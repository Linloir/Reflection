import XCTest
@testable import ReflectionCore

final class HostStabilizationTests: XCTestCase {
    private func sample(_ index: UInt64, _ phase: PenPhase, _ x: Double = 0.5, _ y: Double = 0.5) -> PenSample {
        PenSample(sequence: index, timestampNanoseconds: index * 10_000_000, phase: phase, tool: .pen,
                  buttons: 0, x: x, y: y, pressure: phase == .down || phase == .move ? 0.5 : 0, tiltX: 0, tiltY: 0)
    }

    func testSmoothingReducesJitterAndUpKeepsAcceptedEndpoint() {
        let input = HostPenStabilizer(configuration: StabilizationConfiguration())
        input.rawClient = true
        _ = input.apply(sample(1, .down))
        XCTAssertTrue(input.status.contactDown)
        var rawEnergy = 0.0, filteredEnergy = 0.0
        var last: PenSample?
        for index in 2...200 {
            let x = 0.5 + (index % 2 == 0 ? 0.0006 : -0.0006)
            let output = input.apply(sample(UInt64(index), .move, x))!
            rawEnergy += (x - 0.5) * (x - 0.5)
            filteredEnergy += (output.x - 0.5) * (output.x - 0.5)
            XCTAssertLessThanOrEqual(abs(output.x - x), 0.0018 + 1e-12)
            last = output
        }
        XCTAssertLessThan(filteredEnergy, rawEnergy)
        let up = input.apply(sample(201, .up, 0.502))!
        XCTAssertEqual(up.phase, .up)
        XCTAssertEqual(up.x, last!.x)
        XCTAssertEqual(up.pressure, 0)
        XCTAssertFalse(input.status.contactDown)
    }

    func testGuardStillWorksWhenSmoothingIsOffAndHoverSpikeCannotAnchorDown() {
        var config = StabilizationConfiguration(); config.enabled = false
        let input = HostPenStabilizer(configuration: config)
        input.rawClient = true
        XCTAssertNotNil(input.apply(sample(1, .hover, 0.8, 0.8)))
        XCTAssertNil(input.apply(sample(2, .hover, 0.05, 0.05)))
        XCTAssertNil(input.apply(sample(3, .down, 0.05, 0.05)))
        XCTAssertNil(input.apply(sample(4, .move, 0.05, 0.05)))
        XCTAssertNil(input.apply(sample(5, .up, 0.05, 0.05)))
        XCTAssertNotNil(input.apply(sample(6, .down, 0.6, 0.6)))
        let cancelled = input.apply(sample(7, .move, 0, 0))!
        XCTAssertEqual(cancelled.phase, .cancel)
        XCTAssertEqual(cancelled.x, 0.6)
        XCTAssertGreaterThanOrEqual(input.guardRejections, 2)
    }

    func testConfirmedHoverRelocationAndLeavePermitFreshStroke() {
        let input = HostPenStabilizer(configuration: StabilizationConfiguration())
        _ = input.apply(sample(1, .hover, 0.1, 0.1))
        XCTAssertNil(input.apply(sample(2, .hover, 0.9, 0.9)))
        XCTAssertNotNil(input.apply(sample(3, .hover, 0.901, 0.901)))
        XCTAssertNotNil(input.apply(sample(4, .down, 0.901, 0.901)))
        XCTAssertEqual(input.apply(sample(5, .leave))?.phase, .cancel)
        XCTAssertEqual(input.apply(sample(6, .down))?.phase, .down)
    }

    func testLegacyDoesNotDoubleSmoothAndFreshDownGateRejectsOrphanMoves() {
        let input = HostPenStabilizer(configuration: StabilizationConfiguration())
        _ = input.apply(sample(1, .down))
        XCTAssertEqual(input.apply(sample(2, .move, 0.501))?.x, 0.501)
        input.reset(waitForDown: true)
        XCTAssertNil(input.apply(sample(3, .move)))
        XCTAssertNil(input.apply(sample(4, .up)))
        XCTAssertEqual(input.apply(sample(5, .down))?.phase, .down)
    }

    func testTerminalToolFlagChangeReleasesOldToolAndCurrentButtons() {
        let input = HostPenStabilizer(configuration: StabilizationConfiguration())
        let down = PenSample(sequence: 1, timestampNanoseconds: 10, phase: .down, tool: .pen,
            buttons: 1, x: 0.5, y: 0.5, pressure: 0.5, tiltX: 10, tiltY: 0)
        _ = input.apply(down)
        let up = input.apply(PenSample(sequence: 2, timestampNanoseconds: 20, phase: .up, tool: .eraser,
            buttons: 0, x: 0.9, y: 0.9, pressure: 0, tiltX: 15, tiltY: 0))!
        XCTAssertEqual(up.phase, .up)
        XCTAssertEqual(up.tool, .pen)
        XCTAssertEqual(up.buttons, 0)
        XCTAssertEqual(up.tiltX, 15)
        XCTAssertEqual(up.x, down.x)
        XCTAssertFalse(input.status.contactDown)
        XCTAssertEqual(input.apply(sample(3, .down))?.phase, .down)
    }

    func testEqualTimestampKeepsFilteredPositionAndUpdatesPenMetadata() {
        let input = HostPenStabilizer(configuration: StabilizationConfiguration())
        input.rawClient = true
        _ = input.apply(sample(1, .down))
        let previous = input.apply(sample(2, .move, 0.501))!
        let duplicate = input.apply(PenSample(sequence: 3, timestampNanoseconds: 20_000_000,
            phase: .move, tool: .pen, buttons: 1, x: 0.51, y: 0.52, pressure: 0.8, tiltX: 20, tiltY: 0))!
        XCTAssertEqual(duplicate.x, previous.x)
        XCTAssertEqual(duplicate.y, previous.y)
        XCTAssertEqual(duplicate.pressure, 0.8)
        XCTAssertEqual(duplicate.buttons, 1)
        XCTAssertEqual(duplicate.tiltX, 20)
        let up = input.apply(PenSample(sequence: 4, timestampNanoseconds: 20_000_000,
            phase: .up, tool: .pen, buttons: 0, x: 0.51, y: 0.52, pressure: 0, tiltX: 20, tiltY: 0))!
        XCTAssertEqual(up.phase, .up)
        XCTAssertEqual(up.x, previous.x)
        XCTAssertFalse(input.status.contactDown)
    }

    func testCalibrationAcceptsSmallDriftAndOutlierButPreservesFailureState() {
        let calibration = HostCalibration()
        calibration.start(noise: 0.0006, now: 0)
        XCTAssertNil(calibration.finish())
        XCTAssertTrue(calibration.status.active)
        XCTAssertEqual(calibration.status.result, "failed")
        calibration.add(sample(1, .down))
        for index in 1...180 {
            let x = 0.5 + Double(index) * 0.0000008 + 0.00035 * sin(Double(index) * 2.31) + (index == 78 ? 0.008 : 0)
            let y = 0.5 + 0.0003 * cos(Double(index) * 1.71)
            calibration.add(sample(UInt64(index + 1), .move, x, y))
        }
        calibration.add(sample(182, .up))
        XCTAssertEqual(calibration.status.progress, 1)
        let candidate = calibration.finish()
        XCTAssertNotNil(candidate)
        XCTAssertLessThan(candidate!, 0.001)
        XCTAssertEqual(calibration.status.noise, 0.0006, "Finish proposes a value until persistence succeeds")
        calibration.complete(noise: candidate!)
        XCTAssertFalse(calibration.status.active)
        XCTAssertEqual(calibration.status.result, "success")
    }

    func testCalibrationRejectsLargeMotionAndTimesOut() {
        let calibration = HostCalibration()
        calibration.start(noise: 0.0006, now: 0)
        calibration.add(sample(1, .down))
        for index in 1...180 { calibration.add(sample(UInt64(index + 1), .move, 0.5 + Double(index) * 0.001)) }
        XCTAssertNil(calibration.finish())
        XCTAssertEqual(calibration.status.phase, "failed")
        calibration.start(noise: 0.0006, now: 0)
        calibration.tick(now: 10)
        XCTAssertFalse(calibration.status.active)
        XCTAssertEqual(calibration.status.noise, 0.0006)
    }

    func testCalibrationDeduplicatesEqualTimestampsWithoutLosingContactTransitions() {
        func timed(_ sequence: UInt64, _ time: UInt64, _ phase: PenPhase, _ x: Double = 0.5) -> PenSample {
            PenSample(sequence: sequence, timestampNanoseconds: time, phase: phase, tool: .pen,
                buttons: 0, x: x, y: 0.5, pressure: phase == .down || phase == .move ? 0.5 : 0, tiltX: 0, tiltY: 0)
        }
        let calibration = HostCalibration()
        calibration.start(noise: 0.0006, now: 0)
        for index in 0...180 {
            let time = UInt64(1_000_000_000 + index * 10_000_000)
            calibration.add(timed(UInt64(index * 2 + 1), time, index == 0 ? .down : .move,
                                  0.5 + 0.0003 * sin(Double(index))))
            calibration.add(timed(UInt64(index * 2 + 2), time, .move, 0.99))
        }
        XCTAssertTrue(calibration.status.active)
        XCTAssertEqual(calibration.status.samples, 181)
        XCTAssertEqual(calibration.status.durationMS, 1800)
        calibration.add(timed(363, 2_800_000_000, .up))
        XCTAssertNotNil(calibration.finish())
        // UP at an equal timestamp still ended the contact; another DOWN is invalid.
        calibration.add(timed(364, 2_800_000_000, .down))
        XCTAssertEqual(calibration.status.phase, "failed")
        XCTAssertTrue(calibration.status.reason.contains("continuous hold"))

        calibration.start(noise: 0.0006, now: 0)
        calibration.add(timed(1, 100, .down))
        calibration.add(timed(2, 99, .move))
        XCTAssertEqual(calibration.status.phase, "failed")
    }
}
