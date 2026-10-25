import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.PenStabilizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

public final class PenStabilizerTest {
    private static final double NOISE = PenStabilizer.DEFAULT_NOISE;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static PenSample point(long sequence, long nanos, double x, double y, int phase) {
        return new PenSample(sequence, nanos, (float)x, (float)y, 0.43f, 12, -23, 0.1f,
            phase, PenSample.PEN, 3, 7);
    }
    private static PenSample point(long sequence, long nanos, double x, double y, int phase, int tool) {
        PenSample p = point(sequence, nanos, x, y, phase);
        return new PenSample(p.sequence, p.timeNanos, p.x, p.y, p.pressure, p.tiltX, p.tiltY,
            p.distance, p.phase, tool, p.buttons, p.capabilities);
    }
    private static void unchangedMetadata(PenSample raw, PenSample out) {
        check(raw.sequence == out.sequence && raw.timeNanos == out.timeNanos, "sample identity and time");
        check(raw.phase == out.phase && raw.tool == out.tool && raw.buttons == out.buttons && raw.capabilities == out.capabilities,
            "phase, tool, buttons and capabilities");
        check(raw.pressure == out.pressure && raw.tiltX == out.tiltX && raw.tiltY == out.tiltY && raw.distance == out.distance,
            "pressure, tilt and distance");
    }
    private static void boundaries() {
        PenStabilizer filter = new PenStabilizer();
        filter.setEnabled(false);
        for (int phase = PenSample.HOVER; phase <= PenSample.LEAVE; phase++) {
            PenSample p = point(phase, phase * 4000000L, 0.2, 0.8, phase);
            check(filter.apply(p) == p, "disabled is an exact object bypass");
        }
        filter.setEnabled(true);
        PenSample down = point(10, 100000000L, 0.2, 0.8, PenSample.DOWN);
        check(filter.apply(down) == down, "DOWN never waits for more samples");
        PenSample moved = point(11, 104000000L, 0.21, 0.81, PenSample.MOVE);
        unchangedMetadata(moved, filter.apply(moved));
        PenSample repeated = point(12, 104000000L, 0.22, 0.82, PenSample.MOVE);
        check(filter.apply(repeated) == repeated, "equal timestamps never divide by zero or drop a point");
        PenSample backwards = point(13, 103000000L, 0.23, 0.83, PenSample.MOVE);
        check(filter.apply(backwards) == backwards, "backward timestamps reset");
        PenSample gap = point(14, 300000000L, 0.24, 0.84, PenSample.MOVE);
        check(filter.apply(gap) == gap, "long idle gap resets");
        PenSample eraser = point(15, 304000000L, 0.25, 0.85, PenSample.MOVE, PenSample.ERASER);
        check(filter.apply(eraser) == eraser, "tool switch resets");
        PenSample hover = point(16, 308000000L, 0.26, 0.86, PenSample.HOVER);
        check(filter.apply(hover) == hover, "contact to hover resets");
        PenSample contact = point(17, 312000000L, 0.27, 0.87, PenSample.MOVE);
        check(filter.apply(contact) == contact, "hover to contact resets");
        for (int phase : new int[]{PenSample.UP, PenSample.CANCEL, PenSample.LEAVE}) {
            PenSample end = point(18, 316000000L, 0.28, 0.88, phase);
            check(filter.apply(end) == end, "contact boundary exact");
            PenSample next = point(19, 320000000L, 0.7, 0.3, PenSample.MOVE);
            check(filter.apply(next) == next, "no residual state after contact boundary");
        }
        filter.reset();
        PenSample reconnect = point(1, 20000000L, 0.6, 0.6, PenSample.MOVE);
        check(filter.apply(reconnect) == reconnect, "explicit reconnect reset");
    }
    private static void fastGeometry() {
        PenStabilizer filter = new PenStabilizer();
        double[][] w = {{.1,.1},{.2,.9},{.4,.3},{.6,.9},{.8,.1},{.8,.1}};
        for (int i = 0; i < w.length; i++) {
            PenSample p = point(i + 1, 1000000000L + i * 4000000L, w[i][0], w[i][1],
                i == 0 ? PenSample.DOWN : i == w.length - 1 ? PenSample.UP : PenSample.MOVE);
            PenSample out = filter.apply(p);
            unchangedMetadata(p, out);
            check(Math.hypot(out.x - p.x, out.y - p.y) <= NOISE * .751, "fast W initial vertices retained");
        }
        for (int hz : new int[]{30, 60, 120, 240}) {
            filter.reset();
            for (int i = 0; i <= hz; i++) {
                double progress = (double)i / hz;
                double x = .2 + Math.min(progress, .5), y = .2 + Math.max(0, progress - .5);
                PenSample p = point(i + 1, 1000000000L + Math.round(progress * 1000000000L), x, y,
                    i == 0 ? PenSample.DOWN : PenSample.MOVE);
                PenSample out = filter.apply(p);
                check(Math.hypot(out.x - p.x, out.y - p.y) <= NOISE * .751, "90 degree corner bound at " + hz);
            }
        }
        filter.reset();
        Random random = new Random(813);
        for (int i = 0; i < 200; i++) filter.apply(point(i + 1, i * 5000000L,
            .5 + random.nextGaussian() * NOISE, .5 + random.nextGaussian() * NOISE,
            i == 0 ? PenSample.DOWN : PenSample.MOVE));
        PenSample onset = point(201, 1000000000L, .54, .58, PenSample.MOVE);
        PenSample response = filter.apply(onset);
        check(Math.hypot(response.x - onset.x, response.y - onset.y) <= NOISE * .751,
            "fast motion immediately leaves the stationary smoothing state");
    }
    private static void stationaryMetrics() {
        for (int hz : new int[]{60, 120, 240}) {
            Random random = new Random(4431 + hz);
            PenStabilizer filter = new PenStabilizer();
            int total = hz * 12, count = 0;
            double rawSquared = 0, filteredSquared = 0;
            double[] errors = new double[total];
            long time = 1000000000L;
            for (int i = 0; i < total; i++) {
                time += Math.round((.8 + .4 * random.nextDouble()) * 1000000000 / hz);
                PenSample raw = point(i + 1, time, .5 + random.nextGaussian() * NOISE,
                    .5 + random.nextGaussian() * NOISE, i == 0 ? PenSample.DOWN : PenSample.MOVE);
                PenSample out = filter.apply(raw);
                unchangedMetadata(raw, out);
                check(Math.hypot(out.x - raw.x, out.y - raw.y) <= NOISE * 2.501, "hard maximum correction");
                if (i >= hz) {
                    double before = Math.hypot(raw.x - .5, raw.y - .5), after = Math.hypot(out.x - .5, out.y - .5);
                    rawSquared += before * before; filteredSquared += after * after; errors[count++] = after;
                }
            }
            Arrays.sort(errors, 0, count);
            double ratio = Math.sqrt(filteredSquared / rawSquared);
            check(ratio < .72, "stationary jitter reduction at " + hz + ": " + ratio);
            System.out.printf(Locale.ROOT, "Stabilizer stationary %dHz irregular: RMS %.3fx raw, p95 %.3f sigma, max %.3f sigma%n",
                hz, ratio, errors[(count - 1) * 95 / 100] / NOISE, errors[count - 1] / NOISE);
        }
    }
    private static void microMotionMetrics() {
        for (int hz : new int[]{60, 120, 240}) {
            for (int amplitude : new int[]{2, 5, 10}) {
                double lowestRatio = 1, highestLag = 0;
                for (int frequency : new int[]{1, 5, 12}) {
                    Random random = new Random(125 + hz + frequency);
                    PenStabilizer filter = new PenStabilizer();
                    long time = 1000000000L;
                    double sinProjection = 0, cosProjection = 0, rawProjection = 0;
                    for (int i = 0; i < hz * 8; i++) {
                        time += Math.round((.85 + .3 * random.nextDouble()) * 1000000000 / hz);
                        double seconds = (time - 1000000000L) * 1e-9;
                        double angle = 2 * Math.PI * frequency * seconds;
                        PenSample raw = point(i + 1, time, .5 + amplitude * NOISE * Math.sin(angle), .5,
                            i == 0 ? PenSample.DOWN : PenSample.MOVE);
                        PenSample out = filter.apply(raw);
                        if (i >= hz) {
                            sinProjection += (out.x - .5) * Math.sin(angle);
                            cosProjection += (out.x - .5) * Math.cos(angle);
                            rawProjection += (raw.x - .5) * Math.sin(angle);
                        }
                    }
                    double ratio = Math.hypot(sinProjection, cosProjection) / rawProjection;
                    double lag = Math.abs(Math.atan2(cosProjection, sinProjection) / (2 * Math.PI * frequency)) * 1000;
                    lowestRatio = Math.min(lowestRatio, ratio); highestLag = Math.max(highestLag, lag);
                    double minimumAmplitude = amplitude == 2 ? .65 : amplitude == 5 ? .86 : .92;
                    double maximumLagMillis = amplitude == 2 ? 50 : amplitude == 5 ? 35 : 20;
                    check(ratio > minimumAmplitude, "intentional oscillation retained at " + hz + "Hz, " + amplitude + "sigma, " + frequency + "Hz: " + ratio);
                    check(lag < maximumLagMillis, "intentional oscillation phase lag at " + hz + "Hz, " + amplitude + "sigma: " + lag);
                    check(ratio < 1.03, "no oscillation amplification");
                }
                System.out.printf(Locale.ROOT, "Stabilizer micro-motion %dHz irregular, %d sigma: amplitude >=%.3f, phase lag <=%.2fms (1/5/12Hz)%n",
                    hz, amplitude, lowestRatio, highestLag);
            }
        }
    }
    private static void loopsAndAnisotropicBounds() {
        PenStabilizer filter = new PenStabilizer();
        float noiseX = .0002f, noiseY = .001f;
        filter.setCalibration(new PenStabilizer.Calibration(noiseX, noiseY, 300, 1500000000L, 1000, 1600, 0));
        Random random = new Random(351);
        for (int i = 0; i < 500; i++) {
            PenSample raw = point(i + 1, i * 8000000L, .5 + random.nextGaussian() * noiseX,
                .5 + random.nextGaussian() * noiseY, i == 0 ? PenSample.DOWN : PenSample.MOVE);
            PenSample out = filter.apply(raw);
            check(Math.hypot((out.x - raw.x) / noiseX, (out.y - raw.y) / noiseY) < 2.501,
                "calibration bounds correction with an axis-specific ellipse");
        }
        filter.setCalibration(null);
        for (int hz : new int[]{60, 120, 240}) {
            filter.reset();
            double minRadius = Double.POSITIVE_INFINITY;
            for (int i = 0; i < hz * 4; i++) {
                double angle = 2 * Math.PI * i / hz;
                PenSample raw = point(i + 1, Math.round(i * 1000000000.0 / hz),
                    .5 + 2 * NOISE * Math.cos(angle), .5 + 2 * NOISE * Math.sin(angle),
                    i == 0 ? PenSample.DOWN : PenSample.MOVE);
                PenSample out = filter.apply(raw);
                if (i >= hz) {
                    double radius = Math.hypot(out.x - .5, out.y - .5);
                    minRadius = Math.min(minRadius, radius);
                    check(radius <= 2.001 * NOISE, "small circular motion never overshoots");
                }
            }
            check(minRadius > 1.25 * NOISE, "small deliberate loops are not collapsed at " + hz);
        }
    }
    private static void calibration() {
        PenStabilizer.Calibrator waiting = new PenStabilizer.Calibrator(1000, 1600, 0);
        for (int i = 0; i < 300; i++) waiting.add(point(i, i * 5000000L, .5, .5, PenSample.MOVE));
        check(waiting.sampleCount() == 0, "calibration waits for a fresh DOWN");
        PenStabilizer.Calibrator shortHold = new PenStabilizer.Calibrator(1000, 1600, 0);
        shortHold.add(point(1, 1, .5, .5, PenSample.DOWN));
        check(!shortHold.finish().accepted && shortHold.finish().reason.equals("not-enough-samples"), "insufficient data rejected");
        PenStabilizer.Calibrator briefHold = new PenStabilizer.Calibrator(1000, 1600, 0);
        for (int i = 0; i < 120; i++) briefHold.add(point(i + 1, i * 1000000L, .5, .5, i == 0 ? PenSample.DOWN : PenSample.MOVE));
        check(briefHold.finish().reason.equals("hold-longer"), "adequate count without adequate time rejected");
        PenStabilizer.Calibrator measured = new PenStabilizer.Calibrator(1000, 1600, 0);
        Random random = new Random(8135);
        for (int i = 0; i < 600; i++) {
            double outlier = i % 101 == 0 ? .02 : 0;
            measured.add(point(i + 1, i * 5000000L, .5 + random.nextGaussian() * .0005 + outlier,
                .5 + random.nextGaussian() * .001 + outlier, i == 0 ? PenSample.DOWN : PenSample.MOVE));
        }
        PenStabilizer.CalibrationResult result = measured.finish();
        check(result.accepted, "stationary anisotropic noise accepted: " + result.reason);
        check(Math.abs(result.calibration.noiseX / .0005 - 1) < .25 && Math.abs(result.calibration.noiseY / .001 - 1) < .25,
            "robust axis-specific noise estimate resists outliers");
        check(result.calibration.matchesGeometry(1000, 1600, 0) && !result.calibration.matchesGeometry(1600, 1000, 1),
            "calibration tied to source surface and rotation");
        for (boolean wave : new boolean[]{false, true}) {
            PenStabilizer.Calibrator moving = new PenStabilizer.Calibrator(1000, 1600, 0);
            for (int i = 0; i < 300; i++) {
                double x = .5 + (wave ? .004 * Math.sin(i * .05) : .002 * i / 300);
                moving.add(point(i + 1, i * 5000000L, x, .5, i == 0 ? PenSample.DOWN : PenSample.MOVE));
            }
            check(!moving.finish().accepted && moving.finish().reason.equals("movement-detected"),
                "coherent " + (wave ? "oscillation" : "drift") + " rejected");
        }
        PenStabilizer.Calibrator taps = new PenStabilizer.Calibrator(1000, 1600, 0);
        taps.add(point(1, 0, .5, .5, PenSample.DOWN));
        taps.add(point(2, 5000000, .5, .5, PenSample.UP));
        taps.add(point(3, 10000000, .5, .5, PenSample.DOWN));
        check(taps.finish().reason.equals("multiple-contacts"), "multiple contacts rejected");
        boolean invalidRejected = false;
        try { new PenStabilizer.Calibration(Float.NaN, .001f, 120, 1000000000L, 1000, 1600, 0); }
        catch (IllegalArgumentException expected) { invalidRejected = true; }
        check(invalidRejected, "invalid persisted calibration rejected");
        PenStabilizer filter = new PenStabilizer();
        filter.setCalibration(result.calibration);
        check(filter.calibration() == result.calibration, "calibration applied");
        filter.setCalibration(null);
        check(filter.calibration() == null, "clearing calibration restores defaults");
    }
    public static void main(String[] args) {
        boundaries(); fastGeometry(); stationaryMetrics(); microMotionMetrics(); loopsAndAnisotropicBounds(); calibration();
        System.out.println("Android stabilizer boundaries, jitter/motion metrics and calibration tests passed");
    }
}
