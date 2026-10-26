import Foundation
import CryptoKit
import Darwin

/// Same-user lifecycle control, independent of the input connection and its queue.
public final class HostControl {
    private let path: String
    private var lockDescriptor: Int32 = -1
    private var listener: Int32 = -1
    private var source: DispatchSourceRead?
    private let queue = DispatchQueue(label: "org.reflection.control", qos: .utility)
    private let onStop: () -> Void
    private let mappingCommand: ((String) throws -> Data)?
    private let calibrationCommand: ((String) throws -> Data)?
    private let statusCommand: (() throws -> Data)?

    public init(configurationURL: URL, mappingCommand: ((String) throws -> Data)? = nil,
                calibrationCommand: ((String) throws -> Data)? = nil,
                statusCommand: (() throws -> Data)? = nil,
                onStop: @escaping () -> Void) throws {
        self.onStop = onStop
        self.mappingCommand = mappingCommand
        self.calibrationCommand = calibrationCommand
        self.statusCommand = statusCommand
        path = try Self.socketPath(configurationURL, createDirectory: true)
        lockDescriptor = open(path + ".lock", O_RDWR | O_CREAT | O_NOFOLLOW, mode_t(0o600))
        guard lockDescriptor >= 0 else { throw Self.systemError() }
        var metadata = stat()
        guard fstat(lockDescriptor, &metadata) == 0,
              metadata.st_uid == getuid(), metadata.st_mode & S_IFMT == S_IFREG,
              flock(lockDescriptor, LOCK_EX | LOCK_NB) == 0 else {
            close(lockDescriptor); lockDescriptor = -1
            throw ReflectionError.invalid("A host already owns this configuration, or its control lock is inaccessible")
        }
        do {
            listener = socket(AF_UNIX, SOCK_STREAM, 0)
            guard listener >= 0 else { throw Self.systemError() }
            unlink(path)
            let bound = try Self.withAddress(path) { Darwin.bind(listener, $0, $1) }
            guard bound == 0, chmod(path, mode_t(0o600)) == 0,
                  listen(listener, 4) == 0 else { throw Self.systemError() }
            let source = DispatchSource.makeReadSource(fileDescriptor: listener, queue: queue)
            self.source = source
            source.setEventHandler { [weak self] in self?.receive() }
            let descriptor = listener
            source.setCancelHandler { close(descriptor) }
            source.resume()
        } catch {
            if listener >= 0 { close(listener); listener = -1 }
            unlink(path)
            close(lockDescriptor); lockDescriptor = -1
            throw error
        }
    }

    deinit {
        source?.cancel()
        if lockDescriptor >= 0 {
            unlink(path)
            close(lockDescriptor)
        }
    }

    public static func isRunning(configurationURL: URL) -> Bool {
        (try? request("ping", configurationURL: configurationURL)) == "running\n"
    }

    public static func requestStop(configurationURL: URL) throws {
        guard try request("stop", configurationURL: configurationURL) == "stopping\n" else {
            throw ReflectionError.invalid("Host rejected the stop request")
        }
    }

    public static func requestMapping(_ command: String, configurationURL: URL) throws -> Data {
        guard command.utf8.count <= 160, !command.contains(where: { $0.isNewline || $0 == "\0" }) else {
            throw ReflectionError.invalid("Invalid mapping command")
        }
        let response = try request("mapping " + command, configurationURL: configurationURL)
        let data = Data(response.utf8)
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let error = object["error"] as? String { throw ReflectionError.invalid(error) }
        _ = try JSONDecoder().decode(MappingStatus.self, from: data)
        return data
    }

    public static func requestCalibration(_ command: String, configurationURL: URL) throws -> Data {
        guard ["start", "finish", "cancel", "status"].contains(command) else {
            throw ReflectionError.invalid("Invalid calibration command")
        }
        let response = try request("calibration " + command, configurationURL: configurationURL)
        let data = Data(response.utf8)
        if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let error = object["error"] as? String { throw ReflectionError.invalid(error) }
        _ = try JSONDecoder().decode(CalibrationStatus.self, from: data)
        return data
    }

    public static func requestStatus(configurationURL: URL) throws -> Data {
        let data = Data(try request("status", configurationURL: configurationURL).utf8)
        _ = try JSONDecoder().decode(ServerStatus.self, from: data)
        return data
    }

    private func receive() {
        let client = accept(listener, nil, nil)
        guard client >= 0 else { return }
        defer { close(client) }
        var uid: uid_t = 0, gid: gid_t = 0
        guard getpeereid(client, &uid, &gid) == 0, uid == getuid() else { return }
        Self.configure(client)
        guard let command = try? Self.readLine(client, limit: 192) else { return }
        switch command {
        case "ping\n": try? Self.writeLine("running\n", client)
        case "stop\n":
            try? Self.writeLine("stopping\n", client)
            DispatchQueue.main.async(execute: onStop)
        case "status\n":
            if let data = try? statusCommand?(), data.count <= 8190 {
                try? Self.writeLine(String(decoding: data, as: UTF8.self) + "\n", client)
            } else { try? Self.writeLine("error\n", client) }
        default:
            let handler: ((String) throws -> Data)?
            let prefix: Int
            if command.hasPrefix("mapping ") { handler = mappingCommand; prefix = 8 }
            else if command.hasPrefix("calibration ") { handler = calibrationCommand; prefix = 12 }
            else { handler = nil; prefix = 0 }
            guard let handler else {
                try? Self.writeLine("error\n", client); return
            }
            do {
                let data = try handler(String(command.dropFirst(prefix).dropLast()))
                guard data.count <= 8190 else { throw ReflectionError.invalid("Mapping response is too large") }
                try Self.writeLine(String(decoding: data, as: UTF8.self) + "\n", client)
            } catch {
                if let data = try? JSONSerialization.data(withJSONObject: ["error": String(describing: error)]) {
                    try? Self.writeLine(String(decoding: data, as: UTF8.self) + "\n", client)
                }
            }
        }
    }

    private static func request(_ command: String, configurationURL: URL) throws -> String {
        let path = try socketPath(configurationURL, createDirectory: false)
        let descriptor = socket(AF_UNIX, SOCK_STREAM, 0)
        guard descriptor >= 0 else { throw systemError() }
        defer { close(descriptor) }
        configure(descriptor)
        guard try withAddress(path, { Darwin.connect(descriptor, $0, $1) }) == 0 else {
            throw ReflectionError.invalid("No controllable host is running for this configuration")
        }
        var uid: uid_t = 0, gid: gid_t = 0
        guard getpeereid(descriptor, &uid, &gid) == 0, uid == getuid() else {
            throw ReflectionError.invalid("Host control belongs to a different user")
        }
        try writeLine(command + "\n", descriptor)
        return try readLine(descriptor)
    }

    private static func configure(_ descriptor: Int32) {
        var timeout = timeval(tv_sec: 0, tv_usec: 500_000)
        setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout.size(ofValue: timeout)))
        setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout.size(ofValue: timeout)))
        var enabled: Int32 = 1
        setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &enabled, socklen_t(MemoryLayout.size(ofValue: enabled)))
    }

    private static func readLine(_ descriptor: Int32, limit: Int = 8192) throws -> String {
        var result = [UInt8]()
        // Bounded local messages; byte reads also handle fragmented stream delivery.
        while result.count < limit {
            var byte: UInt8 = 0
            let count = recv(descriptor, &byte, 1, 0)
            if count < 0 && errno == EINTR { continue }
            guard count == 1 else { throw ReflectionError.invalid("Host control response timed out or disconnected") }
            result.append(byte)
            if byte == 10 { return String(decoding: result, as: UTF8.self) }
        }
        throw ReflectionError.invalid("Host control message is too long")
    }

    private static func writeLine(_ value: String, _ descriptor: Int32) throws {
        try Array(value.utf8).withUnsafeBytes { buffer in
            var offset = 0
            while offset < buffer.count {
                let count = send(descriptor, buffer.baseAddress!.advanced(by: offset), buffer.count - offset, 0)
                if count < 0 && errno == EINTR { continue }
                guard count > 0 else { throw systemError() }
                offset += count
            }
        }
    }

    private static func socketPath(_ config: URL, createDirectory: Bool) throws -> String {
        let directory = "/tmp/reflection-\(getuid())"
        if createDirectory && mkdir(directory, mode_t(0o700)) != 0 && errno != EEXIST {
            throw systemError()
        }
        var metadata = stat()
        guard lstat(directory, &metadata) == 0,
              metadata.st_mode & S_IFMT == S_IFDIR, metadata.st_uid == getuid(),
              metadata.st_mode & 0o077 == 0 else {
            throw ReflectionError.invalid("Host control directory is unavailable or has unsafe permissions")
        }
        let canonical = config.standardizedFileURL.resolvingSymlinksInPath().path
        let hash = SHA256.hash(data: Data(canonical.utf8)).prefix(16)
            .map { String(format: "%02x", $0) }.joined()
        return "\(directory)/\(hash).sock"
    }

    private static func withAddress<T>(_ path: String,
        _ body: (UnsafePointer<sockaddr>, socklen_t) -> T) throws -> T {
        var address = sockaddr_un()
        let bytes = Array(path.utf8) + [0]
        guard bytes.count <= MemoryLayout.size(ofValue: address.sun_path) else {
            throw ReflectionError.invalid("Host control socket path is too long")
        }
        address.sun_family = sa_family_t(AF_UNIX)
        address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: bytes) }
        return withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                body($0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
    }

    private static func systemError() -> POSIXError {
        POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
    }
}
