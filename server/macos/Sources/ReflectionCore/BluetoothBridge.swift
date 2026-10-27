import Foundation
import IOBluetooth
import Network

/// Native RFCOMM transport adapter. An ordered local TCP hop reuses the exact
/// authenticated session engine, including its watchdog and single-writer policy.
public final class BluetoothBridge: NSObject {
    public static let serviceUUID = "7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758"
    private var record: IOBluetoothSDPServiceRecord?
    private var notification: IOBluetoothUserNotification?
    private var sessions: [UUID: BluetoothRelaySession] = [:]
    private let host: String
    private let port: UInt16
    private let log: (String) -> Void
    private let serviceIdentifier: String
    private let maximumSessions: Int
    private let requireBonded: Bool
    private var running = false
    private var recoveryWork: DispatchWorkItem?
    private var recoveryGeneration: UInt64 = 0
    private var retryDelay: TimeInterval = 1
    private var lastFailure = ""
    public private(set) var channelID: BluetoothRFCOMMChannelID = 0

    public init(host: String, port: UInt16, serviceIdentifier: String = BluetoothBridge.serviceUUID,
                maximumSessions: Int = 1, requireBonded: Bool = false,
                log: @escaping (String) -> Void = { print($0) }) {
        self.host = host == "0.0.0.0" ? "127.0.0.1" : (host == "::" ? "::1" : host)
        self.port = port
        self.log = log
        self.serviceIdentifier = serviceIdentifier
        self.maximumSessions = maximumSessions
        self.requireBonded = requireBonded
    }

    public static var controllerAvailable: Bool {
        IOBluetoothHostController.default() != nil
    }

    /// Call start and stop on the main thread. Retain the bridge after a startup
    /// error: it retries until stopped when the controller or permission recovers.
    public func start() throws {
        guard !running else { return }
        guard UUID(uuidString: serviceIdentifier) != nil else { throw ReflectionError.invalid("Invalid Bluetooth service UUID") }
        running = true
        try startService()
    }

    private static var controllerPowered: Bool {
        IOBluetoothHostController.default()?.powerState == kBluetoothHCIPowerStateON
    }

    private func startService() throws {
        do {
            try publishService()
            retryDelay = 1
            lastFailure = ""
            scheduleRecovery(after: 5)
        } catch {
            releaseService()
            retry(after: error)
            throw error
        }
    }

    private func publishService() throws {
        guard Self.controllerPowered else { throw ReflectionError.invalid("Bluetooth controller is unavailable or powered off") }
        guard var uuid = UUID(uuidString: serviceIdentifier)?.uuid else { throw ReflectionError.invalid("Invalid Bluetooth service UUID") }
        let uuidData = withUnsafeBytes(of: &uuid) { Data($0) }
        let dictionary: [String: Any] = [
            "0001 - ServiceClassIDList": [uuidData],
            "0004 - ProtocolDescriptorList": [
                [Data([0x01, 0x00])],
                [Data([0x00, 0x03]), ["DataElementType": 1, "DataElementSize": 1, "DataElementValue": 1]]
            ],
            "0005 - BrowseGroupList": [Data([0x10, 0x02])],
            "0100 - ServiceName": requireBonded ? "Reflection Pairing" : "Reflection Tablet",
            "LocalAttributes": ["Persistent": false]
        ]
        guard let record = IOBluetoothSDPServiceRecord.publishedServiceRecord(with: dictionary) else {
            throw ReflectionError.invalid("Bluetooth SDP publication failed; check Bluetooth permission and controller state")
        }
        self.record = record
        guard record.getRFCOMMChannelID(&channelID) == kIOReturnSuccess, channelID > 0 else {
            throw ReflectionError.invalid("Bluetooth RFCOMM channel allocation failed")
        }
        notification = IOBluetoothRFCOMMChannel.register(
            forChannelOpenNotifications: self, selector: #selector(opened(_:channel:)),
            withChannelID: channelID, direction: kIOBluetoothUserNotificationChannelDirectionIncoming)
        guard notification != nil else {
            throw ReflectionError.invalid("Bluetooth RFCOMM listener registration failed")
        }
        log("{\"event\":\"bluetooth_listening\",\"channel\":\(channelID),\"uuid\":\"\(serviceIdentifier)\"}")
    }

    public func stop() {
        running = false
        recoveryGeneration &+= 1
        recoveryWork?.cancel()
        recoveryWork = nil
        retryDelay = 1
        lastFailure = ""
        releaseService()
    }

    private func scheduleRecovery(after delay: TimeInterval) {
        guard running else { return }
        recoveryWork?.cancel()
        recoveryGeneration &+= 1
        let generation = recoveryGeneration
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.running, self.recoveryGeneration == generation else { return }
            self.recoveryWork = nil
            if self.notification != nil {
                if Self.controllerPowered { self.scheduleRecovery(after: 5); return }
                self.releaseService()
                self.retry(after: ReflectionError.invalid("Bluetooth controller is unavailable or powered off"))
            } else {
                try? self.startService()
            }
        }
        recoveryWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func retry(after error: Error) {
        let delay = retryDelay
        retryDelay = min(30, retryDelay * 2)
        let reason = String(describing: error)
        if reason != lastFailure {
            lastFailure = reason
            if let data = try? JSONSerialization.data(withJSONObject: [
                "event": "bluetooth_waiting", "uuid": serviceIdentifier,
                "reason": reason, "retrySeconds": delay
            ]), let message = String(data: data, encoding: .utf8) {
                log(message)
            }
        }
        scheduleRecovery(after: delay)
    }

    private func releaseService() {
        notification?.unregister()
        notification = nil
        for session in sessions.values { session.close() }
        sessions.removeAll()
        _ = record?.remove()
        record = nil
        channelID = 0
    }

    @objc private func opened(_ notification: IOBluetoothUserNotification, channel: IOBluetoothRFCOMMChannel) {
        guard running, self.notification === notification else { channel.close(); return }
        guard sessions.count < maximumSessions else { channel.close(); return }
        if requireBonded {
            guard let device = channel.getDevice(), device.isPaired(), device.getEncryptionMode() != 0 else {
                channel.close(); return
            }
        }
        let id = UUID()
        let relay = BluetoothRelaySession(channel: channel, host: host, port: port) { [weak self] in
            DispatchQueue.main.async { self?.sessions.removeValue(forKey: id) }
        }
        sessions[id] = relay
        relay.start()
    }

    deinit { stop() }
}

private final class BluetoothRelaySession: NSObject, IOBluetoothRFCOMMChannelDelegate {
    private let channel: IOBluetoothRFCOMMChannel
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "org.reflection.bluetooth", qos: .userInteractive)
    private let writeQueue = DispatchQueue(label: "org.reflection.bluetooth.write", qos: .userInitiated)
    private let ended: () -> Void
    private var closed = false
    private let incomingBudgetLock = NSLock()
    private var incomingPendingBytes = 0
    private var writeGeneration: UInt64 = 0
    private var ready = false

    init(channel: IOBluetoothRFCOMMChannel, host: String, port: UInt16, ended: @escaping () -> Void) {
        self.channel = channel
        self.ended = ended
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!,
            using: NWParameters(tls: nil, tcp: tcp))
    }

    func start() {
        guard channel.setDelegate(self) == kIOReturnSuccess else { close(); return }
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.ready = true
                self.receive()
            case .waiting, .failed, .cancelled: self.closeOnQueue()
            default: break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, !self.ready else { return }
            self.closeOnQueue()
        }
    }

    func close() { queue.async { self.closeOnQueue() } }

    private func closeOnQueue() {
        guard !closed else { return }
        closed = true
        writeGeneration &+= 1
        connection.stateUpdateHandler = nil
        connection.cancel()
        DispatchQueue.main.async {
            self.channel.setDelegate(nil)
            self.channel.close()
            self.ended()
        }
    }

    func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        guard dataLength > 0 else { return }
        guard reserveIncoming(dataLength) else { close(); return }
        let data = Data(bytes: dataPointer, count: dataLength)
        queue.async { [self] in
            guard !self.closed else { self.releaseIncoming(data.count); return }
            self.connection.send(content: data, completion: .contentProcessed { [weak self] error in
                guard let self else { return }
                self.releaseIncoming(data.count)
                if error != nil { self.closeOnQueue() }
            })
        }
    }

    func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) { close() }

    private func reserveIncoming(_ bytes: Int) -> Bool {
        incomingBudgetLock.lock()
        defer { incomingBudgetLock.unlock() }
        guard bytes <= 65536 - incomingPendingBytes else { return false }
        incomingPendingBytes += bytes
        return true
    }

    private func releaseIncoming(_ bytes: Int) {
        incomingBudgetLock.lock()
        incomingPendingBytes -= bytes
        incomingBudgetLock.unlock()
    }

    private func receive() {
        guard !closed else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, complete, error in
            guard let self, !self.closed else { return }
            guard let data, !data.isEmpty else {
                if complete || error != nil { self.closeOnQueue() }
                else { self.receive() }
                return
            }
            // Read the next chunk only after the current one is written. This bounds
            // memory and preserves stream order even when the radio stalls.
            self.writeGeneration &+= 1
            let generation = self.writeGeneration
            self.queue.asyncAfter(deadline: .now() + 2) { [weak self] in
                guard let self, !self.closed, self.writeGeneration == generation else { return }
                self.closeOnQueue()
            }
            self.writeQueue.async {
                let mtu = max(1, Int(self.channel.getMTU()))
                var offset = 0
                var result = kIOReturnSuccess
                while offset < data.count {
                    var chunk = Data(data.dropFirst(offset).prefix(mtu))
                    result = chunk.withUnsafeMutableBytes {
                        self.channel.writeSync($0.baseAddress, length: UInt16($0.count))
                    }
                    if result != kIOReturnSuccess { break }
                    offset += chunk.count
                }
                self.queue.async {
                    self.writeGeneration &+= 1
                    if result != kIOReturnSuccess || complete || error != nil { self.closeOnQueue() }
                    else { self.receive() }
                }
            }
        }
    }
}
