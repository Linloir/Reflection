import Foundation
import AppKit
import ApplicationServices

/// A Quartz event backend. This does not register a physical or virtual HID device.
public final class QuartzTabletBackend: TabletBackend {
    private let source = CGEventSource(stateID: .hidSystemState)
    private var down = false
    private var rightDown = false
    private var middleDown = false
    private var proximity = false
    private var tool = PenTool.pen
    private var location = CGPoint.zero
    private var previousLocation: CGPoint?
    private let deviceID: Int64 = 0x5246
    private var lastMapped: MappedSample?
    private let emitTabletPointEvents: Bool

    public init(emitTabletPointEvents: Bool = false) throws {
        self.emitTabletPointEvents = emitTabletPointEvents
        guard Self.accessibilityTrusted else {
            throw ReflectionError.invalid("Accessibility permission is required for input injection. Enable the terminal or reflection in System Settings > Privacy & Security > Accessibility, then restart.")
        }
    }

    public static var accessibilityTrusted: Bool { AXIsProcessTrusted() }

    public func submit(_ mapped: MappedSample) {
        let sample = mapped.sample
        location = mapped.point
        lastMapped = mapped
        if sample.phase == .cancel || sample.phase == .leave {
            release()
            return
        }
        if !proximity || sample.tool != tool {
            if proximity { release() }
            tool = sample.tool
            postProximity(enter: true)
            proximity = true
        }
        let nextDown = sample.phase == .down || sample.phase == .move
        let nextRight = sample.buttons & 1 != 0
        let nextMiddle = sample.buttons & 2 != 0
        if rightDown != nextRight {
            postMouse(nextRight ? .rightMouseDown : .rightMouseUp, mapped, button: .right)
            rightDown = nextRight
        }
        if middleDown != nextMiddle {
            postMouse(nextMiddle ? .otherMouseDown : .otherMouseUp, mapped, button: .center)
            middleDown = nextMiddle
        }
        let type: CGEventType
        if nextDown && !down { type = .leftMouseDown }
        else if !nextDown && down { type = .leftMouseUp }
        else if nextDown { type = .leftMouseDragged }
        else if middleDown { type = .otherMouseDragged }
        else if rightDown { type = .rightMouseDragged }
        else { type = .mouseMoved }
        postMouse(type, mapped, button: nextDown ? .left : (middleDown ? .center : (rightDown ? .right : .left)),
                  previousLocation: previousLocation)
        previousLocation = mapped.point
        if emitTabletPointEvents {
            QuartzEventFactory.point(mapped, type: .tabletPointer)?.post(tap: .cghidEventTap)
        }
        down = nextDown
    }

    public func release() {
        if down {
            let event = CGEvent(mouseEventSource: source, mouseType: .leftMouseUp,
                                mouseCursorPosition: location, mouseButton: .left)
            event?.setDoubleValueField(.mouseEventPressure, value: 0)
            event?.setDoubleValueField(.tabletEventPointPressure, value: 0)
            event?.setIntegerValueField(.mouseEventSubtype, value: 1)
            event?.setIntegerValueField(.tabletEventDeviceID, value: deviceID)
            event?.post(tap: .cghidEventTap)
        }
        if rightDown {
            CGEvent(mouseEventSource: source, mouseType: .rightMouseUp,
                    mouseCursorPosition: location, mouseButton: .right)?.post(tap: .cghidEventTap)
        }
        if middleDown {
            CGEvent(mouseEventSource: source, mouseType: .otherMouseUp,
                    mouseCursorPosition: location, mouseButton: .center)?.post(tap: .cghidEventTap)
        }
        if emitTabletPointEvents, down, let lastMapped {
            let old = lastMapped.sample
            let released = PenSample(sequence: old.sequence, timestampNanoseconds: old.timestampNanoseconds,
                phase: .up, tool: old.tool, buttons: 0, x: old.x, y: old.y,
                pressure: 0, tiltX: old.tiltX, tiltY: old.tiltY)
            QuartzEventFactory.point(MappedSample(sample: released, point: location, pressure: 0,
                                                  tiltX: lastMapped.tiltX, tiltY: lastMapped.tiltY),
                                     type: .tabletPointer)?.post(tap: .cghidEventTap)
        }
        down = false; rightDown = false; middleDown = false
        if proximity { postProximity(enter: false) }
        proximity = false
        previousLocation = nil
    }

    private func postMouse(_ type: CGEventType, _ mapped: MappedSample, button: CGMouseButton,
                           previousLocation: CGPoint? = nil) {
        QuartzEventFactory.point(mapped, type: type, button: button,
                                 previousLocation: previousLocation)?.post(tap: .cghidEventTap)
    }

    private func postProximity(enter: Bool) {
        QuartzEventFactory.proximity(tool: tool, enter: enter, location: location)?.post(tap: .cghidEventTap)
    }

    deinit { release() }
}

/// Event construction is side-effect free and can be checked without permission.
public enum QuartzEventFactory {
    private static let deviceID: Int64 = 0x5246

    public static func point(_ mapped: MappedSample, type: CGEventType,
                             button: CGMouseButton = .left,
                             previousLocation: CGPoint? = nil) -> CGEvent? {
        let candidate: CGEvent?
        if type == .tabletPointer {
            candidate = CGEvent(source: nil)
            candidate?.type = .tabletPointer
            candidate?.location = mapped.point
        } else {
            candidate = CGEvent(mouseEventSource: nil, mouseType: type,
                                mouseCursorPosition: mapped.point, mouseButton: button)
        }
        guard let event = candidate else { return nil }
        if let previousLocation,
           [.mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged].contains(type) {
            // Drawing gesture recognizers use movement deltas as well as absolute location.
            event.setIntegerValueField(.mouseEventDeltaX,
                value: motionDelta(from: previousLocation.x, to: mapped.point.x))
            event.setIntegerValueField(.mouseEventDeltaY,
                value: motionDelta(from: previousLocation.y, to: mapped.point.y))
        }
        if type != .tabletPointer { event.setIntegerValueField(.mouseEventSubtype, value: 1) }
        event.setIntegerValueField(.mouseEventClickState,
                                   value: type == .leftMouseDown || type == .leftMouseUp ? 1 : 0)
        event.setIntegerValueField(.tabletEventDeviceID, value: deviceID)
        event.setIntegerValueField(.tabletEventPointX, value: Int64(mapped.sample.x * 65535))
        event.setIntegerValueField(.tabletEventPointY, value: Int64(mapped.sample.y * 65535))
        event.setIntegerValueField(.tabletEventPointButtons,
                                   value: (Int64(mapped.sample.buttons) << 1)
                                    | (mapped.sample.phase == .down || mapped.sample.phase == .move ? 1 : 0))
        event.setDoubleValueField(.mouseEventPressure, value: mapped.pressure)
        event.setDoubleValueField(.tabletEventPointPressure, value: mapped.pressure)
        event.setDoubleValueField(.tabletEventTiltX, value: mapped.tiltX / 90)
        event.setDoubleValueField(.tabletEventTiltY, value: mapped.tiltY / 90)
        event.setDoubleValueField(.tabletEventRotation, value: mapped.sample.rotation)
        return event
    }

    private static func motionDelta(from previous: CGFloat, to current: CGFloat) -> Int64 {
        // Quantize positions, not each step, so repeated subpixel moves still accumulate.
        let delta = current.rounded() - previous.rounded()
        guard delta.isFinite else { return 0 }
        return Int64(min(CGFloat(Int16.max), max(CGFloat(Int16.min), delta)))
    }

    public static func proximity(tool: PenTool, enter: Bool, location: CGPoint) -> CGEvent? {
        guard let event = CGEvent(source: nil) else { return nil }
        event.type = .tabletProximity
        event.location = location
        event.setIntegerValueField(.tabletProximityEventDeviceID, value: deviceID)
        event.setIntegerValueField(.tabletProximityEventTabletID, value: 1)
        event.setIntegerValueField(.tabletProximityEventSystemTabletID, value: 1)
        event.setIntegerValueField(.tabletProximityEventPointerID, value: 1)
        event.setIntegerValueField(.tabletProximityEventPointerType,
                                   value: Int64(tool == .pen ? NSEvent.PointingDeviceType.pen.rawValue : NSEvent.PointingDeviceType.eraser.rawValue))
        event.setIntegerValueField(.tabletProximityEventEnterProximity, value: enter ? 1 : 0)
        return event
    }
}
