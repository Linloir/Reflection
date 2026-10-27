import Foundation
import Network

/// A separate bounded bootstrap service keeps discovery independent of the active input session.
public final class BluetoothPairingServer {
    public static let serviceUUID = "7b011e5a-8b31-4c40-8d7a-3e6c6dcbb759"
    private let discovery: HostDiscoveryService
    private let queue = DispatchQueue(label: "reflection.bluetooth-pairing", qos: .utility)
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: NWConnection] = [:]

    public init(discovery: HostDiscoveryService) { self.discovery = discovery }

    public func start(ready: @escaping (UInt16) -> Void) throws {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener, self.listener === listener else { return }
            if case .ready = state, let port = listener.port {
                self.discovery.setBluetoothPairingPort(port.rawValue)
                ready(port.rawValue)
            }
        }
        listener.newConnectionHandler = { [weak self] in self?.accept($0) }
        listener.start(queue: queue)
    }

    public func stop() {
        queue.async {
            self.listener?.cancel(); self.listener = nil
            for connection in self.connections.values { connection.cancel() }
            self.connections.removeAll()
        }
    }

    private func accept(_ connection: NWConnection) {
        guard connections.count < 4 else { connection.cancel(); return }
        let identity = ObjectIdentifier(connection)
        connections[identity] = connection
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready: self.receive(connection, buffer: Data())
            case .failed, .cancelled: self.close(connection)
            default: break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 3) { [weak self, weak connection] in
            guard let self, let connection, self.connections[identity] === connection else { return }
            self.close(connection)
        }
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1205) { [weak self, weak connection] bytes, _, complete, error in
            guard let self, let connection, self.connections[ObjectIdentifier(connection)] === connection else { return }
            var data = buffer
            if let bytes { data.append(bytes) }
            guard data.count <= 1204, error == nil else { self.close(connection); return }
            if data.count >= 4 {
                let length = data.prefix(4).reduce(0) { $0 * 256 + Int($1) }
                guard (2...1200).contains(length), data.count <= length + 4 else { self.close(connection); return }
                if data.count == length + 4 {
                    guard data[4] == 1, data[5] == 0x04,
                          let request = String(data: data.dropFirst(6), encoding: .utf8),
                          let reply = try? self.discovery.bluetoothReply(request) else {
                        self.close(connection); return
                    }
                    let response = WireProtocol.frame(type: 0x05, payload: reply, key: nil)
                    connection.send(content: response, completion: .contentProcessed { [weak self, weak connection] _ in
                        guard let self, let connection else { return }
                        self.close(connection)
                    })
                    return
                }
            }
            if complete { self.close(connection) }
            else { self.receive(connection, buffer: data) }
        }
    }

    private func close(_ connection: NWConnection) {
        connections.removeValue(forKey: ObjectIdentifier(connection))
        connection.stateUpdateHandler = nil
        connection.cancel()
    }
}
