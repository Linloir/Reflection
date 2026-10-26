import Foundation
import Network
import CryptoKit
import Security
import CoreGraphics

public struct ServerStatus: Codable {
    public var state = "starting"
    public var backend = "quartz"
    public var inputMode = "api"
    public var inputError: String?
    public var authenticated = false
    public var sessions: UInt64 = 0
    public var samples: UInt64 = 0
    public var rejectedConnections: UInt64 = 0
    public var protocolErrors: UInt64 = 0
    public var lastSequence: UInt64?
    public var lastSample: PenSample?
    public var lastMappedX: Double?
    public var lastMappedY: Double?
    public var lastDisconnect: String?
    public var contactDown: Bool?
    public var backendReleaseCount: UInt64?
    public var pendingSendBytes = 0
    public var sendQueueLimit = 65536
    public var updatedAt = ""
    public var pid = ProcessInfo.processInfo.processIdentifier
    public var mapping: MappingStatus?
    public var stabilization: StabilizationStatus?
    public var calibration: CalibrationStatus?
}

/// All mutable session and backend state is confined to one serial queue.
public final class TabletServer {
    private let configuration: ServerConfiguration
    private let backend: TabletBackend
    private var mapper: CoordinateMapper
    private var screenMapping: ScreenMappingState
    private let stabilizer: HostPenStabilizer
    private let calibration: HostCalibration
    private let configurationURL: URL?
    private let tokenKey: SymmetricKey
    private let queue = DispatchQueue(label: "org.reflection.server", qos: .userInteractive)
    private let statusQueue = DispatchQueue(label: "org.reflection.status", qos: .utility)
    private let statusURL: URL?
    private let log: (String) -> Void
    private let verbose: Bool
    private var listener: NWListener?
    private var connection: NWConnection?
    private var decoder = FrameDecoder()
    private var nonce = Data()
    private var sessionKey: SymmetricKey?
    private var connectedAt = 0.0
    private var lastAuthenticatedAt = 0.0
    private var lastSequence: UInt64 = 0
    private var sampleGuard = PenSequenceGuard()
    private var lastPing: UInt64?
    private var capabilitiesReceived = false
    private var mappingNegotiated = false
    private var mappingRequestSequence: UInt64 = 0
    private var mappingResponseRevision: UInt64 = 0
    private var lastMappingSentRevision: UInt64 = 0
    private var lastDisplayRefresh = 0.0
    private var lastStatusWrite = 0.0
    private var sendBudget = OutboundSendBudget()
    private var watchdog: DispatchSourceTimer?
    private var status = ServerStatus()

    public init(configuration: ServerConfiguration, backend: TabletBackend,
                mapper: CoordinateMapper, dryRun: Bool = false, statusURL: URL? = nil,
                verbose: Bool = false, configurationURL: URL? = nil,
                log: @escaping (String) -> Void = { print($0) }) throws {
        try configuration.validate()
        self.configuration = configuration; self.backend = backend; self.mapper = mapper
        self.stabilizer = HostPenStabilizer(configuration: configuration.stabilization)
        self.calibration = HostCalibration(noise: configuration.stabilization.noise)
        self.configurationURL = configurationURL
        let displays = try availableDisplays()
        let selected = configuration.mapping.displayUUID
            ?? displays.first(where: { $0.id == configuration.mapping.displayID })?.stableID
            ?? configuration.mapping.displayID.map(String.init)
        let screenMapping = try ScreenMappingState(displays: mappingDisplays(displays), selected: selected,
                                                  custom: configuration.mapping.target)
        self.screenMapping = screenMapping
        if mapper.target != screenMapping.target {
            self.mapper = CoordinateMapper(configuration: configuration.mapping, target: screenMapping.target,
                                           initialCursor: CGEvent(source: nil)?.location)
        }
        self.statusURL = statusURL; self.verbose = verbose; self.log = log
        self.tokenKey = try WireProtocol.tokenKey(configuration.token)
        status.inputMode = (backend as? ManagedTabletBackend)?.mode ?? configuration.inputMode
        status.backend = dryRun ? "recording" : status.inputMode == "driver" ? "virtual-hid" : "quartz"
    }

    /// Executed through the same-user control socket. Pen and mapping changes share one queue.
    public func mappingCommand(_ command: String) throws -> Data {
        try queue.sync {
            refreshDisplayLayout()
            let parts = command.split(separator: " ", maxSplits: 1).map(String.init)
            let action: UInt8
            switch parts.first {
            case "status": action = 0
            case "next": action = 1
            case "previous": action = 2
            case "all": action = 3
            case "select": action = 4
            default: throw ReflectionError.invalid("Unknown mapping command")
            }
            guard action == 4 ? parts.count == 2 : parts.count == 1 else {
                throw ReflectionError.invalid("mapping select requires one display identifier")
            }
            var identifier = parts.count == 2 ? parts[1] : ""
            if action == 4, let numeric = UInt32(identifier),
               let display = try availableDisplays().first(where: { $0.id == numeric }) { identifier = display.stableID }
            try screenMapping.request(action, displayID: identifier)
            synchronizeMapper()
            sendMappingStatus()
            persistStatus()
            return try JSONEncoder().encode(screenMapping.status)
        }
    }

    public func calibrationCommand(_ command: String) throws -> Data {
        try queue.sync {
            let wasActive = calibration.status.active
            switch command {
            case "status": break
            case "start":
                guard status.authenticated, stabilizer.rawClient else {
                    throw ReflectionError.invalid("Connect an updated tablet that sends raw pen samples before calibrating.")
                }
                backend.release(); screenMapping.release(); synchronizeMapper(); mapper.reset()
                stabilizer.reset(waitForDown: true)
                calibration.start(noise: stabilizer.configuration.noise)
            case "finish":
                if let noise = calibration.finish() {
                    do {
                        guard let configurationURL else { throw ReflectionError.invalid("The configuration path is unavailable") }
                        var current = try ServerConfiguration.load(from: configurationURL)
                        current.stabilization.noise = noise
                        try current.save(to: configurationURL)
                        stabilizer.configuration.noise = noise
                        calibration.complete(noise: noise)
                    } catch { calibration.fail("Could not save calibration: \(error). Previous noise setting was kept.") }
                }
                if wasActive && !calibration.status.active {
                    backend.release(); screenMapping.release(); synchronizeMapper()
                    stabilizer.reset(waitForDown: true); mapper.reset()
                }
            case "cancel":
                calibration.cancel()
                if wasActive {
                    backend.release(); screenMapping.release(); synchronizeMapper()
                    stabilizer.reset(waitForDown: true); mapper.reset()
                }
            default: throw ReflectionError.invalid("calibration requires start, finish, cancel, or status")
            }
            persistStatus()
            return try JSONEncoder().encode(calibration.status)
        }
    }

    public func liveStatus() throws -> Data {
        try queue.sync { updateStatus(); return try JSONEncoder().encode(status) }
    }

    public func start(onFailure: @escaping (Error) -> Void) throws {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(configuration.bind),
            port: NWEndpoint.Port(rawValue: configuration.port)!)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.status.state = "listening"
                self.emit(["event": "listening", "bind": self.configuration.bind,
                           "port": self.configuration.port, "backend": self.status.backend,
                           "protocol": 1, "encrypted": false])
                self.persistStatus()
            case .failed(let error):
                self.status.state = "failed"
                self.disconnect("listener_failure")
                self.persistStatus()
                onFailure(error)
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] in self?.accept($0) }
        listener.start(queue: queue)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(250), repeating: .milliseconds(250))
        timer.setEventHandler { [weak self] in self?.tick() }
        watchdog = timer
        timer.resume()
    }

    public func stop(completion: @escaping () -> Void) {
        queue.async {
            self.status.state = "stopped"
            self.disconnect("server_stopped")
            self.listener?.cancel()
            self.watchdog?.cancel()
            self.persistStatus()
            self.statusQueue.async(execute: completion)
        }
    }

    private func accept(_ incoming: NWConnection) {
        guard connection == nil else {
            status.rejectedConnections += 1
            incoming.cancel()
            return
        }
        connection = incoming
        decoder = FrameDecoder()
        sessionKey = nil
        lastSequence = 0
        sampleGuard = PenSequenceGuard()
        stabilizer.rawClient = false
        stabilizer.reset()
        sendBudget.resetSession()
        lastPing = nil
        capabilitiesReceived = false
        mappingNegotiated = false
        mappingRequestSequence = 0
        mappingResponseRevision = 0
        lastMappingSentRevision = 0
        connectedAt = ProcessInfo.processInfo.systemUptime
        status.authenticated = false
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            disconnect("randomness_failure"); return
        }
        nonce = Data(bytes)
        incoming.stateUpdateHandler = { [weak self, weak incoming] state in
            guard let self, let incoming, self.connection === incoming else { return }
            switch state {
            case .ready:
                self.send(type: 1, payload: self.nonce, signed: false)
                self.receive(incoming)
            case .failed: self.disconnect("transport_failure")
            case .cancelled: self.disconnect("transport_cancelled")
            default: break
            }
        }
        incoming.start(queue: queue)
    }

    private func receive(_ incoming: NWConnection) {
        incoming.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self, weak incoming] data, _, complete, error in
            guard let self, let incoming, self.connection === incoming else { return }
            do {
                if let data {
                    for body in try self.decoder.append(data) {
                        guard self.connection === incoming else { break }
                        try self.handle(body)
                    }
                }
            } catch {
                self.status.protocolErrors += 1
                self.disconnect("invalid_or_unauthenticated_frame")
                return
            }
            if complete || error != nil { self.disconnect("transport_closed") }
            else if self.connection === incoming { self.receive(incoming) }
        }
    }

    private func handle(_ body: Data) throws {
        let bytes = [UInt8](body)
        let type = bytes[1]
        if sessionKey == nil {
            guard type == 2, body.count == 34 else { throw ReflectionError.invalid("Expected authentication") }
            var message = Data("Reflection/client/v1".utf8)
            message.append(nonce)
            guard WireProtocol.verify(key: tokenKey, data: message, tag: Data(body.dropFirst(2))) else {
                throw ReflectionError.invalid("Authentication failed")
            }
            var serverMessage = Data("Reflection/server/v1".utf8)
            serverMessage.append(nonce)
            send(type: 3, payload: WireProtocol.mac(key: tokenKey, data: serverMessage), signed: false)
            var sessionMessage = Data("Reflection/session/v1".utf8)
            sessionMessage.append(nonce)
            sessionKey = SymmetricKey(data: WireProtocol.mac(key: tokenKey, data: sessionMessage))
            lastAuthenticatedAt = ProcessInfo.processInfo.systemUptime
            status.authenticated = true
            status.sessions += 1
            emit(["event": "authenticated", "session": status.sessions])
            persistStatus()
            return
        }
        guard body.count >= 34, let sessionKey,
              WireProtocol.verify(key: sessionKey, data: Data(body.dropLast(32)),
                                  tag: Data(body.suffix(32))) else {
            throw ReflectionError.invalid("Invalid authentication code")
        }
        let payload = Data(body.dropFirst(2).dropLast(32))
        switch type {
        case 0x10:
            let sample = try WireProtocol.decodeSample(payload)
            try sampleGuard.accept(sample)
            lastSequence = sample.sequence
            status.samples += 1
            status.lastSequence = sample.sequence
            status.lastSample = sample
            if calibration.status.active {
                calibration.add(sample)
                if !calibration.status.active { stabilizer.reset(waitForDown: true); mapper.reset() }
            } else if let processed = stabilizer.apply(sample) {
                let mapped = mapper.map(processed)
                try backend.submit(mapped)
                screenMapping.didSubmit(processed.phase)
                synchronizeMapper()
                sendMappingStatus()
                status.lastMappedX = mapped.point.x; status.lastMappedY = mapped.point.y
                if verbose {
                let data = try JSONEncoder().encode(sample)
                var object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
                object["event"] = "sample"
                object["mappedX"] = mapped.point.x; object["mappedY"] = mapped.point.y
                object["mappedPressure"] = mapped.pressure
                emit(object)
                }
            }
        case 0x11:
            guard payload.count == 8 else { throw ReflectionError.invalid("Invalid ping") }
            let ping = WireProtocol.uint64([UInt8](payload), 0)
            guard lastPing == nil || ping > lastPing! else { throw ReflectionError.invalid("Replayed ping") }
            lastPing = ping
            send(type: 0x12, payload: payload)
            sendMappingStatus()
        case 0x20:
            guard !capabilitiesReceived, lastSequence == 0,
                  let value = try? JSONSerialization.jsonObject(with: payload), value is [String: Any] else {
                throw ReflectionError.invalid("Invalid capabilities")
            }
            capabilitiesReceived = true
            struct Capabilities: Decodable { let hostStabilization: Int? }
            stabilizer.rawClient = (try? JSONDecoder().decode(Capabilities.self, from: payload))?.hostStabilization == 1
            mappingNegotiated = MappingRequest.supportsControl(in: payload)
            if mappingNegotiated { sendMappingStatus(force: true) }
        case 0x21:
            guard mappingNegotiated else { throw ReflectionError.invalid("Mapping control was not negotiated") }
            let request = try MappingRequest.decode(payload, after: mappingRequestSequence)
            mappingRequestSequence = request.sequence
            try screenMapping.request(request.action, displayID: request.displayID)
            synchronizeMapper()
            sendMappingStatus(force: true)
            persistStatus()
        default:
            throw ReflectionError.invalid("Unknown frame type")
        }
        lastAuthenticatedAt = ProcessInfo.processInfo.systemUptime
    }

    private func send(type: UInt8, payload: Data, signed: Bool = true) {
        guard let connection else { return }
        let frame = WireProtocol.frame(type: type, payload: payload, key: signed ? sessionKey : nil)
        let reservation: OutboundSendBudget.Reservation
        do {
            reservation = try sendBudget.reserve(frame.count)
        } catch {
            disconnect("send_backpressure")
            return
        }
        connection.send(content: frame, completion: .contentProcessed { [weak self, weak connection] error in
            guard let self else { return }
            self.sendBudget.complete(reservation)
            guard let connection, self.connection === connection else { return }
            if error != nil { self.disconnect("send_failure") }
        })
    }

    private func sendMappingStatus(force: Bool = false) {
        guard mappingNegotiated, sessionKey != nil,
              force || lastMappingSentRevision != screenMapping.revision else { return }
        var snapshot = screenMapping.status
        mappingResponseRevision += 1
        snapshot.revision = mappingResponseRevision
        guard let data = try? JSONEncoder().encode(snapshot), data.count <= 4062 else {
            disconnect("mapping_status_too_large"); return
        }
        lastMappingSentRevision = screenMapping.revision
        send(type: 0x22, payload: data)
    }

    private func synchronizeMapper() {
        guard mapper.target != screenMapping.target else { return }
        // ScreenMappingState guarantees the old contact has ended before this target changes.
        backend.release()
        stabilizer.reset()
        mapper = CoordinateMapper(configuration: configuration.mapping, target: screenMapping.target,
                                  initialCursor: CGEvent(source: nil)?.location)
    }

    private func refreshDisplayLayout() {
        if let displays = try? availableDisplays() {
            try? screenMapping.updateDisplays(mappingDisplays(displays))
            synchronizeMapper()
        }
        lastDisplayRefresh = ProcessInfo.processInfo.systemUptime
    }

    private func tick() {
        if let managed = backend as? ManagedTabletBackend {
            if managed.refresh() {
                screenMapping.release()
                stabilizer.reset(waitForDown: true)
                mapper.reset()
                status.inputMode = managed.mode
                if status.backend != "recording" { status.backend = managed.mode == "driver" ? "virtual-hid" : "quartz" }
            }
            status.inputError = managed.error
        }
        let wasCalibrating = calibration.status.active
        calibration.tick()
        if wasCalibrating && !calibration.status.active { stabilizer.reset(waitForDown: true); mapper.reset() }
        if ProcessInfo.processInfo.systemUptime - lastDisplayRefresh >= 1 {
            refreshDisplayLayout()
            sendMappingStatus()
        }
        if connection != nil {
            let now = ProcessInfo.processInfo.systemUptime
            if sessionKey == nil && now - connectedAt >= 5 { disconnect("handshake_timeout") }
            else if sessionKey != nil && now - lastAuthenticatedAt >= 2 { disconnect("heartbeat_timeout") }
        }
        if ProcessInfo.processInfo.systemUptime - lastStatusWrite >= 1 { persistStatus() }
    }

    private func disconnect(_ reason: String) {
        guard let old = connection else { return }
        connection = nil
        sendBudget.resetSession()
        old.stateUpdateHandler = nil
        old.cancel()
        backend.release()
        stabilizer.reset()
        stabilizer.rawClient = false
        if calibration.status.active { calibration.fail("The tablet disconnected. Previous calibration was kept.") }
        screenMapping.release()
        synchronizeMapper()
        mapper.reset()
        sessionKey = nil
        nonce = Data()
        status.authenticated = false
        status.lastDisconnect = reason
        emit(["event": "disconnected", "reason": reason])
        persistStatus()
    }

    private func emit(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return }
        log(text)
    }

    private func updateStatus() {
        status.mapping = screenMapping.status
        status.stabilization = stabilizer.status
        if calibration.status.active || !status.authenticated { status.stabilization?.active = false }
        status.stabilization?.legacyClient = status.authenticated && !stabilizer.rawClient
        status.calibration = calibration.status
        status.pendingSendBytes = sendBudget.pendingBytes
        status.sendQueueLimit = sendBudget.limit
        if let recording = (backend as? RecordingBackend) ?? (backend as? ManagedTabletBackend)?.recording {
            status.contactDown = recording.contactDown
            status.backendReleaseCount = recording.releaseCount
        }
        status.updatedAt = ISO8601DateFormatter().string(from: Date())
    }

    private func persistStatus() {
        updateStatus()
        guard let statusURL else { return }
        lastStatusWrite = ProcessInfo.processInfo.systemUptime
        let snapshot = status
        statusQueue.async { [log] in
          do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try writePrivateData(encoder.encode(snapshot), to: statusURL)
          } catch {
            log("{\"event\":\"status_write_failed\"}")
          }
        }
    }
}
