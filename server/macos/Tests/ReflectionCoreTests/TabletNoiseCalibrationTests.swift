import XCTest
@testable import ReflectionCore

final class TabletNoiseCalibrationTests: XCTestCase {
    private func payload(_ sequence: UInt64, _ noise: Float) -> Data {
        Data(TabletNoiseCalibration.acknowledgement(sequence: sequence, noise: Double(noise)).dropFirst())
    }
    func testStrictProtocolAndReplay() throws {
        let bytes = payload(1, 0.003)
        let value = try TabletNoiseCalibration.decode(bytes, after: 0)
        XCTAssertEqual(value.sequence, 1)
        XCTAssertEqual(value.noise, 0.003, accuracy: 1e-8)
        XCTAssertThrowsError(try TabletNoiseCalibration.decode(bytes, after: 1))
        XCTAssertThrowsError(try TabletNoiseCalibration.decode(payload(2, 0.003), after: 0))
        XCTAssertThrowsError(try TabletNoiseCalibration.decode(payload(1, .nan), after: 0))
        XCTAssertThrowsError(try TabletNoiseCalibration.decode(payload(1, 0.03), after: 0))
        XCTAssertThrowsError(try TabletNoiseCalibration.decode(bytes.dropLast(), after: 0))
        XCTAssertEqual(try TabletNoiseCalibration.decode(payload(1, 0.00001), after: 0).noise, 0.00001)
        XCTAssertEqual(try TabletNoiseCalibration.decode(payload(1, 0), after: 0).noise, 0)
    }
    func testSessionOverrideRestoresConfiguredPriorAndRejectsContact() throws {
        var configuration = StabilizationConfiguration()
        configuration.noise = 0.0007
        let stabilizer = HostPenStabilizer(configuration: configuration)
        try stabilizer.setSessionNoise(0.003)
        XCTAssertEqual(stabilizer.configuration.noise, 0.003)
        XCTAssertEqual(stabilizer.status.estimatedNoiseX, 0.003)
        try stabilizer.setSessionNoise(0.004)
        stabilizer.clearSessionNoise()
        XCTAssertEqual(stabilizer.configuration.noise, 0.0007)
        try stabilizer.setSessionNoise(0.002)
        try stabilizer.setSessionNoise(0)
        XCTAssertEqual(stabilizer.configuration.noise, 0.0007)
        _ = stabilizer.apply(PenSample(sequence: 1, timestampNanoseconds: 100, phase: .down, tool: .pen,
            buttons: 0, x: 0.5, y: 0.5, pressure: 0.5, tiltX: 0, tiltY: 0))
        XCTAssertThrowsError(try stabilizer.setSessionNoise(0.005))
        XCTAssertEqual(stabilizer.configuration.noise, 0.0007)
    }
}
