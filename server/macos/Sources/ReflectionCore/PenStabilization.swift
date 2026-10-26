import Foundation

public struct StabilizationConfiguration: Codable, Equatable {
    public var enabled = true
    public var adaptiveNoise = true
    public var independentAxes = false
    public var strength = 0.5
    public var minCutoffHz = 3.0
    public var beta = 80.0
    public var derivativeCutoffHz = 12.0
    public var noise = 0.0006
    public var maxJump = 0.25
    public init() {}

    private enum CodingKeys: String, CodingKey {
        case enabled, adaptiveNoise, independentAxes, strength, minCutoffHz, beta, derivativeCutoffHz, noise, maxJump
    }
    public init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? enabled
        adaptiveNoise = try c.decodeIfPresent(Bool.self, forKey: .adaptiveNoise) ?? adaptiveNoise
        independentAxes = try c.decodeIfPresent(Bool.self, forKey: .independentAxes) ?? independentAxes
        strength = try c.decodeIfPresent(Double.self, forKey: .strength) ?? strength
        minCutoffHz = try c.decodeIfPresent(Double.self, forKey: .minCutoffHz) ?? minCutoffHz
        beta = try c.decodeIfPresent(Double.self, forKey: .beta) ?? beta
        derivativeCutoffHz = try c.decodeIfPresent(Double.self, forKey: .derivativeCutoffHz) ?? derivativeCutoffHz
        noise = try c.decodeIfPresent(Double.self, forKey: .noise) ?? noise
        maxJump = try c.decodeIfPresent(Double.self, forKey: .maxJump) ?? maxJump
    }
    public func validate() throws {
        guard [strength, minCutoffHz, beta, derivativeCutoffHz, noise, maxJump].allSatisfy(\.isFinite),
              (0...1).contains(strength), (0.1...60).contains(minCutoffHz), (0...1000).contains(beta),
              (0.1...120).contains(derivativeCutoffHz), (0.00001...0.02).contains(noise),
              (0.05...1).contains(maxJump) else {
            throw ReflectionError.invalid("Invalid host stabilization strength, cutoff, noise, or jump limit")
        }
    }
}

public struct StabilizationStatus: Codable {
    public var enabled: Bool
    public var active: Bool
    public var guardEnabled = true
    public var guardRejections: UInt64
    public var suppressedStrokes: UInt64
    public var legacyClient: Bool
    public var contactDown: Bool
    public var adaptiveNoise = true
    public var independentAxes = false
    public var estimatedNoiseX = 0.0006
    public var estimatedNoiseY = 0.0006
    public var noiseSamples = 0
    private enum CodingKeys: String, CodingKey {
        case enabled, active
        case guardEnabled = "guard_enabled", guardRejections = "guard_rejections"
        case suppressedStrokes = "suppressed_strokes", legacyClient = "legacy_client"
        case contactDown = "contact_down"
        case adaptiveNoise = "adaptive_noise", estimatedNoiseX = "estimated_noise_x"
        case independentAxes = "independent_axes"
        case estimatedNoiseY = "estimated_noise_y", noiseSamples = "noise_samples"
    }
}

/// Raw transport validation stays independent of the contact state repaired below.
public struct PenSequenceGuard {
    private var sequence: UInt64 = 0
    private var timestamp: UInt64 = 0
    public init() {}
    public mutating func accept(_ sample: PenSample) throws {
        guard sample.sequence > sequence, sequence != 0 || sample.sequence == 1,
              sample.timestampNanoseconds >= timestamp else {
            throw ReflectionError.invalid("Replayed, unordered, or invalid initial pen sequence")
        }
        sequence = sample.sequence; timestamp = sample.timestampNanoseconds
    }
}

extension PenSample {
    func replacingPosition(x: Double, y: Double, phase: PenPhase? = nil) -> PenSample {
        let phase = phase ?? self.phase
        return PenSample(sequence: sequence, timestampNanoseconds: timestampNanoseconds, phase: phase,
            tool: tool, buttons: phase == .cancel ? 0 : buttons, x: x, y: y,
            pressure: phase == .down || phase == .move ? pressure : 0,
            tiltX: tiltX, tiltY: tiltY, rotation: rotation, distance: distance)
    }
}

/// A bounded, causal estimate of coordinate noise. It changes correction budgets,
/// never measured velocity, the source points, or the saved calibration prior.
private struct AdaptivePenNoise {
    private struct Residual { let time: UInt64; let x: Double; let y: Double }
    private var history: [PenSample] = []
    private var residuals: [Residual] = []
    private var count: UInt64 = 0
    private var lastUpdate: UInt64?
    private var lastObservation: UInt64?
    private var eligible = false
    private(set) var x: Double
    private(set) var y: Double
    var samples: Int { eligible ? residuals.count : 0 }

    init(prior: Double) { x = prior; y = prior }
    mutating func reset(prior: Double) { x = prior; y = prior; lastObservation = nil; clearHistory() }
    mutating func clearHistory() {
        history.removeAll(keepingCapacity: true); residuals.removeAll(keepingCapacity: true)
        count = 0; lastUpdate = nil; eligible = false
    }
    mutating func observe(_ sample: PenSample, prior: Double) {
        if let lastObservation, sample.timestampNanoseconds > lastObservation,
           sample.timestampNanoseconds - lastObservation > 1_000_000_000 {
            x = prior; y = prior; clearHistory()
        }
        lastObservation = sample.timestampNanoseconds
        count += 1
        history.append(sample)
        if history.count > 3 { history.removeFirst() }
        guard history.count == 3 else { return }
        let dt1 = history[1].timestampNanoseconds - history[0].timestampNanoseconds
        let dt2 = history[2].timestampNanoseconds - history[1].timestampNanoseconds
        let ratio = dt1 > 0 ? Double(dt2) / Double(dt1) : 0
        guard dt1 >= 1_000, dt2 >= 1_000, dt1 <= 20_000_000, dt2 <= 20_000_000,
              (0.5...2).contains(ratio) else {
            residuals.removeAll(keepingCapacity: true); lastUpdate = nil; eligible = false
            return
        }
        let scale = sqrt(1 + (1 + ratio) * (1 + ratio) + ratio * ratio)
        let rx = (history[2].x - (1 + ratio) * history[1].x + ratio * history[0].x) / scale
        let ry = (history[2].y - (1 + ratio) * history[1].y + ratio * history[0].y) / scale
        residuals.append(Residual(time: sample.timestampNanoseconds, x: rx, y: ry))
        while residuals.count > 48 || (!residuals.isEmpty && sample.timestampNanoseconds - residuals[0].time > 250_000_000) {
            residuals.removeFirst()
        }
        guard residuals.count >= 16,
              sample.timestampNanoseconds - residuals[0].time >= 40_000_000 else { eligible = false; return }
        guard count % 4 == 0 else { return }

        func estimate(_ values: [Double], current: Double) -> Double? {
            let ordered = values.sorted()
            let center = Self.median(ordered)
            let candidate = min(0.02, max(prior, 1.4826 * Self.median(values.map { abs($0 - center) }.sorted())))
            // Discrete coordinate steps are not evidence of extra random noise.
            let separation = max(0.0000001, prior * 0.05)
            var levels = 1, previous = ordered[0]
            for value in ordered.dropFirst() where value - previous >= separation {
                levels += 1; previous = value
            }
            guard levels >= 8,
                  abs(Self.median(Array(values.suffix(8)).sorted())) <= 3 * max(current, candidate) else { return nil }
            return candidate
        }
        let targetX = estimate(residuals.map(\.x), current: x)
        let targetY = estimate(residuals.map(\.y), current: y)
        eligible = targetX != nil || targetY != nil
        let elapsed = lastUpdate.map { min(0.1, Double(sample.timestampNanoseconds - $0) * 1e-9) } ?? 0.016
        // Curvature and quantized plateaus cannot justify an increase. They must
        // still release a previous burst's larger estimate toward calibration.
        let nextX = targetX ?? prior, nextY = targetY ?? prior
        x += (1 - exp(-elapsed / (nextX > x ? 0.06 : 0.5))) * (nextX - x)
        y += (1 - exp(-elapsed / (nextY > y ? 0.06 : 0.5))) * (nextY - y)
        lastUpdate = sample.timestampNanoseconds
    }
    private static func median(_ sorted: [Double]) -> Double {
        let mid = sorted.count / 2
        return sorted.count % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) * 0.5 : sorted[mid]
    }
}

/// Immediate One-Euro filtering with a bounded error; it never queues samples.
/// Contact repair and jump rejection remain active when smoothing is disabled.
public final class HostPenStabilizer {
    public var configuration: StabilizationConfiguration
    public var rawClient = false
    public private(set) var guardRejections: UInt64 = 0
    public private(set) var suppressedStrokes: UInt64 = 0
    private var lastSafe: PenSample?
    private var hoverCandidate: PenSample?
    private var touching = false
    private var suppressed = false
    private var needsDown = false
    private var filterTime: UInt64?
    private var rawX = 0.0, rawY = 0.0, filteredX = 0.0, filteredY = 0.0
    private var derivativeX = 0.0, derivativeY = 0.0
    private var noiseEstimator: AdaptivePenNoise

    public init(configuration: StabilizationConfiguration) {
        self.configuration = configuration
        noiseEstimator = AdaptivePenNoise(prior: configuration.noise)
    }
    public var status: StabilizationStatus {
        StabilizationStatus(enabled: configuration.enabled,
            active: configuration.enabled && rawClient && configuration.strength > 0,
            guardRejections: guardRejections, suppressedStrokes: suppressedStrokes,
            legacyClient: !rawClient, contactDown: touching, adaptiveNoise: configuration.adaptiveNoise,
            independentAxes: configuration.independentAxes,
            estimatedNoiseX: configuration.adaptiveNoise ? noiseEstimator.x : configuration.noise,
            estimatedNoiseY: configuration.adaptiveNoise ? noiseEstimator.y : configuration.noise,
            noiseSamples: configuration.adaptiveNoise && configuration.enabled && configuration.strength > 0 && rawClient ? noiseEstimator.samples : 0)
    }
    public func reset(waitForDown: Bool = false) {
        lastSafe = nil; hoverCandidate = nil; touching = false; suppressed = false; needsDown = waitForDown
        resetFilter()
        noiseEstimator.reset(prior: configuration.noise)
    }

    public func apply(_ sample: PenSample) -> PenSample? {
        if needsDown {
            guard sample.phase == .down else { return nil }
            needsDown = false
        }
        if suppressed {
            if sample.phase == .up || sample.phase == .cancel || sample.phase == .leave {
                suppressed = false; lastSafe = nil; resetFilter()
            }
            return nil
        }
        if sample.phase == .cancel || sample.phase == .leave {
            let result = touching ? cancel(at: sample, suppress: false) : sample
            touching = false; lastSafe = nil; resetFilter()
            return result
        }
        if touching {
            guard let previous = lastSafe else { reset(); return nil }
            if sample.phase == .hover || sample.phase == .down || (sample.phase != .up && sample.tool != previous.tool) {
                return cancel(at: sample, suppress: true)
            }
            if sample.phase == .move && distance(sample, previous) > configuration.maxJump {
                return cancel(at: sample, suppress: true)
            }
            if sample.phase == .up {
                touching = false
                if distance(sample, previous) > configuration.maxJump {
                    guardRejections += 1
                }
                // UP carries release semantics; never snap a smoothed endpoint to raw noise.
                let result = PenSample(sequence: sample.sequence, timestampNanoseconds: sample.timestampNanoseconds,
                    phase: .up, tool: previous.tool, buttons: sample.buttons, x: previous.x, y: previous.y,
                    pressure: 0, tiltX: sample.tiltX, tiltY: sample.tiltY, rotation: sample.rotation, distance: sample.distance)
                lastSafe = nil; hoverCandidate = nil; resetFilter()
                return result
            }
        } else if sample.phase == .move || sample.phase == .up {
            // Never turn an orphaned contact move into a cursor movement.
            guardRejections += 1
            return nil
        }
        if sample.phase == .hover, let previous = lastSafe,
           sample.timestampNanoseconds >= previous.timestampNanoseconds,
           sample.timestampNanoseconds - previous.timestampNanoseconds <= 150_000_000,
           distance(sample, previous) > configuration.maxJump {
            if let candidate = hoverCandidate,
               sample.timestampNanoseconds >= candidate.timestampNanoseconds,
               sample.timestampNanoseconds - candidate.timestampNanoseconds <= 150_000_000,
               distance(sample, candidate) <= configuration.maxJump {
                hoverCandidate = nil; resetFilter()
            } else {
                hoverCandidate = sample; guardRejections += 1
                return nil
            }
        } else if sample.phase == .hover { hoverCandidate = nil }
        if sample.phase == .down {
            hoverCandidate = nil
            if let previous = lastSafe, previous.phase == .hover,
               sample.timestampNanoseconds >= previous.timestampNanoseconds,
               sample.timestampNanoseconds - previous.timestampNanoseconds <= 150_000_000,
               distance(sample, previous) > configuration.maxJump {
                guardRejections += 1; suppressedStrokes += 1; suppressed = true
                resetFilter()
                return nil
            }
            touching = true
            resetFilter()
        }
        let result = smooth(sample)
        lastSafe = result
        return result
    }

    private func cancel(at sample: PenSample, suppress: Bool) -> PenSample {
        let previous = lastSafe ?? sample
        let result = PenSample(sequence: sample.sequence, timestampNanoseconds: sample.timestampNanoseconds,
            phase: .cancel, tool: previous.tool, buttons: 0, x: previous.x, y: previous.y,
            pressure: 0, tiltX: previous.tiltX, tiltY: previous.tiltY, rotation: previous.rotation, distance: previous.distance)
        guardRejections += 1; suppressedStrokes += 1
        touching = false; suppressed = suppress; resetFilter()
        return result
    }

    private func distance(_ a: PenSample, _ b: PenSample) -> Double { hypot(a.x - b.x, a.y - b.y) }
    private func resetFilter() {
        filterTime = nil; derivativeX = 0; derivativeY = 0; noiseEstimator.clearHistory()
    }
    private func seed(_ sample: PenSample) {
        rawX = sample.x; rawY = sample.y; filteredX = sample.x; filteredY = sample.y
        derivativeX = 0; derivativeY = 0; filterTime = sample.timestampNanoseconds
        noiseEstimator.clearHistory()
        if configuration.adaptiveNoise && configuration.enabled && rawClient && configuration.strength > 0 {
            noiseEstimator.observe(sample, prior: configuration.noise)
        }
    }
    private func smooth(_ sample: PenSample) -> PenSample {
        guard configuration.enabled, rawClient, configuration.strength > 0 else { seed(sample); return sample }
        if let previous = lastSafe, previous.tool != sample.tool || (previous.phase == .hover) != (sample.phase == .hover) {
            resetFilter()
        }
        if let filterTime, sample.timestampNanoseconds >= filterTime,
           sample.timestampNanoseconds - filterTime < 1_000, let previous = lastSafe {
            // Keep the accepted coordinate at sub-microsecond dt; metadata comes
            // from this event, and the next timestamp retains the filter history.
            return sample.replacingPosition(x: previous.x, y: previous.y)
        }
        guard let filterTime, sample.timestampNanoseconds > filterTime,
              sample.timestampNanoseconds - filterTime <= 100_000_000 else { seed(sample); return sample }
        let dt = Double(sample.timestampNanoseconds - filterTime) * 1e-9
        if configuration.adaptiveNoise { noiseEstimator.observe(sample, prior: configuration.noise) }
        func alpha(_ cutoff: Double) -> Double {
            let value = 2 * Double.pi * cutoff * dt
            return value / (1 + value)
        }
        let derivativeAlpha = alpha(configuration.derivativeCutoffHz)
        derivativeX += derivativeAlpha * ((sample.x - rawX) / dt - derivativeX)
        derivativeY += derivativeAlpha * ((sample.y - rawY) / dt - derivativeY)
        let speed = hypot(derivativeX, derivativeY)
        let cutoff = configuration.minCutoffHz / (0.25 + 1.5 * configuration.strength) + configuration.beta * speed
        let positionAlpha = alpha(cutoff)
        var x = filteredX + positionAlpha * (sample.x - filteredX)
        var y = filteredY + positionAlpha * (sample.y - filteredY)
        filteredX = x; filteredY = y
        let blend = min(1, 2 * configuration.strength)
        x = sample.x + blend * (x - sample.x)
        y = sample.y + blend * (y - sample.y)
        func budget(_ noise: Double, _ axisSpeed: Double) -> Double {
            max(noise * 0.5, noise * (1 + 4 * configuration.strength) / (1 + axisSpeed / (noise * 120 + 1e-6)))
        }
        // Keep the position cutoff shared. Only the optional straight-stroke
        // correction budgets use the already-smoothed velocity of each axis.
        let boundX = budget(configuration.adaptiveNoise ? noiseEstimator.x : configuration.noise,
                            configuration.independentAxes ? abs(derivativeX) : speed)
        let boundY = budget(configuration.adaptiveNoise ? noiseEstimator.y : configuration.noise,
                            configuration.independentAxes ? abs(derivativeY) : speed)
        let bound = max(boundX, boundY)
        let error = hypot(x - sample.x, y - sample.y)
        if !configuration.independentAxes && error > bound {
            x = sample.x + (x - sample.x) * bound / error
            y = sample.y + (y - sample.y) * bound / error
        }
        if configuration.adaptiveNoise || configuration.independentAxes {
            x = sample.x + min(boundX, max(-boundX, x - sample.x))
            y = sample.y + min(boundY, max(-boundY, y - sample.y))
        }
        rawX = sample.x; rawY = sample.y
        self.filterTime = sample.timestampNanoseconds
        return sample.replacingPosition(x: x, y: y)
    }
}
