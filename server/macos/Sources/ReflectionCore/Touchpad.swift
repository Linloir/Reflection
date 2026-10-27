import Foundation
import CoreGraphics

public struct TouchpadEvent {
    public enum Kind: UInt8 { case move, leftTap, rightTap, scrollBegin, scroll, scrollEnd, zoom, swipe, cancel }
    public let sequence: UInt64
    public let timestamp: UInt64
    public let x: Double
    public let y: Double
    public let kind: Kind
    public let fingers: UInt8

    public static func decode(_ payload: Data) throws -> TouchpadEvent {
        let b = [UInt8](payload)
        guard b.count == 32, let kind = Kind(rawValue: b[24]), b[25] <= 4,
              b[26...31].allSatisfy({ $0 == 0 }) else { throw ReflectionError.invalid("Invalid touchpad payload") }
        let x = Double(Float(bitPattern: WireProtocol.uint32(b, 16)))
        let y = Double(Float(bitPattern: WireProtocol.uint32(b, 20)))
        guard x.isFinite, y.isFinite, abs(x) <= 1024, abs(y) <= 1024 else {
            throw ReflectionError.invalid("Invalid touchpad delta")
        }
        let fingers = b[25]
        switch kind {
        case .move: guard fingers == 1 else { throw ReflectionError.invalid("Invalid move") }
        case .leftTap: guard fingers == 1, x == 0, y == 0 else { throw ReflectionError.invalid("Invalid tap") }
        case .rightTap, .scrollBegin, .scrollEnd:
            guard fingers == 2, x == 0, y == 0 else { throw ReflectionError.invalid("Invalid scroll transition") }
        case .scroll: guard fingers == 2 else { throw ReflectionError.invalid("Invalid scroll") }
        case .zoom: guard fingers == 2, abs(x) <= 1, y == 0 else { throw ReflectionError.invalid("Invalid zoom") }
        case .swipe:
            guard fingers >= 3, abs(x) + abs(y) == 1, x == x.rounded(), y == y.rounded() else {
                throw ReflectionError.invalid("Invalid swipe")
            }
        case .cancel: guard fingers == 0, x == 0, y == 0 else { throw ReflectionError.invalid("Invalid cancel") }
        }
        return TouchpadEvent(sequence: WireProtocol.uint64(b, 0), timestamp: WireProtocol.uint64(b, 8),
                             x: x, y: y, kind: kind, fingers: fingers)
    }
}

public struct TouchpadSequenceGuard {
    private var sequence: UInt64 = 0
    private var timestamp: UInt64 = 0
    public init() {}
    public mutating func accept(_ event: TouchpadEvent) throws {
        guard event.sequence > sequence, sequence != 0 || event.sequence == 1, event.timestamp >= timestamp else {
            throw ReflectionError.invalid("Replayed or unordered touchpad event")
        }
        sequence = event.sequence; timestamp = event.timestamp
    }
}

/// Uses public Quartz mouse/scroll events and system shortcuts; never holds a key across frames.
public final class QuartzTouchpadBackend {
    private let dryRun: Bool
    private var scrolling = false
    private var zoom = 0.0
    public private(set) var events: UInt64 = 0
    public init(dryRun: Bool) { self.dryRun = dryRun }

    public func submit(_ event: TouchpadEvent) {
        events += 1
        switch event.kind {
        case .move:
            guard !dryRun else { return }
            let current = CGEvent(source: nil)?.location ?? .zero
            let proposed = CGPoint(x: current.x + event.x * 1.5, y: current.y + event.y * 1.5)
            let next = Self.visiblePosition(proposed)
            let move = CGEvent(mouseEventSource: nil, mouseType: .mouseMoved,
                               mouseCursorPosition: next, mouseButton: .left)
            move?.setIntegerValueField(.mouseEventDeltaX, value: Int64((next.x - current.x).rounded()))
            move?.setIntegerValueField(.mouseEventDeltaY, value: Int64((next.y - current.y).rounded()))
            move?.post(tap: .cghidEventTap)
        case .leftTap, .rightTap:
            guard !dryRun else { return }
            let right = event.kind == .rightTap
            let location = CGEvent(source: nil)?.location ?? .zero
            for type: CGEventType in right ? [.rightMouseDown, .rightMouseUp] : [.leftMouseDown, .leftMouseUp] {
                let click = CGEvent(mouseEventSource: nil, mouseType: type,
                                    mouseCursorPosition: location, mouseButton: right ? .right : .left)
                click?.setIntegerValueField(.mouseEventClickState, value: 1)
                click?.post(tap: .cghidEventTap)
            }
        case .scrollBegin:
            release(); scrolling = true; postScroll(x: 0, y: 0, phase: 1)
        case .scroll:
            if !scrolling { scrolling = true; postScroll(x: 0, y: 0, phase: 1) }
            postScroll(x: event.x, y: event.y, phase: 2)
        case .scrollEnd: release()
        case .zoom:
            zoom += event.x
            while abs(zoom) >= 0.12 {
                shortcut(key: zoom > 0 ? 24 : 27, flags: .maskCommand)
                zoom += zoom > 0 ? -0.12 : 0.12
            }
        case .swipe:
            release()
            if event.x != 0 {
                // Control-arrow follows macOS's desktop navigation shortcuts.
                shortcut(key: event.x > 0 ? 123 : 124, flags: .maskControl)
            } else {
                // Up exposes Mission Control; down exposes the current application's windows.
                shortcut(key: event.y < 0 ? 126 : 125, flags: .maskControl)
            }
        case .cancel: release()
        }
    }

    public func release() {
        if scrolling { postScroll(x: 0, y: 0, phase: 4) }
        scrolling = false; zoom = 0
    }
    private func postScroll(x: Double, y: Double, phase: Int64) {
        if !dryRun { Self.scrollEvent(x: x, y: y, phase: phase)?.post(tap: .cghidEventTap) }
    }
    public static func scrollEvent(x: Double, y: Double, phase: Int64) -> CGEvent? {
        let event = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                            wheel1: Int32(y.rounded()), wheel2: Int32(x.rounded()), wheel3: 0)
        event?.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        event?.setIntegerValueField(.scrollWheelEventScrollPhase, value: phase)
        event?.setDoubleValueField(.scrollWheelEventPointDeltaAxis1, value: y)
        event?.setDoubleValueField(.scrollWheelEventPointDeltaAxis2, value: x)
        return event
    }
    private func shortcut(key: CGKeyCode, flags: CGEventFlags) {
        guard !dryRun else { return }
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: down)
            event?.flags = flags
            event?.post(tap: .cghidEventTap)
        }
    }
    private static func visiblePosition(_ point: CGPoint) -> CGPoint {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else { return point }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else { return point }
        var closest = point, distance = Double.infinity
        for id in ids.prefix(Int(count)) {
            let b = CGDisplayBounds(id)
            let candidate = CGPoint(x: min(b.maxX - 1, max(b.minX, point.x)),
                                    y: min(b.maxY - 1, max(b.minY, point.y)))
            let d = hypot(candidate.x - point.x, candidate.y - point.y)
            if d < distance { distance = d; closest = candidate }
        }
        return closest
    }
}
