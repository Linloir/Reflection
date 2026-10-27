import Foundation
import CoreGraphics
import ColorSync
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

public struct PressurePoint: Codable, Equatable {
    public var input: Double
    public var output: Double
    public init(input: Double, output: Double) { self.input = input; self.output = output }
}

public struct MappingConfiguration: Codable, Equatable {
    public var mode = "absolute"
    public var source = Region(x: 0, y: 0, width: 1, height: 1)
    public var target: Region? = nil
    public var displayID: UInt32? = nil
    public var displayUUID: String? = nil
    public var pressureGamma = 1.0
    public var pressureCurve: [PressurePoint] = []
    public var pressureInterpolation = "linear"
    public var tiltEnabled = true
    public var tiltScale = 1.0
    public var positionStepsX = 0
    public var positionStepsY = 0
    public var relativeSensitivity = 1.0
    public var rotation = 0
    public var preserveAspect = false
    public var sourceAspect = 1.0

    public init() {}

    private enum CodingKeys: String, CodingKey {
        case mode, source, target, displayID, displayUUID, pressureGamma, pressureCurve, pressureInterpolation
        case relativeSensitivity, rotation, preserveAspect, sourceAspect
        case tiltEnabled, tiltScale, positionStepsX, positionStepsY
    }

    public init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = try c.decodeIfPresent(String.self, forKey: .mode) ?? mode
        source = try c.decodeIfPresent(Region.self, forKey: .source) ?? source
        target = try c.decodeIfPresent(Region.self, forKey: .target)
        displayID = try c.decodeIfPresent(UInt32.self, forKey: .displayID)
        displayUUID = try c.decodeIfPresent(String.self, forKey: .displayUUID)
        pressureGamma = try c.decodeIfPresent(Double.self, forKey: .pressureGamma) ?? pressureGamma
        pressureCurve = try c.decodeIfPresent([PressurePoint].self, forKey: .pressureCurve) ?? pressureCurve
        pressureInterpolation = try c.decodeIfPresent(String.self, forKey: .pressureInterpolation) ?? pressureInterpolation
        tiltEnabled = try c.decodeIfPresent(Bool.self, forKey: .tiltEnabled) ?? tiltEnabled
        tiltScale = try c.decodeIfPresent(Double.self, forKey: .tiltScale) ?? tiltScale
        positionStepsX = try c.decodeIfPresent(Int.self, forKey: .positionStepsX) ?? positionStepsX
        positionStepsY = try c.decodeIfPresent(Int.self, forKey: .positionStepsY) ?? positionStepsY
        relativeSensitivity = try c.decodeIfPresent(Double.self, forKey: .relativeSensitivity) ?? relativeSensitivity
        rotation = try c.decodeIfPresent(Int.self, forKey: .rotation) ?? rotation
        preserveAspect = try c.decodeIfPresent(Bool.self, forKey: .preserveAspect) ?? preserveAspect
        sourceAspect = try c.decodeIfPresent(Double.self, forKey: .sourceAspect) ?? sourceAspect
    }

    public func mapPressure(_ value: Double) -> Double {
        let input = value.isFinite ? min(1, max(0, value)) : 0
        guard pressureCurve.count >= 2 else { return pow(input, pressureGamma) }
        for index in 1..<pressureCurve.count {
            let right = pressureCurve[index], left = pressureCurve[index - 1]
            if input <= right.input {
                let fraction = (input - left.input) / (right.input - left.input)
                if pressureInterpolation == "smooth" {
                    let h = right.input - left.input
                    let t2 = fraction * fraction, t3 = t2 * fraction
                    let result = (2 * t3 - 3 * t2 + 1) * left.output
                        + (t3 - 2 * t2 + fraction) * h * pressureSlope(index - 1)
                        + (-2 * t3 + 3 * t2) * right.output
                        + (t3 - t2) * h * pressureSlope(index)
                    return min(right.output, max(left.output, result))
                }
                return left.output + fraction * (right.output - left.output)
            }
        }
        return 1
    }

    /// Shape-preserving cubic Hermite slopes (PCHIP); flat segments never overshoot.
    private func pressureSlope(_ index: Int) -> Double {
        let count = pressureCurve.count
        func secant(_ left: Int) -> Double {
            let a = pressureCurve[left], b = pressureCurve[left + 1]
            return (b.output - a.output) / (b.input - a.input)
        }
        if count == 2 { return secant(0) }
        if index == 0 || index == count - 1 {
            let left = index == 0 ? 0 : count - 2
            let adjacent = index == 0 ? 1 : count - 3
            let h0 = pressureCurve[left + 1].input - pressureCurve[left].input
            let h1 = pressureCurve[adjacent + 1].input - pressureCurve[adjacent].input
            let d0 = secant(left), d1 = secant(adjacent)
            return max(0, min(3 * d0, ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)))
        }
        let d0 = secant(index - 1), d1 = secant(index)
        guard d0 > 0, d1 > 0 else { return 0 }
        let h0 = pressureCurve[index].input - pressureCurve[index - 1].input
        let h1 = pressureCurve[index + 1].input - pressureCurve[index].input
        let w1 = 2 * h1 + h0, w2 = h1 + 2 * h0
        return (w1 + w2) / (w1 / d0 + w2 / d1)
    }

    public func validate() throws {
        guard ["linear", "smooth"].contains(pressureInterpolation) else {
            throw ReflectionError.invalid("mapping.pressureInterpolation must be linear or smooth")
        }
        if let displayUUID {
            guard !displayUUID.isEmpty, displayUUID.utf8.count <= 128,
                  !displayUUID.contains(where: { $0.isNewline || $0 == "\0" }) else {
                throw ReflectionError.invalid("mapping.displayUUID must be a display identifier")
            }
        }
        guard ["absolute", "relative"].contains(mode) else {
            throw ReflectionError.invalid("mapping.mode must be absolute or relative")
        }
        let s = source
        guard [s.x, s.y, s.width, s.height].allSatisfy(\.isFinite),
              s.x >= 0, s.y >= 0, s.width > 0, s.height > 0,
              s.x + s.width <= 1.000001, s.y + s.height <= 1.000001 else {
            throw ReflectionError.invalid("mapping.source must be a nonempty normalized rectangle")
        }
        if let t = target {
            guard [t.x, t.y, t.width, t.height, t.x + t.width, t.y + t.height].allSatisfy(\.isFinite),
                  t.width > 0, t.height > 0 else {
                throw ReflectionError.invalid("mapping.target must be a finite nonempty desktop rectangle")
            }
        }
        guard pressureGamma.isFinite, pressureGamma > 0, pressureGamma <= 10,
              relativeSensitivity.isFinite, relativeSensitivity > 0, relativeSensitivity <= 100,
              sourceAspect.isFinite, sourceAspect > 0, [0, 90, 180, 270].contains(rotation),
              tiltScale.isFinite, (0...2).contains(tiltScale),
              [positionStepsX, positionStepsY].allSatisfy({ $0 == 0 || (2...65536).contains($0) }) else {
            throw ReflectionError.invalid("Invalid pressure, sensitivity, rotation, tilt, or coordinate steps")
        }
        if !pressureCurve.isEmpty {
            guard (2...32).contains(pressureCurve.count),
                  pressureCurve.first == PressurePoint(input: 0, output: 0),
                  pressureCurve.last == PressurePoint(input: 1, output: 1) else {
                throw ReflectionError.invalid("Pressure curve needs 2..32 points, from (0,0) to (1,1)")
            }
            for (index, point) in pressureCurve.enumerated() {
                guard point.input.isFinite, point.output.isFinite,
                      (0...1).contains(point.input), (0...1).contains(point.output),
                      index == 0 || (point.input > pressureCurve[index - 1].input
                        && point.output >= pressureCurve[index - 1].output) else {
                    throw ReflectionError.invalid("Pressure curve inputs must increase and outputs must not decrease")
                }
            }
        }
    }
}

public struct ServerConfiguration: Codable {
    public var version = 1
    public var bind = "0.0.0.0"
    public var port: UInt16 = 24819
    public var token: String
    public var mapping = MappingConfiguration()
    public var stabilization = StabilizationConfiguration()

    public init(token: String) { self.token = token }

    private enum CodingKeys: String, CodingKey { case version, bind, port, token, mapping, stabilization }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        token = try c.decode(String.self, forKey: .token)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? version
        bind = try c.decodeIfPresent(String.self, forKey: .bind) ?? bind
        port = try c.decodeIfPresent(UInt16.self, forKey: .port) ?? port
        mapping = try c.decodeIfPresent(MappingConfiguration.self, forKey: .mapping) ?? mapping
        stabilization = try c.decodeIfPresent(StabilizationConfiguration.self, forKey: .stabilization) ?? stabilization
    }

    public func validate() throws {
        guard version == 1 else { throw ReflectionError.invalid("Unsupported config version") }
        guard !bind.isEmpty, port > 0 else { throw ReflectionError.invalid("bind and port are required") }
        guard token.count == 64, token.allSatisfy({ $0.isHexDigit && $0.isASCII }) else {
            throw ReflectionError.invalid("token must contain exactly 64 hexadecimal characters")
        }
        try mapping.validate()
        try stabilization.validate()
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
        let destination = url.standardizedFileURL.resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try writePrivateData(encoder.encode(self), to: destination)
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
    public let stableID: String
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
        let stableID: String
        if let uuid = CGDisplayCreateUUIDFromDisplayID($0)?.takeRetainedValue() {
            stableID = CFUUIDCreateString(kCFAllocatorDefault, uuid) as String
        } else { stableID = String($0) }
        return DisplayDescription(id: $0, stableID: stableID, main: $0 == CGMainDisplayID(),
            bounds: Region(x: b.origin.x, y: b.origin.y, width: b.width, height: b.height),
            pixelWidth: CGDisplayPixelsWide($0), pixelHeight: CGDisplayPixelsHigh($0))
    }
}

public func mappingDisplays(_ displays: [DisplayDescription]) -> [MappingDisplay] {
    displays.map { MappingDisplay(id: $0.stableID, name: "Display \($0.id)", bounds: $0.bounds, primary: $0.main) }
}
