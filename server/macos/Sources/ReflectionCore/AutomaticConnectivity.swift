import Foundation
import CryptoKit
import Darwin
import Security

public struct DiscoveredTablet: Codable, Identifiable {
    public let id: String
    public let name: String
    public let address: String
    public let code: String
    public var trusted: Bool
}

public struct USBTablet {
    public let id: String
    public let name: String
    public init(id: String, name: String) { self.id = id; self.name = name }
}

/// Discovery never transports credentials. Each reply proves the saved pairing key.
public final class HostDiscoveryService {
    public static let port: UInt16 = 24820
    private let configuration: ServerConfiguration
    private let queue = DispatchQueue(label: "reflection.discovery", qos: .utility)
    private var source: DispatchSourceRead?
    private var descriptor: Int32 = -1
    private struct Entry {
        var tablet: DiscoveredTablet
        let modulus: Data
        let exponent: Data
        var nonce: String
        var endpoint: sockaddr_in?
        var seen: TimeInterval
        var approval: Data?
    }
    private var devices: [String: Entry] = [:]
    private var usbDevices: [String: (tablet: USBTablet, seen: TimeInterval)] = [:]
    private var trusted = Set<String>()
    private let trustURL: URL?
    private var selected: String?
    public var onSelection: (() -> Void)?
    public var onUSBSelection: ((String, String) throws -> Void)?
    private var bluetoothPairingPort: UInt16 = 0

    public func setBluetoothPairingPort(_ port: UInt16) { queue.async { self.bluetoothPairingPort = port } }

    public init(configuration: ServerConfiguration, configurationURL: URL? = nil) {
        self.configuration = configuration
        trustURL = configurationURL?.deletingPathExtension().appendingPathExtension("trusted-tablets.json")
        if let trustURL, let data = try? Data(contentsOf: trustURL),
           let ids = try? JSONDecoder().decode([String].self, from: data) { trusted = Set(ids) }
    }
    public func start() throws {
        guard descriptor < 0 else { return }
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { throw POSIXError(.EIO) }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = Self.port.bigEndian
        guard inet_pton(AF_INET, configuration.bind, &address.sin_addr) == 1 else {
            close(fd); throw ReflectionError.invalid("Discovery requires a numeric IPv4 bind address")
        }
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard result == 0 else { close(fd); throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        _ = fcntl(fd, F_SETFL, O_NONBLOCK)
        descriptor = fd
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        source.setEventHandler { [weak self] in self?.receive(fd) }
        source.setCancelHandler { close(fd) }
        self.source = source
        source.resume()
    }
    public func stop() { source?.cancel(); source = nil; descriptor = -1 }
    deinit { stop() }

    private func receive(_ fd: Int32) {
        // Bound each event so an untrusted sender cannot monopolize the service queue.
        for _ in 0..<32 {
            var remote = sockaddr_in(), size = socklen_t(MemoryLayout<sockaddr_in>.size)
            var bytes = [UInt8](repeating: 0, count: 1200)
            let count = withUnsafeMutablePointer(to: &remote) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &bytes, bytes.count, 0, $0, &size) }
            }
            guard count > 0 else { return }
            let request = String(decoding: bytes.prefix(count), as: UTF8.self)
            if request.hasPrefix("REFLECTION-DEVICE/1\n") {
                if observe(request, remote: remote) {
                    let fields = request.components(separatedBy: "\n")
                    if let approval = devices[fields[1]]?.approval { sendApproval(approval, to: remote) }
                }
                continue
            }
            let fields = request.split(separator: " ").map(String.init)
            let credential: String
            if fields.count == 3, trusted.contains(fields[2]) { credential = Self.deviceToken(rootToken: configuration.token, deviceID: fields[2]) }
            else if fields.count == 2 { credential = configuration.token }
            else { continue }
            guard let reply = Self.reply(request: fields.prefix(2).joined(separator: " "), port: configuration.port,
                                         token: credential, hostToken: configuration.token) else { continue }
            reply.withUnsafeBytes { buffer in
                withUnsafePointer(to: &remote) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { _ = sendto(fd, buffer.baseAddress, buffer.count, 0, $0, size) }
                }
            }
        }
    }

    @discardableResult
    private func observe(_ request: String, remote: sockaddr_in?) -> Bool {
        let fields = request.components(separatedBy: "\n")
        guard fields.count == 6, fields[0] == "REFLECTION-DEVICE/1", fields[1].count == 32,
              let name = Data(base64Encoded: fields[2]).flatMap({ String(data: $0, encoding: .utf8) }),
              let modulus = Data(base64Encoded: fields[3]), modulus.count == 256,
              let exponent = Data(base64Encoded: fields[4]), exponent == Data([1, 0, 1]),
              fields[5].count == 32, fields[5].allSatisfy({ $0.isHexDigit && $0.isASCII }) else { return false }
        let digest = Array(SHA256.hash(data: modulus))
        let id = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
        guard fields[1] == id else { return false }
        let code = String(format: "%06d", (Int(digest[0]) << 16 | Int(digest[1]) << 8 | Int(digest[2])) % 1_000_000)
        let now = ProcessInfo.processInfo.systemUptime
        devices = devices.filter { now - $0.value.seen < 20 }
        guard devices.count < 32 || devices[id] != nil else { return false }
        var displayAddress = "Bluetooth"
        if let remote {
            var address = remote.sin_addr
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &address, &buffer, socklen_t(buffer.count)) != nil else { return false }
            displayAddress = String(cString: buffer)
        }
        let tablet = DiscoveredTablet(id: id, name: String(name.filter { !$0.isNewline && $0 != "\0" }.prefix(64)),
            address: displayAddress, code: code, trusted: trusted.contains(id))
        let previous = devices[id]
        devices[id] = Entry(tablet: tablet, modulus: modulus, exponent: exponent,
            nonce: fields[5], endpoint: remote, seen: now,
            approval: previous?.nonce == fields[5] ? previous?.approval : nil)
        return true
    }

    public func bluetoothReply(_ announcement: String) throws -> Data {
        try queue.sync {
            guard observe(announcement, remote: nil) else {
                throw ReflectionError.invalid("Invalid Bluetooth pairing announcement")
            }
            let fields = announcement.components(separatedBy: "\n")
            guard fields.count == 6, let entry = devices[fields[1]], entry.nonce == fields[5] else {
                throw ReflectionError.invalid("Invalid Bluetooth pairing announcement")
            }
            return entry.approval ?? Data("REFLECTION-PENDING/1".utf8)
        }
    }

    private func sendApproval(_ approval: Data, to remote: sockaddr_in) {
        guard descriptor >= 0 else { return }
        var endpoint = remote
        approval.withUnsafeBytes { buffer in
            withUnsafePointer(to: &endpoint) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    _ = sendto(descriptor, buffer.baseAddress, buffer.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    public func command(_ command: String) throws -> Data {
        let fields = command.split(separator: " ").map(String.init)
        if fields.count == 2, fields[0] == "trust", queue.sync(execute: {
            usbDevices[fields[1]].map { ProcessInfo.processInfo.systemUptime - $0.seen < 20 } ?? false
        }) {
            // USB polling calls back into discovery. Never hold this queue while waiting for ADB.
            guard let select = onUSBSelection else { throw ReflectionError.invalid("USB selection is unavailable") }
            let id = fields[1], token = Self.deviceToken(rootToken: configuration.token, deviceID: fields[1])
            try select(id, token)
            return try queue.sync {
                try rememberTrusted(id)
                if selected != id { selected = id; onSelection?() }
                devices[id]?.tablet.trusted = true
                return try JSONSerialization.data(withJSONObject: ["approvalSent": true, "usbSelected": true])
            }
        }
        return try queue.sync {
            if fields == ["endpoint"] {
                return try JSONSerialization.data(withJSONObject: ["loopbackPort": bluetoothPairingPort])
            }
            if fields == ["list"] {
                let now = ProcessInfo.processInfo.systemUptime
                var visible = devices.filter { now - $0.value.seen < 20 }.mapValues(\.tablet)
                for (id, entry) in usbDevices where now - entry.seen < 20 {
                    let name = visible[id]?.name ?? entry.tablet.name
                    visible[id] = DiscoveredTablet(id: id, name: name, address: "USB",
                        code: Self.tabletCode(id: id), trusted: trusted.contains(id))
                }
                return try JSONEncoder().encode(visible.values.sorted { $0.id < $1.id })
            }
            guard fields.count == 2, fields[0] == "trust", let entry = devices[fields[1]],
                  ProcessInfo.processInfo.systemUptime - entry.seen < 20 else {
                throw ReflectionError.invalid("Choose a tablet currently visible in discovery")
            }
            let encrypted = try Self.encryptToken(configuration: configuration, modulus: entry.modulus,
                exponent: entry.exponent, nonce: entry.nonce, credential: Self.deviceToken(rootToken: configuration.token, deviceID: entry.tablet.id))
            let bytes = Data("REFLECTION-PAIR/1\n\(entry.nonce)\n\(encrypted.base64EncodedString())".utf8)
            try rememberTrusted(entry.tablet.id)
            devices[entry.tablet.id]?.approval = bytes
            if selected != entry.tablet.id {
                selected = entry.tablet.id
                onSelection?()
            }
            devices[entry.tablet.id]?.tablet.trusted = true
            // Keep the offer for repeat discovery/polls if a datagram is dropped.
            if let endpoint = entry.endpoint { sendApproval(bytes, to: endpoint) }
            let credential = Self.deviceToken(rootToken: configuration.token, deviceID: entry.tablet.id)
            return try JSONSerialization.data(withJSONObject: ["approvalSent": true,
                "confirmationCode": Self.confirmationCode(token: credential, nonce: entry.nonce)])
        }
    }

    public var authenticationTokens: [String] {
        queue.sync {
            if let selected { return [Self.deviceToken(rootToken: configuration.token, deviceID: selected)] }
            return [configuration.token] + trusted.sorted().map { Self.deviceToken(rootToken: configuration.token, deviceID: $0) }
        }
    }

    private func rememberTrusted(_ id: String) throws {
        guard !trusted.contains(id) else { return }
        let updated = trusted.union([id])
        if let trustURL { try writePrivateData(JSONEncoder().encode(updated.sorted()), to: trustURL) }
        trusted = updated
    }

    public func updateUSBTablets(_ tablets: [USBTablet]) {
        queue.sync {
            let now = ProcessInfo.processInfo.systemUptime
            usbDevices.removeAll()
            for tablet in tablets where Self.validTabletID(tablet.id) {
                usbDevices[tablet.id] = (tablet, now)
            }
        }
    }

    public func authorizeUSB(_ id: String) throws -> String {
        try queue.sync {
            guard Self.validTabletID(id) else {
                throw ReflectionError.invalid("Invalid USB tablet identity")
            }
            try rememberTrusted(id)
            return Self.deviceToken(rootToken: configuration.token, deviceID: id)
        }
    }

    private static func validTabletID(_ id: String) -> Bool {
        id.count == 32 && id.allSatisfy { $0.isASCII && ($0.isNumber || ("a"..."f").contains(String($0))) }
    }
    private static func tabletCode(id: String) -> String {
        String(format: "%06d", (Int(id.prefix(6), radix: 16) ?? 0) % 1_000_000)
    }

    public static func deviceToken(rootToken: String, deviceID: String) -> String {
        guard let key = try? WireProtocol.tokenKey(rootToken) else { return "" }
        return WireProtocol.mac(key: key, data: Data(("Reflection/device/v1\n" + deviceID).utf8))
            .map { String(format: "%02x", $0) }.joined()
    }
    public static func confirmationCode(token: String, nonce: String) -> String {
        guard let key = try? WireProtocol.tokenKey(token) else { return "" }
        let digest = Array(WireProtocol.mac(key: key, data: Data(("Reflection/pair-confirm/v1\n" + nonce).utf8)))
        return String(format: "%06d", (Int(digest[0]) << 16 | Int(digest[1]) << 8 | Int(digest[2])) % 1_000_000)
    }

    public static func encryptToken(configuration: ServerConfiguration, modulus: Data, exponent: Data, nonce: String,
                                    credential: String? = nil) throws -> Data {
        func der(_ tag: UInt8, _ value: Data) -> Data {
            let length: [UInt8] = value.count < 128 ? [UInt8(value.count)] :
                value.count < 256 ? [0x81, UInt8(value.count)] : [0x82, UInt8(value.count >> 8), UInt8(value.count & 255)]
            return Data([tag] + length) + value
        }
        let integer = modulus.first.map { $0 & 0x80 != 0 } == true ? Data([0]) + modulus : modulus
        let encoded = der(0x30, der(0x02, integer) + der(0x02, exponent))
        let attributes: [CFString: Any] = [kSecAttrKeyType: kSecAttrKeyTypeRSA, kSecAttrKeyClass: kSecAttrKeyClassPublic,
                                         kSecAttrKeySizeInBits: 2048]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(encoded as CFData, attributes as CFDictionary, &error) else {
            throw ReflectionError.invalid("Invalid tablet public key")
        }
        let identity = SHA256.hash(data: Data(configuration.token.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
        let clear = Data("\(nonce)\n\(identity)\n\(configuration.port)\n\(credential ?? configuration.token)".utf8)
        guard let encrypted = SecKeyCreateEncryptedData(key, .rsaEncryptionOAEPSHA256, clear as CFData, &error) else {
            throw ReflectionError.invalid("Could not encrypt the pairing approval")
        }
        return encrypted as Data
    }

    public static func reply(request: String, port: UInt16, token: String, hostToken: String? = nil) -> Data? {
        let prefix = "REFLECTION-DISCOVER/1 "
        guard request.hasPrefix(prefix) else { return nil }
        let nonce = String(request.dropFirst(prefix.count))
        guard nonce.count == 32, nonce.allSatisfy({ $0.isHexDigit && $0.isASCII }),
              let key = try? WireProtocol.tokenKey(token) else { return nil }
        let identity = SHA256.hash(data: Data((hostToken ?? token).utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
        let message = "REFLECTION-HOST/1\n\(nonce)\n\(port)\n\(identity)"
        let signature = WireProtocol.mac(key: key, data: Data(message.utf8)).map { String(format: "%02x", $0) }.joined()
        return Data((message + "\n" + signature).utf8)
    }
}

/// Uses only USB devices already authorized by Android. No SDK installation is needed in packaged apps.
public final class USBProvisioningService {
    private let configuration: ServerConfiguration
    private let queue = DispatchQueue(label: "reflection.usb-provisioning", qos: .utility)
    private var timer: DispatchSourceTimer?
    private let adb: URL?
    private let log: (String) -> Void
    private let authorize: (String) throws -> String
    private let discovered: ([USBTablet]) -> Void
    private let commandExecutor: (([String]) -> String?)?
    private let deviceLock = NSLock()
    private var devices: [String: String] = [:]
    private static let receiver = "io.github.linloir.reflection/io.github.linloir.reflection.control.ControlReceiver"

    public init(configuration: ServerConfiguration, authorize: @escaping (String) throws -> String,
                discovered: @escaping ([USBTablet]) -> Void = { _ in },
                commandExecutor: (([String]) -> String?)? = nil,
                log: @escaping (String) -> Void = { _ in }) {
        self.configuration = configuration; self.log = log; self.authorize = authorize
        self.discovered = discovered; self.commandExecutor = commandExecutor
        let executable = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
        let environment = ProcessInfo.processInfo.environment
        var candidates = [
            executable.deletingLastPathComponent().appendingPathComponent("platform-tools/adb"),
            executable.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Resources/platform-tools/adb")
        ]
        if let configured = environment["REFLECTION_ADB"] { candidates.insert(URL(fileURLWithPath: configured), at: 0) }
        for directory in (environment["PATH"] ?? "").split(separator: ":") {
            candidates.append(URL(fileURLWithPath: String(directory)).appendingPathComponent("adb"))
        }
        candidates.append(FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Android/sdk/platform-tools/adb"))
        adb = candidates.first { FileManager.default.isExecutableFile(atPath: $0.path) }
    }
    public var available: Bool { adb != nil || commandExecutor != nil }
    public func start() {
        guard timer == nil else { return }
        guard available else { log("{\"event\":\"usb_unavailable\",\"reason\":\"bundled_adb_missing\"}"); return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: .seconds(5))
        timer.setEventHandler { [weak self] in self?.poll() }
        self.timer = timer; timer.resume()
    }
    public func stop() { timer?.cancel(); timer = nil }
    deinit { stop() }

    public func refresh() { queue.sync { poll() } }

    public func selectTablet(_ id: String, token: String) throws {
        deviceLock.lock()
        let serial = devices[id]
        deviceLock.unlock()
        guard let serial,
              let list = run(["devices", "-l"]),
              Self.authorizedDevices(list).contains(where: { $0.serial == serial }),
              readIdentity(serial) == id,
              run(["-s", serial, "reverse", "tcp:24819", "tcp:\(configuration.port)"]) != nil,
              provision(serial, token: token, select: true) else {
            throw ReflectionError.invalid("USB tablet is unavailable. Reconnect it, authorize USB debugging, and try again.")
        }
    }

    private static func authorizedDevices(_ list: String) -> [(serial: String, name: String)] {
        list.split(separator: "\n").compactMap { line in
            let fields = line.split(whereSeparator: \.isWhitespace).map(String.init)
            guard fields.count >= 2, fields[1] == "device", fields.contains(where: { $0.hasPrefix("usb:") }) else { return nil }
            let name = fields.first(where: { $0.hasPrefix("model:") }).map { String($0.dropFirst(6)).replacingOccurrences(of: "_", with: " ") }
            return (fields[0], name ?? "Android tablet")
        }
    }

    private func readIdentity(_ serial: String) -> String? {
        guard let result = run(["-s", serial, "shell", "am", "broadcast", "-n", Self.receiver,
                "--es", "command", "pairing-identity"]),
              Self.broadcastSucceeded(result),
              let range = result.range(of: "[a-f0-9]{32}", options: .regularExpression) else { return nil }
        return String(result[range])
    }

    private func provision(_ serial: String, token: String, select: Bool) -> Bool {
        guard let result = run(["-s", serial, "shell", "am", "broadcast", "-n", Self.receiver,
            "--es", "command", "provision-usb", "--es", "token", token,
            "--ez", "select", select ? "true" : "false"]) else { return false }
        return Self.broadcastSucceeded(result)
    }

    private static func broadcastSucceeded(_ result: String) -> Bool {
        result.range(of: #"(?m)^Broadcast completed: result=0(?:,|\r?$)"#, options: .regularExpression) != nil
    }

    private func poll() {
        guard let list = run(["devices", "-l"]) else { updateDevices([:], tablets: []); return }
        var currentDevices: [String: String] = [:]
        var tablets: [USBTablet] = []
        for device in Self.authorizedDevices(list) {
            let serial = device.serial
            guard run(["-s", serial, "shell", "pm", "path", "io.github.linloir.reflection"])?.contains("package:") == true else { continue }
            guard run(["-s", serial, "reverse", "tcp:24819", "tcp:\(configuration.port)"]) != nil else { continue }
            guard let id = readIdentity(serial), let token = try? authorize(id),
                  provision(serial, token: token, select: false) else { continue }
            currentDevices[id] = serial
            tablets.append(USBTablet(id: id, name: device.name))
        }
        updateDevices(currentDevices, tablets: tablets)
    }

    private func updateDevices(_ current: [String: String], tablets: [USBTablet]) {
        deviceLock.lock()
        devices = current
        deviceLock.unlock()
        discovered(tablets)
    }

    private func run(_ arguments: [String]) -> String? {
        if let commandExecutor { return commandExecutor(arguments) }
        guard let adb else { return nil }
        let process = Process(), output = Pipe()
        process.executableURL = adb; process.arguments = arguments
        process.standardOutput = output; process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
        let killTimeout = DispatchWorkItem { if process.isRunning { kill(process.processIdentifier, SIGKILL) } }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 4, execute: timeout)
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 5, execute: killTimeout)
        let bytes = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit(); timeout.cancel(); killTimeout.cancel()
        guard process.terminationStatus == 0 else { return nil }
        return String(decoding: bytes, as: UTF8.self)
    }
}
