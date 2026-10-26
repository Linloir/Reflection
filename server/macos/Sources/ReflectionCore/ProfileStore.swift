import Foundation
import Darwin

/// Portable mapping presets deliberately exclude pairing credentials and listener settings.
public struct MappingProfile: Codable {
    public var version = 1
    public var mapping: MappingConfiguration
    public init(mapping: MappingConfiguration) { self.mapping = mapping }
}

public struct ProfileStore {
    public let directory: URL
    public init(configurationURL: URL) {
        directory = configurationURL.standardizedFileURL.resolvingSymlinksInPath()
            .deletingPathExtension().appendingPathExtension("profiles")
    }

    public func list() throws -> [String] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey])
            .filter { $0.pathExtension == "json" && ((try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true) }
            .map { $0.deletingPathExtension().lastPathComponent }
            .sorted()
    }

    public func save(name: String, mapping: MappingConfiguration, overwrite: Bool = false) throws {
        try mapping.validate()
        let destination = try url(name)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(MappingProfile(mapping: mapping))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        if overwrite {
            try writePrivateData(data, to: destination)
        } else {
            let temporary = directory.appendingPathComponent(".\(UUID().uuidString).tmp")
            defer { try? FileManager.default.removeItem(at: temporary) }
            try writePrivateData(data, to: temporary)
            // Hard-link publication fails atomically if another process created this name.
            guard link(temporary.path, destination.path) == 0 else {
                if errno == EEXIST { throw ReflectionError.invalid("Profile already exists; use --replace to overwrite") }
                throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
            }
        }
    }

    public func load(name: String) throws -> MappingConfiguration {
        let handle = try FileHandle(forReadingFrom: url(name))
        defer { try? handle.close() }
        let data = try handle.read(upToCount: 65537) ?? Data()
        guard data.count <= 65536 else { throw ReflectionError.invalid("Profile exceeds 64 KiB") }
        let profile = try JSONDecoder().decode(MappingProfile.self, from: data)
        guard profile.version == 1 else { throw ReflectionError.invalid("Unsupported profile version") }
        try profile.mapping.validate()
        return profile.mapping
    }

    public func delete(name: String) throws {
        try FileManager.default.removeItem(at: url(name))
    }

    private func url(_ name: String) throws -> URL {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: " _-"))
        guard !name.isEmpty, name.utf8.count <= 64,
              name == name.trimmingCharacters(in: .whitespacesAndNewlines),
              name.unicodeScalars.allSatisfy(allowed.contains) else {
            throw ReflectionError.invalid("Profile name must be 1..64 UTF-8 bytes of letters, numbers, spaces, _ or -")
        }
        return directory.appendingPathComponent(name).appendingPathExtension("json")
    }
}
