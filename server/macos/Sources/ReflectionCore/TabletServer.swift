import Foundation
import Network
import CryptoKit
import Security

public struct ServerStatus: Codable {
    public var state = "starting"
    public var backend = "quartz"
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
}

/// All mutable session and backend state is confined to one serial queue.
public final class TabletServer {
    private let configuration: ServerConfiguration
    private let backend: TabletBackend
    private let mapper: CoordinateMapper
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
    private var sampleGuard = PenSessionGuard()
    private var lastPing: UInt64?
    private var capabilitiesReceived = false
    private var lastStatusWrite = 0.0
    private var sendBudget = OutboundSendBudget()
    private var watchdog: DispatchSourceTimer?
    private var status = ServerStatus()

    public init(configuration: ServerConfiguration, backend: TabletBackend,
                mapper: CoordinateMapper, dryRun: Bool = false, statusURL: URL? = nil,
                verbose: Bool = false, log: @escaping (String) -> Void = { print($0) }) throws {
        try configuration.validate()
        self.configuration = configuration; self.backend = backend; self.mapper = mapper
        self.statusURL = statusURL; self.verbose = verbose; self.log = log
        self.tokenKey = try WireProtocol.tokenKey(configuration.token)
        status.backend = dryRun ? "recording" : "quartz"
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
        sampleGuard = PenSessionGuard()
        sendBudget.resetSession()
        lastPing = nil
        capabilitiesReceived = false
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
            let mapped = mapper.map(sample)
            backend.submit(mapped)
            status.samples += 1
            status.lastSequence = sample.sequence
            status.lastSample = sample
            status.lastMappedX = mapped.point.x; status.lastMappedY = mapped.point.y
            if verbose {
                let data = try JSONEncoder().encode(sample)
                var object = try JSONSerialization.jsonObject(with: data) as! [String: Any]
                object["event"] = "sample"
                object["mappedX"] = mapped.point.x; object["mappedY"] = mapped.point.y
                object["mappedPressure"] = mapped.pressure
                emit(object)
            }
        case 0x11:
            guard payload.count == 8 else { throw ReflectionError.invalid("Invalid ping") }
            let ping = WireProtocol.uint64([UInt8](payload), 0)
            guard lastPing == nil || ping > lastPing! else { throw ReflectionError.invalid("Replayed ping") }
            lastPing = ping
            send(type: 0x12, payload: payload)
        case 0x20:
            guard !capabilitiesReceived, lastSequence == 0,
                  let value = try? JSONSerialization.jsonObject(with: payload), value is [String: Any] else {
                throw ReflectionError.invalid("Invalid capabilities")
            }
            capabilitiesReceived = true
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

    private func tick() {
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

    private func persistStatus() {
        status.pendingSendBytes = sendBudget.pendingBytes
        status.sendQueueLimit = sendBudget.limit
        if let recording = backend as? RecordingBackend {
            status.contactDown = recording.contactDown
            status.backendReleaseCount = recording.releaseCount
        }
        status.updatedAt = ISO8601DateFormatter().string(from: Date())
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
