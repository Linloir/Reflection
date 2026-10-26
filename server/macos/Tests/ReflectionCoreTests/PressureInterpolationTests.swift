import XCTest
@testable import ReflectionCore

final class PressureInterpolationTests: XCTestCase {
    func testSmoothCurvePassesThroughPointsAndDoesNotOvershootPlateaus() throws {
        var mapping = MappingConfiguration()
        mapping.pressureInterpolation = "smooth"
        mapping.pressureCurve = [PressurePoint(input: 0, output: 0), PressurePoint(input: 0.1, output: 0.6),
            PressurePoint(input: 0.7, output: 0.6), PressurePoint(input: 0.9, output: 0.8), PressurePoint(input: 1, output: 1)]
        try mapping.validate()
        for point in mapping.pressureCurve { XCTAssertEqual(mapping.mapPressure(point.input), point.output, accuracy: 1e-12) }
        var previous = 0.0
        for index in 0...10000 {
            let input = Double(index) / 10000
            let output = mapping.mapPressure(input)
            XCTAssertTrue(output.isFinite)
            XCTAssertGreaterThanOrEqual(output + 1e-12, previous)
            XCTAssertGreaterThanOrEqual(output, 0)
            XCTAssertLessThanOrEqual(output, 1)
            if (0.1...0.7).contains(input) { XCTAssertEqual(output, 0.6, accuracy: 1e-12) }
            previous = output
        }
    }

    func testSmoothAndLinearRemainDistinctWithStableEndpoints() throws {
        var mapping = MappingConfiguration()
        mapping.pressureCurve = [PressurePoint(input: 0, output: 0), PressurePoint(input: 0.5, output: 0.2), PressurePoint(input: 1, output: 1)]
        XCTAssertEqual(mapping.mapPressure(0.25), 0.1, accuracy: 1e-12)
        mapping.pressureInterpolation = "smooth"
        XCTAssertLessThan(mapping.mapPressure(0.25), 0.1)
        XCTAssertEqual(mapping.mapPressure(0), 0)
        XCTAssertEqual(mapping.mapPressure(1), 1)
        mapping.pressureCurve = [PressurePoint(input: 0, output: 0), PressurePoint(input: 1, output: 1)]
        XCTAssertEqual(mapping.mapPressure(0.25), 0.25, accuracy: 1e-12)
        mapping.pressureInterpolation = "invalid"
        XCTAssertThrowsError(try mapping.validate())
    }

    func testOldConfigDefaultsToLinearAndHostDefaults() throws {
        let mapping = try JSONDecoder().decode(MappingConfiguration.self, from: Data("{}".utf8))
        XCTAssertEqual(mapping.pressureInterpolation, "linear")
        let host = try JSONDecoder().decode(ServerConfiguration.self,
            from: Data("{\"token\":\"\(String(repeating: "a", count: 64))\"}".utf8))
        XCTAssertTrue(host.stabilization.enabled)
        XCTAssertEqual(host.stabilization.noise, 0.0006)
    }
}
