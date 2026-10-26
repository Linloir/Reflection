import XCTest
@testable import ReflectionCore

final class AdaptiveNoiseTests: XCTestCase {
    private struct Random {
        var state: UInt64 = 0x12345678
        mutating func normal() -> Double {
            func uniform(_ state: inout UInt64) -> Double {
                state = state &* 6364136223846793005 &+ 1442695040888963407
                return max(1e-12, Double(state >> 11) / 9007199254740992)
            }
            let a = uniform(&state), b = uniform(&state)
            return sqrt(-2 * log(a)) * cos(2 * Double.pi * b)
        }
    }
    private func sample(_ index: UInt64, _ x: Double, _ y: Double, phase: PenPhase = .move) -> PenSample {
        PenSample(sequence: index + 1, timestampNanoseconds: 1_000_000_000 + index * 4_000_000,
            phase: phase, tool: .pen, buttons: 0, x: x, y: y,
            pressure: phase == .down || phase == .move ? 0.5 : 0, tiltX: 0, tiltY: 0)
    }
    private func configuration(adaptive: Bool = true) -> StabilizationConfiguration {
        var value = StabilizationConfiguration(); value.noise = 0.00012; value.adaptiveNoise = adaptive
        return value
    }
    private func input(adaptive: Bool = true) -> HostPenStabilizer {
        let value = HostPenStabilizer(configuration: configuration(adaptive: adaptive)); value.rawClient = true
        return value
    }

    func testDefaultsAndExplicitOptOutRoundTrip() throws {
        let old = try JSONDecoder().decode(StabilizationConfiguration.self, from: Data("{}".utf8))
        XCTAssertTrue(old.adaptiveNoise)
        let disabled = try JSONDecoder().decode(StabilizationConfiguration.self, from: Data("{\"adaptiveNoise\":false}".utf8))
        XCTAssertFalse(disabled.adaptiveNoise)
        XCTAssertEqual(try JSONDecoder().decode(StabilizationConfiguration.self, from: JSONEncoder().encode(disabled)), disabled)
    }

    func testVariableStationaryNoiseImprovesWithoutChangingSavedPrior() throws {
        let adaptive = input(), fixed = input(adaptive: false)
        let saved = adaptive.configuration
        var random = Random(), adaptiveEnergy = 0.0, fixedEnergy = 0.0
        for index in 0..<1100 {
            let amplitude = index >= 250 && index < 850 ? 0.002 : 0.00012
            let point = sample(UInt64(index), 0.5 + 0.00012 * random.normal(),
                0.5 + amplitude * random.normal(), phase: index == 0 ? .down : .move)
            let a = adaptive.apply(point)!, b = fixed.apply(point)!
            if index >= 350 && index < 850 {
                adaptiveEnergy += pow(a.y - 0.5, 2); fixedEnergy += pow(b.y - 0.5, 2)
            }
            XCTAssertGreaterThanOrEqual(adaptive.status.estimatedNoiseX, saved.noise)
            XCTAssertGreaterThanOrEqual(adaptive.status.estimatedNoiseY, saved.noise)
            XCTAssertLessThanOrEqual(adaptive.status.estimatedNoiseX, 0.02)
            XCTAssertLessThanOrEqual(adaptive.status.estimatedNoiseY, 0.02)
            XCTAssertEqual(a.pressure, point.pressure)
        }
        XCTAssertLessThan(adaptiveEnergy, fixedEnergy * 0.8)
        XCTAssertEqual(adaptive.configuration, saved)
        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(adaptive.configuration)) as! [String: Any]
        XCTAssertEqual(encoded["noise"] as? Double, saved.noise)
        XCTAssertNil(encoded["estimatedNoiseX"])
        let status = try JSONSerialization.jsonObject(with: JSONEncoder().encode(adaptive.status)) as! [String: Any]
        XCTAssertEqual(status["adaptive_noise"] as? Bool, true)
        XCTAssertNotNil(status["estimated_noise_x"])
        XCTAssertNotNil(status["estimated_noise_y"])
        XCTAssertGreaterThan(adaptive.status.noiseSamples, 0)
    }

    func testSmallCircleAndCornerRemainAfterLearningLargerNoise() {
        let adaptive = input()
        var random = Random()
        for index in 0..<250 {
            _ = adaptive.apply(sample(UInt64(index), 0.5 + 0.002 * random.normal(),
                0.5 + 0.002 * random.normal(), phase: index == 0 ? .down : .move))
        }
        var radius = 0.0, count = 0
        for index in 250..<1000 {
            let angle = Double(index - 250) * 0.004 * 4 * Double.pi
            let output = adaptive.apply(sample(UInt64(index), 0.5 + 0.003 * cos(angle), 0.5 + 0.003 * sin(angle)))!
            if index >= 375 { radius += hypot(output.x - 0.5, output.y - 0.5); count += 1 }
        }
        XCTAssertGreaterThan(radius / Double(count), 0.003 * 0.90)
        XCTAssertLessThan(radius / Double(count), 0.003 * 1.10)
        // A right angle must stay local to its source corner, even after a noisy hold.
        var nearestCorner = Double.infinity
        for index in 1000..<1200 {
            let step = Double(index - 1000) * 0.00048
            let x = 0.5 + min(0.048, step), y = 0.5 + max(0, step - 0.048)
            let output = adaptive.apply(sample(UInt64(index), x, y))!
            nearestCorner = min(nearestCorner, hypot(output.x - 0.548, output.y - 0.5))
        }
        XCTAssertLessThan(nearestCorner, 0.002)
        XCTAssertEqual(adaptive.guardRejections, 0)
    }

    func testQuantizedPlateausDoNotInflateNoiseOrChangeFixedOutput() {
        let adaptive = input(), fixed = input(adaptive: false)
        for index in 0..<500 {
            let x = 0.5 + Double((index / 7) % 3) / 4096
            let y = 0.5 + Double((index / 11) % 3) / 2048
            let point = sample(UInt64(index), x, y, phase: index == 0 ? .down : .move)
            let a = adaptive.apply(point)!, b = fixed.apply(point)!
            XCTAssertEqual(a.x, b.x, accuracy: 1e-14)
            XCTAssertEqual(a.y, b.y, accuracy: 1e-14)
        }
        XCTAssertEqual(adaptive.status.estimatedNoiseX, adaptive.configuration.noise)
        XCTAssertEqual(adaptive.status.estimatedNoiseY, adaptive.configuration.noise)
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
    }

    func testSubMicrosecondSamplesPreserveHistoryAndReleaseLifecycle() {
        let adaptive = input()
        var random = Random()
        var last: PenSample!
        for index in 0..<100 {
            last = adaptive.apply(sample(UInt64(index), 0.5 + 0.001 * random.normal(),
                0.5 + 0.001 * random.normal(), phase: index == 0 ? .down : .move))!
        }
        let before = adaptive.status
        for offset: UInt64 in [0, 1, 999] {
            let result = adaptive.apply(PenSample(sequence: 101 + offset, timestampNanoseconds: last.timestampNanoseconds + offset,
                phase: .move, tool: .pen, buttons: 1, x: 0.51, y: 0.52, pressure: 0.8, tiltX: 20, tiltY: -10))!
            XCTAssertEqual(result.x, last.x); XCTAssertEqual(result.y, last.y)
            XCTAssertEqual(result.pressure, 0.8); XCTAssertEqual(result.buttons, 1)
            XCTAssertEqual(result.tiltX, 20)
            XCTAssertEqual(adaptive.status.noiseSamples, before.noiseSamples)
            XCTAssertEqual(adaptive.status.estimatedNoiseX, before.estimatedNoiseX)
        }
        let up = adaptive.apply(PenSample(sequence: 1200, timestampNanoseconds: last.timestampNanoseconds + 999,
            phase: .up, tool: .pen, buttons: 0, x: 0.9, y: 0.9, pressure: 0, tiltX: 0, tiltY: 0))!
        XCTAssertEqual(up.phase, .up); XCTAssertEqual(up.x, last.x)
        XCTAssertFalse(adaptive.status.contactDown)
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
        XCTAssertEqual(adaptive.status.estimatedNoiseX, before.estimatedNoiseX)
        _ = adaptive.apply(sample(101, 0.5, 0.5, phase: .down))
        XCTAssertEqual(adaptive.status.estimatedNoiseX, before.estimatedNoiseX)
        adaptive.reset()
        XCTAssertEqual(adaptive.status.estimatedNoiseX, adaptive.configuration.noise)
        XCTAssertEqual(adaptive.status.estimatedNoiseY, adaptive.configuration.noise)
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
    }

    func testGapsAndToolTransitionsClearWindowWithoutPersistingRuntimeNoise() {
        let adaptive = input()
        var random = Random()
        for index in 0..<100 {
            _ = adaptive.apply(sample(UInt64(index), 0.5 + 0.001 * random.normal(),
                0.5 + 0.001 * random.normal(), phase: .hover))
        }
        let sigma = adaptive.status.estimatedNoiseX
        XCTAssertGreaterThan(adaptive.status.noiseSamples, 0)
        _ = adaptive.apply(PenSample(sequence: 101, timestampNanoseconds: 1_400_000_000, phase: .hover,
            tool: .eraser, buttons: 0, x: 0.5, y: 0.5, pressure: 0, tiltX: 0, tiltY: 0))
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
        XCTAssertEqual(adaptive.status.estimatedNoiseX, sigma)
        _ = adaptive.apply(sample(200, 0.5, 0.5, phase: .down))
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
        XCTAssertEqual(adaptive.status.estimatedNoiseX, sigma)
        adaptive.configuration.noise = 0.0004
        adaptive.reset(waitForDown: true)
        XCTAssertEqual(adaptive.status.estimatedNoiseX, 0.0004)
        XCTAssertNil(adaptive.apply(sample(201, 0.5, 0.5)))
        XCTAssertEqual(adaptive.apply(sample(202, 0.5, 0.5, phase: .down))?.phase, .down)
    }

    func testIrregularSamplingClearsEvidenceAndNeedsAFreshWindow() {
        let adaptive = input()
        var random = Random()
        for index in 0..<100 {
            _ = adaptive.apply(sample(UInt64(index), 0.5 + 0.001 * random.normal(),
                0.5 + 0.001 * random.normal(), phase: index == 0 ? .down : .move))
        }
        let sigma = adaptive.status.estimatedNoiseX
        func point(_ index: UInt64, _ time: UInt64) -> PenSample {
            PenSample(sequence: index, timestampNanoseconds: time, phase: .move, tool: .pen,
                buttons: 0, x: 0.5, y: 0.5, pressure: 0.5, tiltX: 0, tiltY: 0)
        }
        // This 30 ms gap invalidates noise evidence without reseeding the 100 ms filter.
        _ = adaptive.apply(point(101, 1_426_000_000))
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
        XCTAssertEqual(adaptive.status.estimatedNoiseX, sigma)
        for index in 0..<15 { _ = adaptive.apply(point(UInt64(index + 102), 1_430_000_000 + UInt64(index) * 4_000_000)) }
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
        XCTAssertEqual(adaptive.status.estimatedNoiseX, sigma)
        _ = adaptive.apply(point(200, 1_700_000_000))
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
        XCTAssertEqual(adaptive.status.estimatedNoiseX, sigma)
        XCTAssertTrue(adaptive.status.contactDown)
    }

    func testNoiseBurstReleasesOnQuietPlateauAndIdleResetsPrior() {
        let adaptive = input()
        var random = Random()
        for index in 0..<300 {
            _ = adaptive.apply(sample(UInt64(index), 0.5 + 0.002 * random.normal(),
                0.5 + 0.002 * random.normal(), phase: index == 0 ? .down : .move))
        }
        XCTAssertGreaterThan(adaptive.status.estimatedNoiseX, adaptive.configuration.noise * 3)
        for index in 300..<800 { _ = adaptive.apply(sample(UInt64(index), 0.5, 0.5)) }
        XCTAssertLessThan(adaptive.status.estimatedNoiseX, adaptive.configuration.noise * 1.6)
        XCTAssertLessThan(adaptive.status.estimatedNoiseY, adaptive.configuration.noise * 1.6)
        XCTAssertEqual(adaptive.status.noiseSamples, 0, "A quiet plateau is not a trusted random-noise estimate")
        var radius = 0.0
        for index in 800..<1050 {
            let angle = Double(index - 800) * 0.004 * 4 * Double.pi
            let point = adaptive.apply(sample(UInt64(index), 0.5 + 0.003 * cos(angle), 0.5 + 0.003 * sin(angle)))!
            radius += hypot(point.x - 0.5, point.y - 0.5)
        }
        XCTAssertGreaterThan(radius / 250, 0.003 * 0.95)
        // Learn another burst, lift, then idle: the new contact starts from prior.
        for index in 1050..<1350 {
            _ = adaptive.apply(sample(UInt64(index), 0.5 + 0.002 * random.normal(), 0.5 + 0.002 * random.normal()))
        }
        _ = adaptive.apply(sample(1350, 0.5, 0.5, phase: .up))
        XCTAssertGreaterThan(adaptive.status.estimatedNoiseX, adaptive.configuration.noise * 3)
        _ = adaptive.apply(sample(1700, 0.5, 0.5, phase: .down))
        XCTAssertEqual(adaptive.status.estimatedNoiseX, adaptive.configuration.noise)
        XCTAssertEqual(adaptive.status.estimatedNoiseY, adaptive.configuration.noise)
        XCTAssertEqual(adaptive.status.noiseSamples, 0)
    }
}
