import XCTest
import CryptoKit
import AppKit
@testable import ReflectionCore

final class CoreTests: XCTestCase {
    func sample(_ x: Double, _ y: Double, _ pressure: Double = 0.5, phase: PenPhase = .move) -> PenSample {
        PenSample(sequence: 1, timestampNanoseconds: 1, phase: phase, tool: .pen,
            buttons: 0, x: x, y: y, pressure: pressure, tiltX: 0, tiltY: 0)
    }

    func testCropRotationAndNegativeDesktopCoordinates() {
        var config = MappingConfiguration()
        config.source = Region(x: 0.25, y: 0.25, width: 0.5, height: 0.5)
        config.rotation = 90
        let mapper = CoordinateMapper(configuration: config,
            target: Region(x: -1920, y: 0, width: 1920, height: 1080))
        let result = mapper.map(sample(0.25, 0.25))
        XCTAssertEqual(result.point.x, -1)
        XCTAssertEqual(result.point.y, 0)
    }

    func testRelativeReentryDoesNotJump() {
        var config = MappingConfiguration()
        config.mode = "relative"
        let mapper = CoordinateMapper(configuration: config,
            target: Region(x: 0, y: 0, width: 1000, height: 1000))
        XCTAssertEqual(mapper.map(sample(0.2, 0.2)).point.x, 500)
        XCTAssertEqual(mapper.map(sample(0.3, 0.2)).point.x, 600, accuracy: 0.0001)
        _ = mapper.map(sample(0.3, 0.2, phase: .leave))
        XCTAssertEqual(mapper.map(sample(0.8, 0.8)).point.x, 600, accuracy: 0.0001)
    }

    func testPressureCurveAndRelease() {
        var config = MappingConfiguration()
        config.pressureGamma = 2
        let mapper = CoordinateMapper(configuration: config,
            target: Region(x: 0, y: 0, width: 100, height: 100))
        XCTAssertEqual(mapper.map(sample(0.5, 0.5)).pressure, 0.25)
        XCTAssertEqual(mapper.map(sample(0.5, 0.5, phase: .up)).pressure, 0)
    }

    func testFragmentedAndCoalescedFrames() throws {
        let first = WireProtocol.frame(type: 1, payload: Data(repeating: 2, count: 32))
        let second = WireProtocol.frame(type: 2, payload: Data(repeating: 3, count: 32))
        var decoder = FrameDecoder()
        XCTAssertTrue(try decoder.append(first.prefix(3)).isEmpty)
        let decoded = try decoder.append(first.dropFirst(3) + second)
        XCTAssertEqual(decoded.count, 2)
        XCTAssertEqual(decoded[0].count, 34)
        XCTAssertEqual(decoded[1][1], 2)
    }

    func testRejectOversizeAndWrongVersion() {
        var decoder = FrameDecoder()
        XCTAssertThrowsError(try decoder.append(Data([0, 0, 32, 0])))
        decoder = FrameDecoder()
        XCTAssertThrowsError(try decoder.append(Data([0, 0, 0, 2, 2, 1])))
    }

    func testAuthenticationRejectsTampering() throws {
        let key = try WireProtocol.tokenKey(String(repeating: "ab", count: 32))
        let message = Data("Reflection/client/v1".utf8) + Data(repeating: 7, count: 32)
        let tag = WireProtocol.mac(key: key, data: message)
        XCTAssertTrue(WireProtocol.verify(key: key, data: message, tag: tag))
        XCTAssertFalse(WireProtocol.verify(key: key, data: message + Data([0]), tag: tag))
    }

    func testRejectNaNAndUnknownButtons() throws {
        var payload = [UInt8](repeating: 0, count: 48)
        payload[45] = 1
        XCTAssertNoThrow(try WireProtocol.decodeSample(Data(payload)))
        payload[16] = 0x7f; payload[17] = 0xc0
        XCTAssertThrowsError(try WireProtocol.decodeSample(Data(payload)))
        payload[16] = 0; payload[17] = 0
        payload[43] = 4
        XCTAssertThrowsError(try WireProtocol.decodeSample(Data(payload)))
    }

    func testConfigRejectsUnknownMappingAndBadToken() {
        var config = ServerConfiguration(token: String(repeating: "a", count: 64))
        XCTAssertNoThrow(try config.validate())
        config.mapping.source.width = 2
        XCTAssertThrowsError(try config.validate())
        config.mapping.source.width = 1
        config.token = "short"
        XCTAssertThrowsError(try config.validate())
    }

    func testSessionSequenceAndContactTransitions() throws {
        func pen(_ sequence: UInt64, _ phase: PenPhase, _ tool: PenTool = .pen) -> PenSample {
            PenSample(sequence: sequence, timestampNanoseconds: 100, phase: phase, tool: tool,
                      buttons: 0, x: 0.5, y: 0.5, pressure: 0.5, tiltX: 0, tiltY: 0)
        }
        var session = PenSessionGuard()
        XCTAssertThrowsError(try session.accept(pen(2, .hover)))
        XCTAssertThrowsError(try session.accept(pen(0, .hover)))
        XCTAssertThrowsError(try session.accept(pen(1, .move)))
        XCTAssertThrowsError(try session.accept(pen(1, .up)))
        try session.accept(pen(1, .down))
        XCTAssertThrowsError(try session.accept(pen(1, .move)))
        XCTAssertThrowsError(try session.accept(pen(2, .down)))
        XCTAssertThrowsError(try session.accept(pen(2, .hover)))
        XCTAssertThrowsError(try session.accept(pen(2, .move, .eraser)))
        try session.accept(pen(3, .move))
        try session.accept(pen(4, .cancel, .eraser))
        try session.accept(pen(5, .down, .eraser))
        try session.accept(pen(6, .up, .eraser))
        try session.accept(pen(7, .hover))
    }

    func testOutboundBudgetBoundAndSessionIsolation() throws {
        var budget = OutboundSendBudget()
        let old = try budget.reserve(65536)
        XCTAssertThrowsError(try budget.reserve(1))
        XCTAssertThrowsError(try budget.reserve(Int.max))
        XCTAssertEqual(budget.pendingBytes, 65536)
        budget.resetSession()
        let current = try budget.reserve(46)
        budget.complete(old)
        XCTAssertEqual(budget.pendingBytes, 46)
        budget.complete(current)
        XCTAssertEqual(budget.pendingBytes, 0)
    }

    func testTiltRotatesWithMapping() {
        let pen = PenSample(sequence: 1, timestampNanoseconds: 1, phase: .down, tool: .pen,
            buttons: 0, x: 0.25, y: 0.5, pressure: 0.5, tiltX: 45, tiltY: 0)
        for (rotation, x, y) in [(90, 0.0, 45.0), (180, -45.0, 0.0), (270, 0.0, -45.0)] {
            var configuration = MappingConfiguration()
            configuration.rotation = rotation
            let mapper = CoordinateMapper(configuration: configuration,
                target: Region(x: 0, y: 0, width: 100, height: 100))
            let result = mapper.map(pen)
            XCTAssertEqual(result.tiltX, x)
            XCTAssertEqual(result.tiltY, y)
            XCTAssertEqual(result.sample.tiltX, 45)
        }
    }

    func testFirstDrawingMovementReachesAppKit() {
        let mapper = CoordinateMapper(configuration: MappingConfiguration(),
            target: Region(x: 100, y: 100, width: 561, height: 221))
        let down = mapper.map(sample(0, 0, phase: .down))
        let move = mapper.map(sample(1.0 / 80, 1.0 / 20))
        let event = QuartzEventFactory.point(move, type: .leftMouseDragged,
                                            previousLocation: down.point)!
        XCTAssertEqual(event.location, CGPoint(x: 107, y: 111))
        let appKitEvent = NSEvent(cgEvent: event)!
        XCTAssertEqual(appKitEvent.type, .leftMouseDragged)
        XCTAssertEqual(appKitEvent.subtype, .tabletPoint)
        XCTAssertEqual(appKitEvent.deltaX, 7)
        XCTAssertEqual(appKitEvent.deltaY, 11)
    }

    func testMotionMetadataIsLimitedToMovementEvents() {
        let mapper = CoordinateMapper(configuration: MappingConfiguration(),
            target: Region(x: 100, y: 100, width: 101, height: 101))
        let mapped = mapper.map(sample(0.5, 0.5))
        let previous = CGPoint(x: 140, y: 160)
        for type in [CGEventType.mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged] {
            let event = QuartzEventFactory.point(mapped, type: type, previousLocation: previous)!
            XCTAssertEqual(event.getIntegerValueField(.mouseEventDeltaX), 10)
            XCTAssertEqual(event.getIntegerValueField(.mouseEventDeltaY), -10)
        }
        for type in [CGEventType.leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp,
                     .otherMouseDown, .otherMouseUp, .tabletPointer] {
            let event = QuartzEventFactory.point(mapped, type: type, previousLocation: previous)!
            XCTAssertEqual(event.getIntegerValueField(.mouseEventDeltaX), 0)
            XCTAssertEqual(event.getIntegerValueField(.mouseEventDeltaY), 0)
        }
        let initial = QuartzEventFactory.point(mapped, type: .mouseMoved)!
        XCTAssertEqual(initial.getIntegerValueField(.mouseEventDeltaX), 0)
        XCTAssertEqual(initial.getIntegerValueField(.mouseEventDeltaY), 0)
    }

    func testSubpixelMotionAccumulatesAndReversesWithoutDrift() {
        let mapper = CoordinateMapper(configuration: MappingConfiguration(),
            target: Region(x: 0, y: 0, width: 101, height: 101))
        var previous = mapper.map(sample(0, 0, phase: .down)).point
        var totalX: Int64 = 0
        var totalY: Int64 = 0
        for step in 1...5 {
            let mapped = mapper.map(sample(Double(step) * 0.002, Double(step) * 0.004))
            let event = QuartzEventFactory.point(mapped, type: .leftMouseDragged, previousLocation: previous)!
            totalX += event.getIntegerValueField(.mouseEventDeltaX)
            totalY += event.getIntegerValueField(.mouseEventDeltaY)
            XCTAssertEqual(event.location, mapped.point)
            previous = mapped.point
        }
        XCTAssertEqual(totalX, 1)
        XCTAssertEqual(totalY, 2)
        for step in stride(from: 4, through: 0, by: -1) {
            let mapped = mapper.map(sample(Double(step) * 0.002, Double(step) * 0.004))
            let event = QuartzEventFactory.point(mapped, type: .leftMouseDragged, previousLocation: previous)!
            totalX += event.getIntegerValueField(.mouseEventDeltaX)
            totalY += event.getIntegerValueField(.mouseEventDeltaY)
            previous = mapped.point
        }
        XCTAssertEqual(totalX, 0)
        XCTAssertEqual(totalY, 0)
    }

    func testNegativeDesktopMotionAndBounds() {
        let mapper = CoordinateMapper(configuration: MappingConfiguration(),
            target: Region(x: -1919.25, y: -1082.75, width: 1, height: 1))
        let mapped = mapper.map(sample(0, 0))
        let movement = QuartzEventFactory.point(mapped, type: .leftMouseDragged,
            previousLocation: CGPoint(x: -1920.75, y: -1080.25))!
        XCTAssertEqual(movement.getIntegerValueField(.mouseEventDeltaX), 2)
        XCTAssertEqual(movement.getIntegerValueField(.mouseEventDeltaY), -3)
        let bounded = QuartzEventFactory.point(mapped, type: .leftMouseDragged,
            previousLocation: CGPoint(x: -1e30, y: 1e30))!
        XCTAssertEqual(bounded.getIntegerValueField(.mouseEventDeltaX), Int64(Int16.max))
        XCTAssertEqual(bounded.getIntegerValueField(.mouseEventDeltaY), Int64(Int16.min))
        let invalid = QuartzEventFactory.point(mapped, type: .leftMouseDragged,
            previousLocation: CGPoint(x: Double.nan, y: Double.infinity))!
        XCTAssertEqual(invalid.getIntegerValueField(.mouseEventDeltaX), 0)
        XCTAssertEqual(invalid.getIntegerValueField(.mouseEventDeltaY), 0)
    }
}
