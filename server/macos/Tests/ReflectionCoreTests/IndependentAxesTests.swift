import XCTest
@testable import ReflectionCore

final class IndependentAxesTests: XCTestCase {
    private func input(adaptive: Bool, independent: Bool) -> HostPenStabilizer {
        var config = StabilizationConfiguration()
        config.noise = 0.0006
        config.adaptiveNoise = adaptive
        config.independentAxes = independent
        let result = HostPenStabilizer(configuration: config)
        result.rawClient = true
        return result
    }
    private func sample(_ index: Int, x: Double, y: Double, phase: PenPhase = .move) -> PenSample {
        PenSample(sequence: UInt64(index + 1), timestampNanoseconds: 1_000_000_000 + UInt64(index) * 4_000_000,
            phase: phase, tool: .pen, buttons: 0, x: x, y: y,
            pressure: phase == .down || phase == .move ? 0.6 : 0, tiltX: 12, tiltY: -8)
    }

    func testOptInDefaultsAndFourConfigurationCombinations() throws {
        let old = try JSONDecoder().decode(StabilizationConfiguration.self, from: Data("{}".utf8))
        XCTAssertFalse(old.independentAxes)
        XCTAssertTrue(old.adaptiveNoise)
        for adaptive in [false, true] {
            for independent in [false, true] {
                let filter = input(adaptive: adaptive, independent: independent)
                let value = filter.configuration
                XCTAssertEqual(try JSONDecoder().decode(StabilizationConfiguration.self,
                    from: JSONEncoder().encode(value)), value)
                let status = try JSONSerialization.jsonObject(with: JSONEncoder().encode(filter.status)) as! [String: Any]
                XCTAssertEqual(status["independent_axes"] as? Bool, independent)
                XCTAssertEqual(status["adaptive_noise"] as? Bool, adaptive)
            }
        }
        XCTAssertThrowsError(try JSONDecoder().decode(StabilizationConfiguration.self,
            from: Data("{\"independentAxes\":1}".utf8)))
    }

    func testStraightStrokeReducesSidewaysNoiseWithEitherNoiseMode() {
        for adaptive in [false, true] {
            let independent = input(adaptive: adaptive, independent: true)
            let shared = input(adaptive: adaptive, independent: false)
            let horizontal = input(adaptive: adaptive, independent: true)
            var independentEnergy = 0.0, sharedEnergy = 0.0
            for index in 0..<600 {
                let x = 0.5 + 0.0012 * sin(Double(index) * 0.63)
                let y = 0.15 + Double(index) * 0.001
                let phase: PenPhase = index == 0 ? .down : .move
                let point = sample(index, x: x, y: y, phase: phase)
                let a = independent.apply(point)!, b = shared.apply(point)!
                let transposed = horizontal.apply(sample(index, x: y, y: x, phase: phase))!
                XCTAssertEqual(a.x, transposed.y, accuracy: 1e-12)
                XCTAssertEqual(a.y, transposed.x, accuracy: 1e-12)
                XCTAssertEqual(a.pressure, point.pressure)
                if index > 100 {
                    independentEnergy += pow(a.x - 0.5, 2)
                    sharedEnergy += pow(b.x - 0.5, 2)
                }
            }
            XCTAssertLessThan(independentEnergy, sharedEnergy * 0.95)
            XCTAssertEqual(independent.guardRejections, 0)
        }
    }

    func testSmallCircleAndCornerRemainWithEitherNoiseMode() {
        for adaptive in [false, true] {
            let filter = input(adaptive: adaptive, independent: true)
            filter.configuration.noise = 0.00012
            filter.reset()
            var radius = 0.0, count = 0
            for index in 0..<600 {
                let angle = Double(index) * 0.004 * 4 * Double.pi
                let result = filter.apply(sample(index,
                    x: 0.5 + 0.003 * cos(angle), y: 0.5 + 0.003 * sin(angle),
                    phase: index == 0 ? .down : .move))!
                if index >= 125 { radius += hypot(result.x - 0.5, result.y - 0.5); count += 1 }
            }
            XCTAssertGreaterThan(radius / Double(count), 0.003 * 0.80)
            XCTAssertLessThan(radius / Double(count), 0.003 * 1.20)
            _ = filter.apply(sample(600, x: 0.5, y: 0.5, phase: .up))
            var nearestCorner = Double.infinity
            for index in 601..<801 {
                let step = Double(index - 601) * 0.00048
                let result = filter.apply(sample(index,
                    x: 0.5 + min(0.048, step), y: 0.5 + max(0, step - 0.048),
                    phase: index == 601 ? .down : .move))!
                nearestCorner = min(nearestCorner, hypot(result.x - 0.548, result.y - 0.5))
            }
            XCTAssertLessThan(nearestCorner, 0.001)
            XCTAssertEqual(filter.guardRejections, 0)
        }
    }

    func testSameTimestampPreservesContactAndReleaseWithEitherNoiseMode() {
        for adaptive in [false, true] {
            let filter = input(adaptive: adaptive, independent: true)
            let down = sample(0, x: 0.5, y: 0.5, phase: .down)
            _ = filter.apply(down)
            let point = PenSample(sequence: 2, timestampNanoseconds: down.timestampNanoseconds,
                phase: .move, tool: .pen, buttons: 1, x: 0.501, y: 0.502,
                pressure: 0.8, tiltX: 21, tiltY: -7)
            let held = filter.apply(point)!
            XCTAssertEqual(held.x, down.x)
            XCTAssertEqual(held.y, down.y)
            XCTAssertEqual(held.pressure, 0.8)
            XCTAssertEqual(held.buttons, 1)
            XCTAssertEqual(held.tiltX, 21)
            let up = PenSample(sequence: 3, timestampNanoseconds: down.timestampNanoseconds,
                phase: .up, tool: .pen, buttons: 0, x: 0.51, y: 0.52,
                pressure: 0, tiltX: 21, tiltY: -7)
            let released = filter.apply(up)!
            XCTAssertEqual(released.phase, .up)
            XCTAssertEqual(released.x, held.x)
            XCTAssertEqual(released.y, held.y)
            XCTAssertFalse(filter.status.contactDown)
            XCTAssertEqual(filter.guardRejections, 0)
        }
    }
}
