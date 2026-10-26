import Foundation
import CoreGraphics

public enum PenPhase: UInt8, Codable {
    case hover = 0, down = 1, move = 2, up = 3, cancel = 4, leave = 5
}

public enum PenTool: UInt8, Codable {
    case pen = 1, eraser = 2
}

public struct PenSample: Codable {
    public let sequence: UInt64
    public let timestampNanoseconds: UInt64
    public let phase: PenPhase
    public let tool: PenTool
    public let buttons: UInt32
    public let x: Double
    public let y: Double
    public let pressure: Double
    public let tiltX: Double
    public let tiltY: Double
    public let rotation: Double
    public let distance: Double

    public init(sequence: UInt64, timestampNanoseconds: UInt64, phase: PenPhase,
                tool: PenTool, buttons: UInt32, x: Double, y: Double,
                pressure: Double, tiltX: Double, tiltY: Double, rotation: Double = 0, distance: Double = 0) {
        self.sequence = sequence; self.timestampNanoseconds = timestampNanoseconds
        self.phase = phase; self.tool = tool; self.buttons = buttons
        self.x = x; self.y = y; self.pressure = pressure
        self.tiltX = tiltX; self.tiltY = tiltY; self.rotation = rotation
        self.distance = distance
    }
}

public struct MappedSample {
    public let sample: PenSample
    public let point: CGPoint
    public let pressure: Double
    public let tiltX: Double
    public let tiltY: Double
}

/// Validate a complete authenticated session before any system input is emitted.
public struct PenSessionGuard {
    private var lastSequence: UInt64 = 0
    private var lastTimestamp: UInt64 = 0
    private var contact = false
    private var tool = PenTool.pen

    public init() {}

    public mutating func accept(_ sample: PenSample) throws {
        guard sample.sequence > lastSequence,
              lastSequence != 0 || sample.sequence == 1,
              sample.timestampNanoseconds >= lastTimestamp else {
            throw ReflectionError.invalid("Replayed, unordered, or invalid initial pen sequence")
        }
        if sample.phase == .cancel || sample.phase == .leave {
            contact = false
        } else {
            guard !contact || sample.tool == tool else {
                throw ReflectionError.invalid("Tool changed during contact")
            }
            switch sample.phase {
            case .down:
                guard !contact else { throw ReflectionError.invalid("Duplicate pen down") }
            case .move, .up:
                guard contact else { throw ReflectionError.invalid("Pen move or up without down") }
            case .hover:
                guard !contact else { throw ReflectionError.invalid("Hover without pen up") }
            case .cancel, .leave: break
            }
            contact = sample.phase == .down || sample.phase == .move
            tool = sample.tool
        }
        lastSequence = sample.sequence
        lastTimestamp = sample.timestampNanoseconds
    }
}

public final class CoordinateMapper {
    public let configuration: MappingConfiguration
    public let target: Region
    private var lastNormalized: CGPoint?
    private var cursor: CGPoint

    public init(configuration: MappingConfiguration, target: Region, initialCursor: CGPoint? = nil) {
        self.configuration = configuration
        self.target = target
        self.cursor = initialCursor ?? CGPoint(x: target.x + target.width / 2, y: target.y + target.height / 2)
    }

    public func reset() { lastNormalized = nil }

    public func map(_ sample: PenSample) -> MappedSample {
        let source = configuration.source
        var x = clamp((sample.x - source.x) / source.width)
        var y = clamp((sample.y - source.y) / source.height)
        var tiltX = sample.tiltX
        var tiltY = sample.tiltY
        switch configuration.rotation {
        case 90:
            (x, y) = (1 - y, x)
            (tiltX, tiltY) = (-tiltY, tiltX)
        case 180:
            (x, y) = (1 - x, 1 - y)
            (tiltX, tiltY) = (-tiltX, -tiltY)
        case 270:
            (x, y) = (y, 1 - x)
            (tiltX, tiltY) = (tiltY, -tiltX)
        default: break
        }
        var active = target
        if configuration.preserveAspect {
            var aspect = configuration.sourceAspect * source.width / source.height
            if configuration.rotation == 90 || configuration.rotation == 270 { aspect = 1 / aspect }
            if target.width / target.height > aspect {
                active.width = target.height * aspect
                active.x += (target.width - active.width) / 2
            } else {
                active.height = target.width / aspect
                active.y += (target.height - active.height) / 2
            }
        }
        let normalized = CGPoint(x: x, y: y)
        if configuration.mode == "relative" {
            if let previous = lastNormalized {
                cursor.x += (x - previous.x) * active.width * configuration.relativeSensitivity
                cursor.y += (y - previous.y) * active.height * configuration.relativeSensitivity
            }
            cursor.x = min(target.x + max(0, target.width - 1), max(target.x, cursor.x))
            cursor.y = min(target.y + max(0, target.height - 1), max(target.y, cursor.y))
        } else {
            cursor = CGPoint(x: active.x + x * max(0, active.width - 1),
                             y: active.y + y * max(0, active.height - 1))
        }
        lastNormalized = (sample.phase == .leave || sample.phase == .cancel) ? nil : normalized
        let pressure = sample.phase == .down || sample.phase == .move
            ? configuration.mapPressure(sample.pressure) : 0
        let output = CGPoint(
            x: quantize(cursor.x, origin: target.x, span: target.width - 1, steps: configuration.positionStepsX),
            y: quantize(cursor.y, origin: target.y, span: target.height - 1, steps: configuration.positionStepsY))
        tiltX = configuration.tiltEnabled ? max(-90, min(90, tiltX * configuration.tiltScale)) : 0
        tiltY = configuration.tiltEnabled ? max(-90, min(90, tiltY * configuration.tiltScale)) : 0
        return MappedSample(sample: sample, point: output, pressure: pressure, tiltX: tiltX, tiltY: tiltY)
    }
}

private func quantize(_ value: Double, origin: Double, span: Double, steps: Int) -> Double {
    guard steps >= 2, span > 0 else { return value }
    return origin + (((value - origin) / span) * Double(steps - 1)).rounded() / Double(steps - 1) * span
}

private func clamp(_ value: Double) -> Double { min(1, max(0, value)) }

public protocol TabletBackend: AnyObject {
    func submit(_ sample: MappedSample) throws
    func release()
}

public final class RecordingBackend: TabletBackend {
    public private(set) var count: UInt64 = 0
    public private(set) var lastSample: MappedSample?
    public private(set) var contactDown = false
    public private(set) var releaseCount: UInt64 = 0
    public init() {}
    public func submit(_ sample: MappedSample) {
        count += 1; lastSample = sample
        contactDown = sample.sample.phase == .down || sample.sample.phase == .move
    }
    public func release() { contactDown = false; releaseCount += 1 }
}
