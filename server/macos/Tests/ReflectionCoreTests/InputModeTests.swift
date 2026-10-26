import XCTest
@testable import ReflectionCore

final class InputModeTests: XCTestCase {
    func testLegacyConfigurationDefaultsToAPI() throws {
        let old = Data("{\"token\":\"\(String(repeating: "a", count: 64))\"}".utf8)
        var config = try JSONDecoder().decode(ServerConfiguration.self, from: old)
        XCTAssertEqual(config.inputMode, "api")
        config.inputMode = "driver"
        XCTAssertEqual(try JSONDecoder().decode(ServerConfiguration.self, from: JSONEncoder().encode(config)).inputMode, "driver")
        config.inputMode = "unknown"
        XCTAssertThrowsError(try config.validate())
    }
    func testSharedHIDVectorAndRelease() throws {
        let sample = PenSample(sequence: 1, timestampNanoseconds: 1, phase: .down,
            tool: .pen, buttons: 0, x: 1, y: 0, pressure: 0.5, tiltX: 90, tiltY: -90)
        let mapped = MappedSample(sample: sample, point: CGPoint(x: -1, y: -100),
            pressure: 0.5, tiltX: 90, tiltY: -90)
        let report = try TabletReport.encode(mapped, desktop: CGRect(x: -1920, y: -100, width: 1920, height: 1080))
        XCTAssertEqual(Array(report), [1, 0x11, 0xff, 0x7f, 0, 0, 0, 0x80, 90, 166])
        let lifted = TabletReport.release(report, inRange: true)
        XCTAssertEqual(lifted[1], 0x10)
        XCTAssertEqual(lifted[2..<6], report[2..<6])
        XCTAssertEqual(lifted[6..<8], Data([0, 0]))
        XCTAssertEqual(TabletReport.release(lifted, inRange: false)[1], 0)
    }
    func testEraserHoverNeverHasContactOrPressure() throws {
        for phase in [PenPhase.hover, .up, .cancel, .leave] {
            let sample = PenSample(sequence: 1, timestampNanoseconds: 1, phase: phase,
                tool: .eraser, buttons: 0, x: 0.5, y: 0.5, pressure: 1, tiltX: 0, tiltY: 0)
            let mapped = MappedSample(sample: sample, point: .zero, pressure: 1, tiltX: 0, tiltY: 0)
            let report = try TabletReport.encode(mapped, desktop: CGRect(x: 0, y: 0, width: 1920, height: 1080))
            XCTAssertEqual(report[1], phase == .hover || phase == .up ? 0x14 : 0)
            XCTAssertEqual(report[6..<8], Data([0, 0]))
        }
    }
}
