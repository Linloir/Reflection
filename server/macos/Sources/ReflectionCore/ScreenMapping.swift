import Foundation

/// The wire representation uses desktop coordinates, including negative origins.
public struct MappingDisplay: Codable, Equatable {
    public let id: String
    public let name: String
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double
    public let primary: Bool
    public var region: Region { Region(x: x, y: y, width: width, height: height) }

    public init(id: String, name: String, bounds: Region, primary: Bool) {
        self.id = id; self.name = name; x = bounds.x; y = bounds.y
        width = bounds.width; height = bounds.height; self.primary = primary
    }
}

public struct MappingStatus: Codable, Equatable {
    public var revision: UInt64
    public var mode: String
    public var selected: String
    public var target: Region
    public var displays: [MappingDisplay]
    public var pending: Bool
    public var fallback: Bool
}

public struct MappingRequest {
    public let sequence: UInt64
    public let action: UInt8
    public let displayID: String

    public static func supportsControl(in payload: Data) -> Bool {
        // Codable's Int decoder rejects booleans and fractional numbers instead of
        // applying NSNumber.intValue's lossy conversion (true and 1.5 become 1).
        struct Capabilities: Decodable { let mappingControl: Int? }
        return (try? JSONDecoder().decode(Capabilities.self, from: payload))?.mappingControl == 1
    }

    public static func decode(_ payload: Data, after previous: UInt64) throws -> MappingRequest {
        let bytes = [UInt8](payload)
        guard (9...137).contains(bytes.count) else { throw ReflectionError.invalid("Invalid mapping request length") }
        let sequence = bytes.prefix(8).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
        let action = bytes[8]
        guard sequence > previous, action <= 4 else { throw ReflectionError.invalid("Invalid or replayed mapping request") }
        let identifier = String(bytes: bytes.dropFirst(9), encoding: .utf8)
        guard let identifier, !identifier.contains(where: { $0.isNewline || $0 == "\0" }),
              action == 4 ? !identifier.isEmpty : bytes.count == 9 else {
            throw ReflectionError.invalid("Invalid mapping display identifier")
        }
        return MappingRequest(sequence: sequence, action: action, displayID: identifier)
    }
}

/// Session-queue confined state. No system input, file I/O, or UI dependencies.
/// A pending request changes the target only after the current stroke has ended.
public struct ScreenMappingState {
    private struct Choice: Equatable {
        var mode: String
        var selected: String
        var target: Region
        var fallback: Bool = false
    }
    public private(set) var revision: UInt64 = 1
    public private(set) var displays: [MappingDisplay]
    private var activeDisplays: [MappingDisplay]
    private var active: Choice
    private var desired: Choice
    // Keep the requested stable identity even while the resolved target falls back.
    private var preferredDisplay: String?
    private var contact = false

    public init(displays: [MappingDisplay], selected: String? = nil, custom: Region? = nil) throws {
        self.displays = try Self.validated(displays)
        activeDisplays = self.displays
        preferredDisplay = custom == nil ? selected : nil
        if let custom {
            active = Choice(mode: "custom", selected: "", target: custom)
        } else if let selected, let display = self.displays.first(where: { $0.id == selected }) {
            active = Choice(mode: "display", selected: display.id, target: display.region)
        } else {
            active = Choice(mode: "all", selected: "", target: Self.union(self.displays), fallback: selected != nil)
        }
        desired = active
    }

    public var target: Region { active.target }
    public var status: MappingStatus {
        MappingStatus(revision: revision, mode: active.mode, selected: active.selected,
                      target: active.target, displays: activeDisplays,
                      pending: active != desired || activeDisplays != displays, fallback: active.fallback)
    }

    public mutating func request(_ action: UInt8, displayID: String = "") throws {
        switch action {
        case 0: return
        case 1, 2:
            let current = displays.firstIndex(where: { $0.id == desired.selected })
            let index: Int
            if let current { index = (current + (action == 1 ? 1 : displays.count - 1)) % displays.count }
            else { index = action == 1 ? 0 : displays.count - 1 }
            let display = displays[index]
            desired = Choice(mode: "display", selected: display.id, target: display.region)
            preferredDisplay = display.id
        case 3:
            desired = Choice(mode: "all", selected: "", target: Self.union(displays))
            preferredDisplay = nil
        case 4:
            guard let display = displays.first(where: { $0.id == displayID }) else {
                throw ReflectionError.invalid("The selected display is no longer connected")
            }
            desired = Choice(mode: "display", selected: display.id, target: display.region)
            preferredDisplay = display.id
        default: throw ReflectionError.invalid("Unknown mapping action")
        }
        if !contact { active = desired; activeDisplays = displays }
        revision += 1
    }

    public mutating func updateDisplays(_ value: [MappingDisplay]) throws {
        let value = try Self.validated(value)
        guard value != displays else { return }
        displays = value
        if let preferredDisplay {
            if let display = displays.first(where: { $0.id == preferredDisplay }) {
                desired = Choice(mode: "display", selected: display.id, target: display.region)
            }
            else { desired = Choice(mode: "all", selected: "", target: Self.union(displays), fallback: true) }
        } else if desired.mode == "all" { desired.target = Self.union(displays) }
        if !contact { active = desired; activeDisplays = displays }
        revision += 1
    }

    /// Call after delivering the sample with the previous target, including UP.
    public mutating func didSubmit(_ phase: PenPhase) {
        contact = phase == .down || phase == .move
        if !contact && (active != desired || activeDisplays != displays) {
            active = desired; activeDisplays = displays; revision += 1
        }
    }

    public mutating func release() {
        contact = false
        if active != desired || activeDisplays != displays {
            active = desired; activeDisplays = displays; revision += 1
        }
    }

    private static func validated(_ values: [MappingDisplay]) throws -> [MappingDisplay] {
        guard !values.isEmpty, values.count <= 16, Set(values.map(\.id)).count == values.count,
              values.allSatisfy({ value in
                  !value.id.isEmpty && value.id.utf8.count <= 128 &&
                  !value.id.contains(where: { $0.isNewline || $0 == "\0" }) &&
                  [value.x, value.y, value.width, value.height, value.x + value.width, value.y + value.height].allSatisfy(\.isFinite) &&
                  value.width > 0 && value.height > 0
              }) else { throw ReflectionError.invalid("No valid display layout is available (up to 16 screens supported)") }
        return values.sorted { lhs, rhs in
            if lhs.x != rhs.x { return lhs.x < rhs.x }
            if lhs.y != rhs.y { return lhs.y < rhs.y }
            return lhs.id < rhs.id
        }
    }

    private static func union(_ values: [MappingDisplay]) -> Region {
        let x = values.map(\.x).min()!, y = values.map(\.y).min()!
        return Region(x: x, y: y, width: values.map { $0.x + $0.width }.max()! - x,
                      height: values.map { $0.y + $0.height }.max()! - y)
    }
}
