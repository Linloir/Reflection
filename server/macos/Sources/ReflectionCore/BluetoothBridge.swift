import Foundation
import IOBluetooth
import Network

/// Native RFCOMM transport adapter. An ordered local TCP hop reuses the exact
/// authenticated session engine, including its watchdog and single-writer policy.
public final class BluetoothBridge: NSObject {
    public static let serviceUUID = "7b011e5a-8b31-4c40-8d7a-3e6c6dcbb758"
    private var record: IOBluetoothSDPServiceRecord?
    private var notification: IOBluetoothUserNotification?
    private var session: BluetoothRelaySession?
    private let host: String
    private let port: UInt16
    private let log: (String) -> Void
    public private(set) var channelID: BluetoothRFCOMMChannelID = 0

    public init(host: String, port: UInt16, log: @escaping (String) -> Void = { print($0) }) {
        self.host = host == "0.0.0.0" ? "127.0.0.1" : (host == "::" ? "::1" : host)
        self.port = port
        self.log = log
    }

    public static var controllerAvailable: Bool {
        IOBluetoothHostController.default() != nil
    }

    /// Call on a thread with an active CFRunLoop, normally the application's main thread.
    public func start() throws {
        guard Self.controllerAvailable else { throw ReflectionError.invalid("No Bluetooth controller available") }
        var uuid = UUID(uuidString: Self.serviceUUID)!.uuid
        let uuidData = withUnsafeBytes(of: &uuid) { Data($0) }
        let dictionary: [String: Any] = [
            "0001 - ServiceClassIDList": [uuidData],
            "0004 - ProtocolDescriptorList": [
                [Data([0x01, 0x00])],
                [Data([0x00, 0x03]), ["DataElementType": 1, "DataElementSize": 1, "DataElementValue": 1]]
            ],
            "0005 - BrowseGroupList": [Data([0x10, 0x02])],
            "0100 - ServiceName": "Reflection Tablet",
            "LocalAttributes": ["Persistent": false]
        ]
        guard let record = IOBluetoothSDPServiceRecord.publishedServiceRecord(with: dictionary) else {
            throw ReflectionError.invalid("Bluetooth SDP publication failed; check Bluetooth permission and controller state")
        }
        self.record = record
        guard record.getRFCOMMChannelID(&channelID) == kIOReturnSuccess, channelID > 0 else {
            stop()
            throw ReflectionError.invalid("Bluetooth RFCOMM channel allocation failed")
        }
        notification = IOBluetoothRFCOMMChannel.register(
            forChannelOpenNotifications: self, selector: #selector(opened(_:channel:)),
            withChannelID: channelID, direction: kIOBluetoothUserNotificationChannelDirectionIncoming)
        guard notification != nil else {
            stop()
            throw ReflectionError.invalid("Bluetooth RFCOMM listener registration failed")
        }
        log("{\"event\":\"bluetooth_listening\",\"channel\":\(channelID),\"uuid\":\"\(Self.serviceUUID)\"}")
    }

    public func stop() {
        notification?.unregister()
        notification = nil
        session?.close()
        session = nil
        _ = record?.remove()
        record = nil
    }

    @objc private func opened(_ notification: IOBluetoothUserNotification, channel: IOBluetoothRFCOMMChannel) {
        guard session == nil else { channel.close(); return }
        let relay = BluetoothRelaySession(channel: channel, host: host, port: port) { [weak self] in
            DispatchQueue.main.async { self?.session = nil }
        }
        session = relay
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
