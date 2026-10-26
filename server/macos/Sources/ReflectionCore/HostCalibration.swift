import Foundation

public struct CalibrationStatus: Codable {
    public var active = false
    public var phase = "idle"
    public var samples = 0
    public var durationMS = 0.0
    public var progress = 0.0
    public var noise = 0.0006
    public var previousNoise = 0.0006
    public var result = "none"
    public var reason = "Start calibration, then hold the pen lightly in one place."
    public var minimumSamples = 120
    public var minimumDurationMS = 1200
    private enum CodingKeys: String, CodingKey {
        case active, phase, samples, progress, noise, result, reason
        case durationMS = "duration_ms", previousNoise = "previous_noise"
        case minimumSamples = "min_samples", minimumDurationMS = "min_duration_ms"
    }
}

/// Bounded single-contact sampling. Short windows remove slow hand drift before
/// robust MAD noise estimation, instead of rejecting every nonstationary hand.
public final class HostCalibration {
    private struct Point { let time: UInt64; let x: Double; let y: Double }
    public private(set) var status = CalibrationStatus()
    private var points: [Point] = []
    private var tool: PenTool?
    private var ended = false
    private var startedAt = 0.0

    public init(noise: Double = 0.0006) { status.noise = noise; status.previousNoise = noise }
    public func start(noise: Double, now: Double = ProcessInfo.processInfo.systemUptime) {
        points.removeAll(keepingCapacity: true); tool = nil; ended = false; startedAt = now
        status = CalibrationStatus()
        status.active = true; status.phase = "collecting"; status.noise = noise; status.previousNoise = noise
        status.reason = "Touch down once and hold lightly for at least 1.2 seconds; small hand drift is OK."
    }
    public func cancel() {
        status.active = false; status.phase = "cancelled"; status.result = "cancelled"
        status.reason = "Calibration cancelled; previous noise setting was kept."
    }
    public func fail(_ reason: String) {
        status.active = false; status.phase = "failed"; status.result = "failed"; status.reason = reason
    }
    public func tick(now: Double = ProcessInfo.processInfo.systemUptime) {
        if status.active && now - startedAt >= 10 { fail("Timed out after 10 seconds. Start again and finish after the progress bar fills.") }
    }

    public func add(_ sample: PenSample) {
        guard status.active else { return }
        if sample.phase == .cancel { fail("The pen contact was cancelled. Start again with one continuous hold."); return }
        if sample.phase == .up { if !points.isEmpty { ended = true }; return }
        if sample.phase == .hover || sample.phase == .leave {
            if !points.isEmpty && !ended { fail("The tablet lost contact during the hold. Keep the pen tip touching the surface.") }
            return
        }
        guard sample.phase == .down || sample.phase == .move else { return }
        if points.isEmpty && sample.phase != .down { return }
        if ended || (!points.isEmpty && sample.phase == .down) {
            fail("More than one pen contact was detected. Use one continuous hold."); return
        }
        if let previous = points.last {
            if sample.timestampNanoseconds < previous.time || tool != sample.tool {
                fail("The input tool or sample timing changed during calibration."); return
            }
            // Android history may contain several legal samples in one timestamp
            // bucket. Keep one measurement without changing contact transitions.
            if sample.timestampNanoseconds == previous.time { return }
        }
        guard points.count < 4096 else { fail("The 4096-sample limit was reached. Finish calibration sooner."); return }
        tool = sample.tool
        points.append(Point(time: sample.timestampNanoseconds, x: sample.x, y: sample.y))
        status.samples = points.count
        status.durationMS = Double(sample.timestampNanoseconds - points[0].time) * 1e-6
        status.progress = min(1, min(Double(status.samples) / 120, status.durationMS / 1200))
        if status.progress >= 1 {
            status.phase = "ready"; status.result = "none"
            status.reason = "Enough data collected. Lift the pen and choose Finish calibration."
        }
    }

    /// Returns a proposed noise value; only complete() publishes success after saving it.
    public func finish() -> Double? {
        guard status.active else {
            status.result = "failed"; status.reason = "Start calibration before finishing."
            return nil
        }
        guard status.samples >= 120, status.durationMS >= 1200 else {
            status.result = "failed"; status.phase = "collecting"
            status.reason = status.samples < 120
                ? "Need at least 120 samples; keep holding the pen and finish again."
                : "Need at least 1.2 seconds of contact; keep holding the pen and finish again."
            return nil
        }
        let x = points.map(\.x), y = points.map(\.y)
        if hypot(x.max()! - x.min()!, y.max()! - y.min()!) > 0.08 {
            fail("The pen moved over 8% of the surface. Use a smaller, relaxed hold."); return nil
        }
        var estimates: [Double] = []
        var start = 0
        while start < points.count {
            var end = start + 1
            while end < points.count && points[end].time - points[start].time < 160_000_000 { end += 1 }
            let window = Array(points[start..<end])
            if window.count >= 8 {
                if hypot(window.last!.x - window.first!.x, window.last!.y - window.first!.y) > 0.02 {
                    fail("The pen moved too quickly during a short window. Rest your hand and try again."); return nil
                }
                estimates.append(max(residualNoise(window, axis: \.x), residualNoise(window, axis: \.y)))
            }
            start = end
        }
        guard estimates.count >= 3 else {
            fail("Need at least three well-sampled 160 ms windows. Check the tablet connection and try again."); return nil
        }
        estimates.sort()
        let index = Double(estimates.count - 1) * 0.75
        let lower = Int(index), upper = min(lower + 1, estimates.count - 1)
        let candidate = estimates[lower] + (estimates[upper] - estimates[lower]) * (index - Double(lower))
        guard candidate.isFinite, candidate <= 0.02 else {
            fail("Measured noise exceeded 2% of the surface. Check the pen and tablet before retrying."); return nil
        }
        return min(0.02, max(0.00001, candidate))
    }

    public func complete(noise: Double) {
        status.noise = noise; status.active = false; status.phase = "complete"; status.result = "success"
        status.progress = 1; status.reason = "Calibration saved on this computer and applied immediately."
    }

    private func residualNoise(_ values: [Point], axis: KeyPath<Point, Double>) -> Double {
        let times = values.map { Double($0.time - values[0].time) * 1e-9 }
        let meanTime = times.reduce(0, +) / Double(times.count)
        let meanValue = values.map { $0[keyPath: axis] }.reduce(0, +) / Double(values.count)
        var numerator = 0.0, denominator = 0.0
        for index in values.indices {
            numerator += (times[index] - meanTime) * (values[index][keyPath: axis] - meanValue)
            denominator += (times[index] - meanTime) * (times[index] - meanTime)
        }
        let slope = denominator > 0 ? numerator / denominator : 0
        let residuals = values.indices.map { values[$0][keyPath: axis] - meanValue - slope * (times[$0] - meanTime) }
        let center = median(residuals)
        return 1.4826 * median(residuals.map { abs($0 - center) })
    }

    private func median(_ values: [Double]) -> Double {
        let ordered = values.sorted(), count = values.count
        return count % 2 == 0 ? (ordered[count / 2 - 1] + ordered[count / 2]) / 2 : ordered[count / 2]
    }
}
