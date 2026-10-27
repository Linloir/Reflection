import Foundation
import CryptoKit
import ReflectionCore
import AppKit

var passed = 0
func check(_ condition: @autoclosure () -> Bool, _ name: String) {
    guard condition() else { fputs("FAIL: \(name)\n", stderr); exit(1) }
    passed += 1
}
func rejects(_ name: String, _ action: () throws -> Void) {
    do { try action(); fputs("FAIL: \(name)\n", stderr); exit(1) }
    catch { passed += 1 }
}
func hex(_ value: String) -> Data {
    var result = Data()
    var index = value.startIndex
    while index < value.endIndex {
        let end = value.index(index, offsetBy: 2)
        result.append(UInt8(value[index..<end], radix: 16)!)
        index = end
    }
    return result
}
func sample(x: Double, y: Double, phase: PenPhase = .move) -> PenSample {
    PenSample(sequence: 1, timestampNanoseconds: 1, phase: phase, tool: .pen, buttons: 0,
        x: x, y: y, pressure: 0.5, tiltX: 0, tiltY: 0)
}
func sessionSample(_ sequence: UInt64, _ phase: PenPhase, tool: PenTool = .pen,
                   timestamp: UInt64 = 100) -> PenSample {
    PenSample(sequence: sequence, timestampNanoseconds: timestamp, phase: phase,
        tool: tool, buttons: 0, x: 0.5, y: 0.5, pressure: 0.5, tiltX: 0, tiltY: 0)
}

do {
    let discoveryToken = String(repeating: "24", count: 32)
    let discoveryNonce = String(repeating: "ab", count: 16)
    let discoveryResponse = HostDiscoveryService.reply(request: "REFLECTION-DISCOVER/1 " + discoveryNonce,
                                                       port: 24819, token: discoveryToken)!
    let discoveryFields = String(decoding: discoveryResponse, as: UTF8.self).components(separatedBy: "\n")
    check(discoveryFields.count == 5 && discoveryFields[1] == discoveryNonce && discoveryFields[2] == "24819",
          "discovery nonce and endpoint")
    let discoveryMAC = WireProtocol.mac(key: try WireProtocol.tokenKey(discoveryToken),
        data: Data(discoveryFields.prefix(4).joined(separator: "\n").utf8))
    check(discoveryMAC == hex(discoveryFields[4]), "discovery authentication")
    check(!String(decoding: discoveryResponse, as: UTF8.self).contains(discoveryToken), "discovery keeps credentials private")
    check(HostDiscoveryService.reply(request: "REFLECTION-DISCOVER/1 bad", port: 24819, token: discoveryToken) == nil,
          "discovery malformed nonce")
    check(HostDiscoveryService.deviceToken(rootToken: discoveryToken, deviceID: String(repeating: "a", count: 32))
        != HostDiscoveryService.deviceToken(rootToken: discoveryToken, deviceID: String(repeating: "b", count: 32)),
          "pairing credentials differ between tablets")
    let tabletCalibrationBytes = hex("00000000000000013b449ba6")
    let tabletCalibration = try TabletNoiseCalibration.decode(tabletCalibrationBytes, after: 0)
    check(tabletCalibration.sequence == 1 && abs(tabletCalibration.noise - 0.003) < 1e-8,
          "tablet calibration wire vector")
    rejects("tablet calibration replay") { _ = try TabletNoiseCalibration.decode(tabletCalibrationBytes, after: 1) }
    rejects("tablet calibration nonfinite") { _ = try TabletNoiseCalibration.decode(hex("00000000000000017fc00000"), after: 0) }
    rejects("tablet calibration size") { _ = try TabletNoiseCalibration.decode(tabletCalibrationBytes.dropLast(), after: 0) }
    check(TabletNoiseCalibration.acknowledgement(sequence: 1, noise: 0.003) == Data([1]) + tabletCalibrationBytes,
          "tablet calibration acknowledgement vector")
    var noiseConfiguration = StabilizationConfiguration()
    noiseConfiguration.noise = 0.0007
    let tabletNoise = HostPenStabilizer(configuration: noiseConfiguration)
    try tabletNoise.setSessionNoise(0.003)
    check(tabletNoise.configuration.noise == 0.003 && tabletNoise.status.estimatedNoiseX == 0.003,
          "tablet calibration applies session prior")
    try tabletNoise.setSessionNoise(0.004)
    tabletNoise.clearSessionNoise()
    check(tabletNoise.configuration.noise == 0.0007, "tablet session restores host prior")
    try tabletNoise.setSessionNoise(0.002)
    try tabletNoise.setSessionNoise(0)
    check(tabletNoise.configuration.noise == 0.0007, "tablet zero resets override")
    _ = tabletNoise.apply(sample(x: 0.5, y: 0.5, phase: .down))
    rejects("tablet prior cannot change mid-contact") { try tabletNoise.setSessionNoise(0.005) }
    let fixtureURL = CommandLine.arguments.dropFirst().first.map { URL(fileURLWithPath: $0) }
        ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../protocol/vectors.json").standardized
    let fixtures = try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as! [String: Any]
    func fixture(_ key: String) -> Data { hex(fixtures[key] as! String) }
    let key = try WireProtocol.tokenKey(fixtures["key"] as! String)
    let nonce = fixture("nonce")
    for (label, expected) in [("client", "clientProof"), ("server", "serverProof"), ("session", "sessionKey")] {
        let message = Data("Reflection/\(label)/v1".utf8) + nonce
        check(WireProtocol.mac(key: key, data: message) == fixture(expected), "\(label) HMAC fixture")
    }
    let sessionKey = SymmetricKey(data: fixture("sessionKey"))
    check(WireProtocol.frame(type: 0x10, payload: fixture("samplePayload"), key: sessionKey)
        == fixture("sampleFrame"), "signed sample fixture")
    let decoded = try WireProtocol.decodeSample(fixture("samplePayload"))
    check(decoded.sequence == 1 && decoded.timestampNanoseconds == 1234567890, "wire integer endianness")
    check(decoded.x == 0.25 && decoded.y == 0.75 && decoded.pressure == 0.5
          && decoded.tiltX == -30 && decoded.tiltY == 45 && decoded.buttons == 1
          && decoded.phase == .down && decoded.tool == .pen, "wire sample fields")
    var decoder = FrameDecoder()
    let frame = fixture("sampleFrame")
    for byte in frame.dropLast() {
        let result = try decoder.append(Data([byte]))
        check(result.isEmpty, "fragmented frame waits")
    }
    let finalFragment = try decoder.append(Data([frame.last!]))
    check(finalFragment.count == 1, "fragmented frame emitted")
    let coalesced = try decoder.append(frame + frame)
    check(coalesced.count == 2, "coalesced frames emitted")
    rejects("oversize frame") { var d = FrameDecoder(); _ = try d.append(Data([0, 0, 32, 0])) }
    rejects("wrong version") { var d = FrameDecoder(); _ = try d.append(Data([0, 0, 0, 2, 2, 1])) }
    var invalid = fixture("samplePayload")
    invalid[16] = 0x7f; invalid[17] = 0xc0
    rejects("NaN rejected") { _ = try WireProtocol.decodeSample(invalid) }
    invalid = fixture("samplePayload"); invalid[43] = 4
    rejects("reserved buttons rejected") { _ = try WireProtocol.decodeSample(invalid) }
    let tag = WireProtocol.mac(key: key, data: nonce)
    check(!WireProtocol.verify(key: key, data: nonce + Data([0]), tag: tag), "tampered MAC rejected")
    var session = PenSessionGuard()
    rejects("initial sequence must be one") { try session.accept(sessionSample(2, .hover)) }
    rejects("initial sequence cannot be zero") { try session.accept(sessionSample(0, .hover)) }
    rejects("move cannot start contact") { try session.accept(sessionSample(1, .move)) }
    rejects("up cannot start contact") { try session.accept(sessionSample(1, .up)) }
    try session.accept(sessionSample(1, .hover))
    rejects("sample replay rejected") { try session.accept(sessionSample(1, .hover)) }
    try session.accept(sessionSample(3, .down))
    rejects("duplicate down rejected") { try session.accept(sessionSample(4, .down)) }
    rejects("hover during contact rejected") { try session.accept(sessionSample(4, .hover)) }
    rejects("tool swap during contact rejected") { try session.accept(sessionSample(4, .move, tool: .eraser)) }
    rejects("reversed timestamp rejected") { try session.accept(sessionSample(4, .move, timestamp: 99)) }
    try session.accept(sessionSample(4, .move))
    try session.accept(sessionSample(5, .cancel, tool: .eraser))
    try session.accept(sessionSample(6, .down, tool: .eraser))
    try session.accept(sessionSample(7, .up, tool: .eraser))
    try session.accept(sessionSample(8, .hover, tool: .pen))
    check(true, "sequence gaps, equal timestamps and cancel recovery accepted")
    var sendBudget = OutboundSendBudget()
    let firstReservation = try sendBudget.reserve(32768)
    let secondReservation = try sendBudget.reserve(32768)
    check(sendBudget.pendingBytes == 65536, "outbound budget admits exact limit")
    rejects("outbound budget rejects overflow") { _ = try sendBudget.reserve(1) }
    rejects("outbound budget rejects invalid count") { _ = try sendBudget.reserve(-1) }
    rejects("outbound budget avoids integer overflow") { _ = try sendBudget.reserve(Int.max) }
    sendBudget.complete(firstReservation)
    check(sendBudget.pendingBytes == 32768, "write completion releases budget")
    let replacementReservation = try sendBudget.reserve(32768)
    sendBudget.resetSession()
    let currentReservation = try sendBudget.reserve(100)
    sendBudget.complete(secondReservation)
    sendBudget.complete(replacementReservation)
    check(sendBudget.pendingBytes == 100, "old completions do not alter new session budget")
    sendBudget.complete(currentReservation)
    check(sendBudget.pendingBytes == 0, "current session completion releases budget")

    var mapping = MappingConfiguration()
    mapping.source = Region(x: 0.25, y: 0.25, width: 0.5, height: 0.5)
    mapping.rotation = 90
    mapping.pressureGamma = 2
    let mapper = CoordinateMapper(configuration: mapping,
        target: Region(x: -1920, y: 0, width: 1920, height: 1080))
    let mapped = mapper.map(sample(x: 0.25, y: 0.25))
    check(mapped.point.x == -1 && mapped.point.y == 0, "crop rotation negative display")
    check(mapped.pressure == 0.25, "pressure response")
    check(mapper.map(sample(x: 0.5, y: 0.5, phase: .up)).pressure == 0, "release pressure")
    let tilted = PenSample(sequence: 1, timestampNanoseconds: 1, phase: .down, tool: .pen,
        buttons: 0, x: 0.25, y: 0.5, pressure: 0.5, tiltX: 45, tiltY: 0)
    for (rotation, expectedX, expectedY) in [(90, 0.0, 45.0), (180, -45.0, 0.0), (270, 0.0, -45.0)] {
        var rotatedConfig = MappingConfiguration()
        rotatedConfig.rotation = rotation
        let rotatedMapper = CoordinateMapper(configuration: rotatedConfig,
            target: Region(x: 0, y: 0, width: 100, height: 100))
        let rotated = rotatedMapper.map(tilted)
        check(rotated.tiltX == expectedX && rotated.tiltY == expectedY, "rotated tilt \(rotation)")
        check(rotated.sample.tiltX == 45 && rotated.sample.tiltY == 0, "raw tilt preserved \(rotation)")
        let event = QuartzEventFactory.point(rotated, type: .tabletPointer)!
        check(abs(event.getDoubleValueField(.tabletEventTiltX) - expectedX / 90) < 0.0001
            && abs(event.getDoubleValueField(.tabletEventTiltY) - expectedY / 90) < 0.0001,
            "Quartz applies rotated tilt \(rotation)")
    }
    mapping = MappingConfiguration(); mapping.mode = "relative"
    let relative = CoordinateMapper(configuration: mapping,
        target: Region(x: 0, y: 0, width: 1000, height: 1000))
    _ = relative.map(sample(x: 0.2, y: 0.2))
    let moved = relative.map(sample(x: 0.3, y: 0.2))
    _ = relative.map(sample(x: 0.3, y: 0.2, phase: .leave))
    check(relative.map(sample(x: 0.8, y: 0.8)).point == moved.point, "relative reentry without jump")
    let eventsMapper = CoordinateMapper(configuration: MappingConfiguration(),
        target: Region(x: 0, y: 0, width: 1920, height: 1080))
    let event = QuartzEventFactory.point(eventsMapper.map(decoded), type: .leftMouseDown)!
    check(abs(event.getDoubleValueField(.tabletEventPointPressure) - 0.5) < 1.0 / 65535, "Quartz pressure field")
    check(event.getIntegerValueField(.mouseEventSubtype) == 1, "Quartz tablet subtype")
    check(event.getIntegerValueField(.tabletEventPointButtons) == 3, "Quartz tip and barrel bits")
    check(abs(event.getDoubleValueField(.tabletEventTiltX) + 1.0 / 3) < 0.0001, "Quartz normalized tilt")
    let appKitEvent = NSEvent(cgEvent: event)!
    check(appKitEvent.subtype == .tabletPoint, "AppKit sees tablet point")
    check(abs(Double(appKitEvent.pressure) - 0.5) < 1.0 / 255, "AppKit sees legacy mouse pressure")
    check(abs(appKitEvent.tilt.x + 1.0 / 3) < 0.0001, "AppKit sees tilt")
    let tabletEvent = QuartzEventFactory.point(eventsMapper.map(decoded), type: .tabletPointer)!
    let appKitTablet = NSEvent(cgEvent: tabletEvent)!
    check(appKitTablet.type == .tabletPoint, "AppKit sees dedicated tablet point")
    check(abs(Double(appKitTablet.pressure) - 0.5) < 1.0 / 65535, "AppKit tablet pressure precision")
    let strokeMapper = CoordinateMapper(configuration: MappingConfiguration(),
        target: Region(x: 100, y: 100, width: 561, height: 221))
    let strokeStart = strokeMapper.map(sample(x: 0, y: 0, phase: .down))
    let strokeMove = strokeMapper.map(sample(x: 1.0 / 80, y: 1.0 / 20))
    let firstDrag = QuartzEventFactory.point(strokeMove, type: .leftMouseDragged,
                                            previousLocation: strokeStart.point)!
    check(firstDrag.location == CGPoint(x: 107, y: 111), "Quartz first stroke point remains absolute")
    check(firstDrag.getIntegerValueField(.mouseEventDeltaX) == 7
        && firstDrag.getIntegerValueField(.mouseEventDeltaY) == 11, "Quartz first stroke movement")
    let firstAppKitDrag = NSEvent(cgEvent: firstDrag)!
    check(firstAppKitDrag.deltaX == 7 && firstAppKitDrag.deltaY == 11,
          "AppKit receives initial drawing movement")
    for type in [CGEventType.mouseMoved, .rightMouseDragged, .otherMouseDragged] {
        let movement = QuartzEventFactory.point(strokeMove, type: type,
                                               previousLocation: strokeStart.point)!
        check(movement.getIntegerValueField(.mouseEventDeltaX) == 7
            && movement.getIntegerValueField(.mouseEventDeltaY) == 11, "movement delta \(type.rawValue)")
    }
    for type in [CGEventType.leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp,
                 .otherMouseDown, .otherMouseUp, .tabletPointer] {
        let boundary = QuartzEventFactory.point(strokeMove, type: type,
                                               previousLocation: strokeStart.point)!
        check(boundary.getIntegerValueField(.mouseEventDeltaX) == 0
            && boundary.getIntegerValueField(.mouseEventDeltaY) == 0, "button/tablet has no duplicate motion \(type.rawValue)")
    }
    let initialMovement = QuartzEventFactory.point(strokeMove, type: .mouseMoved)!
    check(initialMovement.getIntegerValueField(.mouseEventDeltaX) == 0
        && initialMovement.getIntegerValueField(.mouseEventDeltaY) == 0, "initial movement has no stale delta")
    let negativeMapper = CoordinateMapper(configuration: MappingConfiguration(),
        target: Region(x: -1919.25, y: -1082.75, width: 1, height: 1))
    let negativeMovement = QuartzEventFactory.point(negativeMapper.map(sample(x: 0, y: 0)),
        type: .leftMouseDragged, previousLocation: CGPoint(x: -1920.75, y: -1080.25))!
    check(negativeMovement.getIntegerValueField(.mouseEventDeltaX) == 2
        && negativeMovement.getIntegerValueField(.mouseEventDeltaY) == -3, "negative display movement direction")
    let fineMapper = CoordinateMapper(configuration: MappingConfiguration(),
        target: Region(x: 0, y: 0, width: 101, height: 101))
    var previousFinePoint = fineMapper.map(sample(x: 0, y: 0, phase: .down)).point
    var fineDeltaX: Int64 = 0
    var fineDeltaY: Int64 = 0
    for step in 1...5 {
        let fine = fineMapper.map(sample(x: Double(step) * 0.002, y: Double(step) * 0.004))
        let movement = QuartzEventFactory.point(fine, type: .leftMouseDragged,
                                               previousLocation: previousFinePoint)!
        fineDeltaX += movement.getIntegerValueField(.mouseEventDeltaX)
        fineDeltaY += movement.getIntegerValueField(.mouseEventDeltaY)
        check(movement.location == fine.point, "subpixel absolute coordinates preserved \(step)")
        previousFinePoint = fine.point
    }
    check(fineDeltaX == 1 && fineDeltaY == 2, "small movements accumulate without rounding loss")
    for step in stride(from: 4, through: 0, by: -1) {
        let fine = fineMapper.map(sample(x: Double(step) * 0.002, y: Double(step) * 0.004))
        let movement = QuartzEventFactory.point(fine, type: .leftMouseDragged,
                                               previousLocation: previousFinePoint)!
        fineDeltaX += movement.getIntegerValueField(.mouseEventDeltaX)
        fineDeltaY += movement.getIntegerValueField(.mouseEventDeltaY)
        previousFinePoint = fine.point
    }
    check(fineDeltaX == 0 && fineDeltaY == 0, "subpixel reversal does not drift")
    let boundedMovement = QuartzEventFactory.point(strokeMove, type: .leftMouseDragged,
        previousLocation: CGPoint(x: -1e30, y: 1e30))!
    check(boundedMovement.getIntegerValueField(.mouseEventDeltaX) == Int64(Int16.max)
        && boundedMovement.getIntegerValueField(.mouseEventDeltaY) == Int64(Int16.min), "extreme movement bounded")
    let invalidPrevious = QuartzEventFactory.point(strokeMove, type: .leftMouseDragged,
        previousLocation: CGPoint(x: Double.nan, y: Double.infinity))!
    check(invalidPrevious.getIntegerValueField(.mouseEventDeltaX) == 0
        && invalidPrevious.getIntegerValueField(.mouseEventDeltaY) == 0, "nonfinite prior movement safely ignored")
    let proximity = QuartzEventFactory.proximity(tool: .eraser, enter: true, location: .zero)!
    let appKitProximity = NSEvent(cgEvent: proximity)!
    check(appKitProximity.type == .tabletProximity, "AppKit sees proximity")
    check(appKitProximity.pointingDeviceType == .eraser, "AppKit sees eraser")
    check(appKitProximity.isEnteringProximity, "AppKit sees entering proximity")
    let recorder = RecordingBackend()
    recorder.submit(eventsMapper.map(decoded))
    check(recorder.contactDown, "recording contact held")
    recorder.release()
    check(!recorder.contactDown && recorder.releaseCount == 1, "recording release tracked")
    var config = ServerConfiguration(token: String(repeating: "ab", count: 32))
    try config.validate()
    config.mapping.source.width = 2
    rejects("invalid source region") { try config.validate() }
    let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("reflection-check-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: temporary) }
    let configURL = temporary.appendingPathComponent("server.json")
    let saved = ServerConfiguration(token: String(repeating: "ab", count: 32))
    try saved.save(to: configURL)
    let attributes = try FileManager.default.attributesOfItem(atPath: configURL.path)
    check((attributes[.posixPermissions] as? NSNumber)?.intValue == 0o600, "private config permissions")
    let loaded = try ServerConfiguration.load(from: configURL)
    check(loaded.token == saved.token, "config round trip")

    var legacyObject = try JSONSerialization.jsonObject(with: JSONEncoder().encode(saved)) as! [String: Any]
    var legacyMapping = legacyObject["mapping"] as! [String: Any]
    for name in ["pressureCurve", "positionStepsX", "positionStepsY", "tiltEnabled", "tiltScale"] {
        legacyMapping.removeValue(forKey: name)
    }
    legacyObject["mapping"] = legacyMapping
    let legacy = try JSONDecoder().decode(ServerConfiguration.self,
        from: JSONSerialization.data(withJSONObject: legacyObject))
    check(legacy.mapping == MappingConfiguration(), "old configuration gains neutral defaults")

    var curve = MappingConfiguration()
    curve.pressureGamma = 2
    check(curve.mapPressure(0.5) == 0.25, "empty curve preserves gamma")
    curve.pressureCurve = [.init(input: 0, output: 0), .init(input: 0.25, output: 0.5),
                          .init(input: 0.75, output: 0.9), .init(input: 1, output: 1)]
    try curve.validate()
    check(curve.mapPressure(0) == 0 && curve.mapPressure(1) == 1, "curve endpoints")
    check(abs(curve.mapPressure(0.5) - 0.7) < 1e-12, "piecewise curve replaces gamma")
    check(curve.mapPressure(-1) == 0 && curve.mapPressure(2) == 1, "curve input clamped")
    let curvedMapper = CoordinateMapper(configuration: curve, target: Region(x: 0, y: 0, width: 101, height: 101))
    for phase in [PenPhase.up, .cancel, .leave, .hover] {
        check(curvedMapper.map(sample(x: 0.5, y: 0.5, phase: phase)).pressure == 0,
              "curve cannot create pressure outside contact \(phase.rawValue)")
    }
    for invalidCurve: [PressurePoint] in [
        [.init(input: 0, output: 0)],
        [.init(input: 0, output: 0.1), .init(input: 1, output: 1)],
        [.init(input: 0, output: 0), .init(input: 0, output: 0.5), .init(input: 1, output: 1)],
        [.init(input: 0, output: 0), .init(input: 0.3, output: 0.8),
         .init(input: 0.6, output: 0.2), .init(input: 1, output: 1)],
        [.init(input: 0, output: 0), .init(input: .nan, output: 0.5), .init(input: 1, output: 1)]
    ] {
        var invalidMapping = curve
        invalidMapping.pressureCurve = invalidCurve
        rejects("invalid pressure curve") { try invalidMapping.validate() }
    }
    var resolution = MappingConfiguration()
    resolution.positionStepsX = 5
    resolution.positionStepsY = 3
    let quantized = CoordinateMapper(configuration: resolution, target: Region(x: -100, y: -50, width: 101, height: 101))
    check(quantized.map(sample(x: 0.36, y: 0.6)).point == CGPoint(x: -75, y: 0), "coordinate levels and negative origin")
    check(quantized.map(sample(x: 1, y: 1)).point == CGPoint(x: 0, y: 50), "coordinate grid includes endpoint")
    resolution.mode = "relative"
    let fineRelative = CoordinateMapper(configuration: resolution,
        target: Region(x: 0, y: 0, width: 101, height: 101), initialCursor: .zero)
    _ = fineRelative.map(sample(x: 0, y: 0))
    var fineRelativePoint = CGPoint.zero
    for step in 1...20 {
        fineRelativePoint = fineRelative.map(sample(x: Double(step) * 0.01, y: 0)).point
    }
    check(fineRelativePoint.x == 25, "quantized relative motion accumulates substep deltas")
    for steps in [-1, 1, 65537] {
        var invalidMapping = MappingConfiguration()
        invalidMapping.positionStepsY = steps
        rejects("invalid coordinate steps \(steps)") { try invalidMapping.validate() }
    }
    var tiltConfig = MappingConfiguration()
    tiltConfig.tiltEnabled = false
    let disabledTilt = CoordinateMapper(configuration: tiltConfig,
        target: Region(x: 0, y: 0, width: 100, height: 100)).map(tilted)
    check(disabledTilt.tiltX == 0 && disabledTilt.tiltY == 0 && disabledTilt.sample.tiltX == 45,
          "tilt disabled without mutating raw sample")
    tiltConfig.tiltEnabled = true
    tiltConfig.tiltScale = 2
    tiltConfig.rotation = 90
    let scaledTilt = CoordinateMapper(configuration: tiltConfig,
        target: Region(x: 0, y: 0, width: 100, height: 100)).map(tilted)
    check(scaledTilt.tiltX == 0 && scaledTilt.tiltY == 90, "tilt scale after rotation")

    let profiles = ProfileStore(configurationURL: configURL)
    let initialProfiles = try profiles.list()
    check(initialProfiles.isEmpty, "missing profile directory is empty")
    try profiles.save(name: "Soft pencil", mapping: curve)
    let firstProfile = try profiles.load(name: "Soft pencil")
    let savedProfiles = try profiles.list()
    check(firstProfile == curve, "profile round trip")
    check(savedProfiles == ["Soft pencil"], "profile enumeration")
    rejects("profile overwrite requires explicit option") { try profiles.save(name: "Soft pencil", mapping: resolution) }
    try profiles.save(name: "Soft pencil", mapping: resolution, overwrite: true)
    let replacedProfile = try profiles.load(name: "Soft pencil")
    check(replacedProfile == resolution, "explicit profile replacement")
    let profileText = try String(contentsOf: profiles.directory.appendingPathComponent("Soft pencil.json"), encoding: .utf8)
    check(!profileText.contains("token") && !profileText.contains("port") && !profileText.contains("bind"),
          "profiles exclude credentials and listener settings")
    for invalidName in ["", "../escape", "a/b", "name.json", " trailing ", String(repeating: "x", count: 65)] {
        rejects("invalid profile name") { try profiles.save(name: invalidName, mapping: curve) }
    }
    try profiles.delete(name: "Soft pencil")
    let remainingProfiles = try profiles.list()
    check(remainingProfiles.isEmpty, "profile deletion")
    passed += try touchpadChecks()
    passed += try usbSelectionChecks()
    print("{\"checks\":\(passed),\"result\":\"passed\"}")
} catch {
    fputs("FAIL: \(error)\n", stderr)
    exit(1)
}
