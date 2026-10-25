import Foundation
import CoreGraphics
import Security
import Darwin

public struct Region: Codable, Equatable {
    public var x: Double
    public var y: Double
    public var width: Double
    public var height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x; self.y = y; self.width = width; self.height = height
    }
}

public struct MappingConfiguration: Codable {
    public var mode = "absolute"
    public var source = Region(x: 0, y: 0, width: 1, height: 1)
    public var target: Region? = nil
    public var displayID: UInt32? = nil
    public var pressureGamma = 1.0
    public var relativeSensitivity = 1.0
    public var rotation = 0
    public var preserveAspect = false
    public var sourceAspect = 1.0

    public init() {}
}

public struct ServerConfiguration: Codable {
    public var version = 1
    public var bind = "127.0.0.1"
    public var port: UInt16 = 24819
    public var token: String
    public var mapping = MappingConfiguration()

    public init(token: String) { self.token = token }

    public func validate() throws {
        guard version == 1 else { throw ReflectionError.invalid("Unsupported config version") }
        guard !bind.isEmpty, port > 0 else { throw ReflectionError.invalid("bind and port are required") }
        guard token.count == 64, token.allSatisfy({ $0.isHexDigit && $0.isASCII }) else {
            throw ReflectionError.invalid("token must contain exactly 64 hexadecimal characters")
        }
        guard ["absolute", "relative"].contains(mapping.mode) else {
            throw ReflectionError.invalid("mapping.mode must be absolute or relative")
        }
        let s = mapping.source
        guard [s.x, s.y, s.width, s.height].allSatisfy(\.isFinite),
              s.x >= 0, s.y >= 0, s.width > 0, s.height > 0,
              s.x + s.width <= 1.000001, s.y + s.height <= 1.000001 else {
            throw ReflectionError.invalid("mapping.source must be a nonempty normalized rectangle")
        }
        if let t = mapping.target {
            guard [t.x, t.y, t.width, t.height].allSatisfy(\.isFinite),
                  t.width > 0, t.height > 0 else {
                throw ReflectionError.invalid("mapping.target must be a finite nonempty desktop rectangle")
            }
        }
        guard mapping.pressureGamma.isFinite, mapping.pressureGamma > 0,
              mapping.pressureGamma <= 10, mapping.relativeSensitivity.isFinite,
              mapping.relativeSensitivity > 0, mapping.relativeSensitivity <= 100,
              mapping.sourceAspect.isFinite, mapping.sourceAspect > 0,
              [0, 90, 180, 270].contains(mapping.rotation) else {
            throw ReflectionError.invalid("Invalid pressure, sensitivity, rotation, or aspect setting")
        }
    }

    public static func randomToken() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw ReflectionError.invalid("Could not obtain system randomness")
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    public static var defaultURL: URL {
        let base = ProcessInfo.processInfo.environment["XDG_CONFIG_HOME"]
            .map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".config")
        return base.appendingPathComponent("reflection/server.json")
    }

    public static func load(from url: URL) throws -> ServerConfiguration {
        let config = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        try config.validate()
        return config
    }

    public func save(to url: URL) throws {
        try validate()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try writePrivateData(encoder.encode(self), to: url)
    }
}

/// Set restrictive permissions before writing secrets, then atomically replace.
func writePrivateData(_ data: Data, to url: URL) throws {
    let temporary = url.deletingLastPathComponent()
        .appendingPathComponent(".reflection-\(UUID().uuidString).tmp")
    let descriptor = open(temporary.path, O_WRONLY | O_CREAT | O_EXCL, mode_t(0o600))
    guard descriptor >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
    defer { try? FileManager.default.removeItem(at: temporary) }
    let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
    try handle.write(contentsOf: data)
    try handle.close()
    guard rename(temporary.path, url.path) == 0 else {
        throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
    }
}

public enum ReflectionError: Error, CustomStringConvertible {
    case invalid(String)
    public var description: String {
        switch self { case .invalid(let message): return message }
    }
}

public struct DisplayDescription: Codable {
    public let id: UInt32
    public let main: Bool
    public let bounds: Region
    public let pixelWidth: Int
    public let pixelHeight: Int
}

public func availableDisplays() throws -> [DisplayDescription] {
    var count: UInt32 = 0
    guard CGGetActiveDisplayList(0, nil, &count) == .success else {
        throw ReflectionError.invalid("Cannot enumerate displays")
    }
    var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
    guard CGGetActiveDisplayList(count, &ids, &count) == .success else {
        throw ReflectionError.invalid("Cannot enumerate displays")
    }
    return ids.prefix(Int(count)).map {
        let b = CGDisplayBounds($0)
        return DisplayDescription(id: $0, main: $0 == CGMainDisplayID(),
            bounds: Region(x: b.origin.x, y: b.origin.y, width: b.width, height: b.height),
            pixelWidth: CGDisplayPixelsWide($0), pixelHeight: CGDisplayPixelsHigh($0))
    }
}
