import Foundation
import CoreGraphics
import ReflectionCore

func touchpadChecks() throws -> Int {
    var count = 0
    func require(_ condition: Bool, _ name: String) throws {
        guard condition else { throw ReflectionError.invalid("Touchpad check: \(name)") }
        count += 1
    }
    func rejects(_ operation: () throws -> Void) throws {
        do { try operation() }
        catch { count += 1; return }
        throw ReflectionError.invalid("Touchpad check accepted an invalid frame")
    }
    let text = "000000000000000100000000000004d241480000c1a000000402000000000000"
    var data = Data()
    var index = text.startIndex
    while index < text.endIndex {
        let next = text.index(index, offsetBy: 2)
        data.append(UInt8(text[index..<next], radix: 16)!)
        index = next
    }
    let event = try TouchpadEvent.decode(data)
    try require(event.sequence == 1 && event.timestamp == 1234 && event.x == 12.5 &&
                event.y == -20 && event.kind == .scroll && event.fingers == 2, "wire vector")
    var guardValue = TouchpadSequenceGuard()
    try guardValue.accept(event)
    try rejects { try guardValue.accept(event) }
    var invalid = data; invalid[31] = 1
    try rejects { _ = try TouchpadEvent.decode(invalid) }
    invalid = data; invalid[16] = 0x7f; invalid[17] = 0xc0
    try rejects { _ = try TouchpadEvent.decode(invalid) }
    invalid = data; invalid[24] = 7
    try rejects { _ = try TouchpadEvent.decode(invalid) }
    try rejects { _ = try TouchpadEvent.decode(data.dropLast()) }
    guard let scroll = QuartzTouchpadBackend.scrollEvent(x: 12, y: -20, phase: 2) else {
        throw ReflectionError.invalid("Could not construct Quartz scroll event")
    }
    try require(scroll.type == .scrollWheel && scroll.getIntegerValueField(.scrollWheelEventIsContinuous) == 1
                && scroll.getIntegerValueField(.scrollWheelEventScrollPhase) == 2
                && scroll.getDoubleValueField(.scrollWheelEventPointDeltaAxis1) == -20, "native scroll fields")
    let backend = QuartzTouchpadBackend(dryRun: true)
    backend.submit(event); backend.release()
    try require(backend.events == 1, "dry-run backend records without injection")
    return count
}
