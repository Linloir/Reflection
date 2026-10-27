import XCTest
import CoreGraphics
@testable import ReflectionCore

final class TouchpadTests: XCTestCase {
    private func payload(sequence: UInt64 = 1, timestamp: UInt64 = 1234, kind: UInt8 = 4,
                         fingers: UInt8 = 2, x: Float = 12.5, y: Float = -20) -> Data {
        var data = Data()
        for value in [sequence, timestamp] {
            var big = value.bigEndian
            withUnsafeBytes(of: &big) { data.append(contentsOf: $0) }
        }
        for value in [x, y] {
            var big = value.bitPattern.bigEndian
            withUnsafeBytes(of: &big) { data.append(contentsOf: $0) }
        }
        data.append(contentsOf: [kind, fingers, 0, 0, 0, 0, 0, 0])
        return data
    }
    func testCrossPlatformPayloadAndReplay() throws {
        let event = try TouchpadEvent.decode(payload())
        XCTAssertEqual(event.sequence, 1)
        XCTAssertEqual(event.timestamp, 1234)
        XCTAssertEqual(event.x, 12.5)
        XCTAssertEqual(event.y, -20)
        XCTAssertEqual(event.kind, .scroll)
        var guardValue = TouchpadSequenceGuard()
        try guardValue.accept(event)
        XCTAssertThrowsError(try guardValue.accept(event))
        XCTAssertThrowsError(try guardValue.accept(TouchpadEvent.decode(payload(sequence: 2, timestamp: 1233))))
        try guardValue.accept(TouchpadEvent.decode(payload(sequence: 3)))
    }
    func testRejectInvalidTouchpadPayload() {
        XCTAssertThrowsError(try TouchpadEvent.decode(payload(x: .nan)))
        XCTAssertThrowsError(try TouchpadEvent.decode(payload(y: .infinity)))
        XCTAssertThrowsError(try TouchpadEvent.decode(payload(kind: 7, fingers: 2, x: 1, y: 0)))
        XCTAssertThrowsError(try TouchpadEvent.decode(payload(kind: 7, fingers: 3, x: 0.5, y: 0.5)))
        XCTAssertThrowsError(try TouchpadEvent.decode(payload(kind: 6, x: 1.1, y: 0)))
        var reserved = payload(); reserved[31] = 1
        XCTAssertThrowsError(try TouchpadEvent.decode(reserved))
        XCTAssertThrowsError(try TouchpadEvent.decode(Data(reserved.dropLast())))
    }
    func testNativeScrollFieldsAndDryRun() throws {
        let scroll = try XCTUnwrap(QuartzTouchpadBackend.scrollEvent(x: 12, y: -20, phase: 2))
        XCTAssertEqual(scroll.type, .scrollWheel)
        XCTAssertEqual(scroll.getIntegerValueField(.scrollWheelEventIsContinuous), 1)
        XCTAssertEqual(scroll.getIntegerValueField(.scrollWheelEventScrollPhase), 2)
        XCTAssertEqual(scroll.getDoubleValueField(.scrollWheelEventPointDeltaAxis1), -20)
        let recording = QuartzTouchpadBackend(dryRun: true)
        recording.submit(try TouchpadEvent.decode(payload()))
        recording.release()
        XCTAssertEqual(recording.events, 1)
    }
}
