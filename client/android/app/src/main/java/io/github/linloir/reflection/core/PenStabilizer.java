package io.github.linloir.reflection.core;

import java.util.Arrays;

/**
 * Optional, bounded coordinate smoothing over source timestamps; no samples are delayed or dropped.
 * Uses the speed-adaptive cutoff from Casiez, Roussel and Vogel's One Euro filter
 * (https://gery.casiez.net/1euro/), with a shared 2D cutoff and a noise-scaled error bound.
 * Constants below are conservative Reflection defaults, not universal settings from that paper.
 * Intentional motion inside the sensor noise distribution cannot be identified perfectly.
 *
 * This class is thread-confined. Call reset on reconnect and surface changes.
 */
public final class PenStabilizer {
    public static final float DEFAULT_NOISE = 0.0006f;
    public static final float MIN_NOISE = 0.00002f, MAX_NOISE = 0.003f;
    public static final int MIN_CALIBRATION_SAMPLES = 120, MAX_CALIBRATION_SAMPLES = 4096;
    public static final long MIN_CALIBRATION_NANOS = 1000000000L;
    private static final double MIN_CUTOFF = 3, DERIVATIVE_CUTOFF = 12, SPEED_COEFFICIENT = 80;
    private static final double MOVING_BOUND = 0.75, STILL_BOUND = 2.5;
    private static final long RESET_GAP_NANOS = 100000000L, WINDOW_NANOS = 160000000L;
    private final double[] windowX = new double[64], windowY = new double[64];
    private final long[] windowTime = new long[64];
    private int windowStart, windowCount, lastTool, lastPhase;
    private long lastTime;
    private double rawX, rawY, filteredX, filteredY, derivativeX, derivativeY;
    private boolean initialized, enabled = true;
    private Calibration calibration;

    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { if (this.enabled != enabled) reset(); this.enabled = enabled; }
    public Calibration calibration() { return calibration; }
    public void setCalibration(Calibration calibration) { this.calibration = calibration; reset(); }
    public void reset() {
        initialized = false; windowStart = 0; windowCount = 0;
        derivativeX = 0; derivativeY = 0;
    }

    public PenSample apply(PenSample sample) {
        if (!enabled) return sample;
        if (!finite(sample.x) || !finite(sample.y)) { reset(); return sample; }
        if (sample.phase == PenSample.UP || sample.phase == PenSample.CANCEL || sample.phase == PenSample.LEAVE) {
            reset(); return sample;
        }
        boolean hoverChanged = initialized && (lastPhase == PenSample.HOVER) != (sample.phase == PenSample.HOVER);
        long elapsed = sample.timeNanos - lastTime;
        if (!initialized || sample.phase == PenSample.DOWN || sample.tool != lastTool || hoverChanged
            || elapsed <= 0 || elapsed > RESET_GAP_NANOS) {
            seed(sample); return sample;
        }
        double seconds = elapsed * 1e-9;
        double derivativeAlpha = alpha(DERIVATIVE_CUTOFF, seconds);
        derivativeX += derivativeAlpha * ((sample.x - rawX) / seconds - derivativeX);
        derivativeY += derivativeAlpha * ((sample.y - rawY) / seconds - derivativeY);
        double cutoff = MIN_CUTOFF + SPEED_COEFFICIENT * Math.hypot(derivativeX, derivativeY);
        double positionAlpha = alpha(cutoff, seconds);
        double nextX = filteredX + positionAlpha * (sample.x - filteredX);
        double nextY = filteredY + positionAlpha * (sample.y - filteredY);
        append(sample);
        double noiseX = calibration == null ? DEFAULT_NOISE : calibration.noiseX;
        double noiseY = calibration == null ? DEFAULT_NOISE : calibration.noiseY;
        double confidence = stationaryConfidence(noiseX, noiseY);
        double bound = MOVING_BOUND + (STILL_BOUND - MOVING_BOUND) * confidence;
        double errorX = (nextX - sample.x) / noiseX, errorY = (nextY - sample.y) / noiseY;
        double radius = Math.hypot(errorX, errorY);
        if (radius > bound) {
            double scale = bound / radius;
            nextX = sample.x + (nextX - sample.x) * scale;
            nextY = sample.y + (nextY - sample.y) * scale;
        }
        rawX = sample.x; rawY = sample.y; lastTime = sample.timeNanos;
        lastTool = sample.tool; lastPhase = sample.phase;
        filteredX = nextX; filteredY = nextY;
        return new PenSample(sample.sequence, sample.timeNanos, (float) nextX, (float) nextY,
            sample.pressure, sample.tiltX, sample.tiltY, sample.distance,
            sample.phase, sample.tool, sample.buttons, sample.capabilities);
    }

    private void seed(PenSample sample) {
        reset(); initialized = true;
        rawX = filteredX = sample.x; rawY = filteredY = sample.y;
        lastTime = sample.timeNanos; lastTool = sample.tool; lastPhase = sample.phase;
        append(sample);
    }
    private void append(PenSample sample) {
        while (windowCount > 0 && (windowCount == windowX.length
            || sample.timeNanos - windowTime[windowStart] > WINDOW_NANOS)) {
            windowStart = (windowStart + 1) % windowX.length; windowCount--;
        }
        int index = (windowStart + windowCount++) % windowX.length;
        windowX[index] = sample.x; windowY[index] = sample.y; windowTime[index] = sample.timeNanos;
    }
    private double stationaryConfidence(double noiseX, double noiseY) {
        if (windowCount < 8 || lastWindowTime() - windowTime[windowStart] < 100000000L) return 0;
        double meanX = 0, meanY = 0;
        for (int i = 0; i < windowCount; i++) {
            int index = (windowStart + i) % windowX.length;
            meanX += windowX[index]; meanY += windowY[index];
        }
        meanX /= windowCount; meanY /= windowCount;
        double variance = 0, lag = 0, lagTwo = 0, previousX = 0, previousY = 0, previousTwoX = 0, previousTwoY = 0;
        double earlyX = 0, earlyY = 0, lateX = 0, lateY = 0;
        int third = windowCount / 3;
        for (int i = 0; i < windowCount; i++) {
            int index = (windowStart + i) % windowX.length;
            double x = (windowX[index] - meanX) / noiseX, y = (windowY[index] - meanY) / noiseY;
            variance += x * x + y * y;
            if (i > 0) lag += x * previousX + y * previousY;
            if (i > 1) lagTwo += x * previousTwoX + y * previousTwoY;
            previousTwoX = previousX; previousTwoY = previousY;
            previousX = x; previousY = y;
            if (i < third) { earlyX += x; earlyY += y; }
            if (i >= windowCount - third) { lateX += x; lateY += y; }
        }
        double correlation = variance < 1e-12 ? 0 : Math.max(lag / variance, Math.abs(lagTwo) / variance);
        double drift = Math.hypot((lateX - earlyX) / third, (lateY - earlyY) / third);
        double correlationConfidence = clamp((0.45 - correlation) / 0.3, 0, 1);
        double driftConfidence = clamp((2 - drift) / 1.5, 0, 1);
        double spreadConfidence = clamp((12 - variance / windowCount) / 6, 0, 1);
        return Math.min(correlationConfidence, Math.min(driftConfidence, spreadConfidence));
    }
    private long lastWindowTime() { return windowTime[(windowStart + windowCount - 1) % windowX.length]; }
    private static double alpha(double cutoff, double seconds) {
        double scaled = 2 * Math.PI * cutoff * seconds;
        return scaled / (1 + scaled);
    }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    /** Persist these scalar values together. Coordinates are fractions of the tagged input surface. */
    public static final class Calibration {
        public final float noiseX, noiseY;
        public final int sampleCount, width, height, rotation;
        public final long durationNanos;
        public Calibration(float noiseX, float noiseY, int sampleCount, long durationNanos,
                           int width, int height, int rotation) {
            if (!finite(noiseX) || !finite(noiseY) || noiseX < MIN_NOISE || noiseX > MAX_NOISE
                || noiseY < MIN_NOISE || noiseY > MAX_NOISE || sampleCount < MIN_CALIBRATION_SAMPLES
                || sampleCount > MAX_CALIBRATION_SAMPLES || durationNanos < MIN_CALIBRATION_NANOS
                || width <= 0 || height <= 0 || rotation < 0 || rotation > 3)
                throw new IllegalArgumentException("Invalid noise calibration");
            this.noiseX = noiseX; this.noiseY = noiseY; this.sampleCount = sampleCount;
            this.durationNanos = durationNanos; this.width = width; this.height = height; this.rotation = rotation;
        }
        public boolean matchesGeometry(int width, int height, int rotation) {
            return this.width == width && this.height == height && this.rotation == rotation;
        }
    }

    public static final class CalibrationResult {
        public final boolean accepted;
        public final String reason;
        public final Calibration calibration;
        public final int sampleCount;
        public final long durationNanos;
        private CalibrationResult(String reason, Calibration calibration, int count, long duration) {
            this.accepted = calibration != null; this.reason = reason; this.calibration = calibration;
            this.sampleCount = count; this.durationNanos = duration;
        }
    }

    /** A single stationary contact hold. Feed raw samples, including phase transitions. */
    public static final class Calibrator {
        private final double[] x = new double[MAX_CALIBRATION_SAMPLES], y = new double[MAX_CALIBRATION_SAMPLES];
        private final int width, height, rotation;
        private int count, tool;
        private long firstTime, lastTime;
        private boolean contactEnded;
        private String failure;
        public Calibrator(int width, int height, int rotation) {
            if (width <= 0 || height <= 0 || rotation < 0 || rotation > 3)
                throw new IllegalArgumentException("Input surface is not ready");
            this.width = width; this.height = height; this.rotation = rotation;
        }
        public int sampleCount() { return count; }
        public long durationNanos() { return count < 2 ? 0 : lastTime - firstTime; }
        public void add(PenSample sample) {
            if (failure != null) return;
            if (sample.phase == PenSample.CANCEL) { failure = "contact-cancelled"; return; }
            if (sample.phase == PenSample.UP || sample.phase == PenSample.LEAVE || sample.phase == PenSample.HOVER) {
                if (count > 0) contactEnded = true;
                return;
            }
            if (sample.phase != PenSample.DOWN && sample.phase != PenSample.MOVE) return;
            if (count == 0 && sample.phase != PenSample.DOWN) return;
            if (contactEnded || (count > 0 && sample.phase == PenSample.DOWN)) {
                failure = "multiple-contacts"; return;
            }
            if (!finite(sample.x) || !finite(sample.y) || sample.x < 0 || sample.x > 1 || sample.y < 0 || sample.y > 1) {
                failure = "invalid-coordinate"; return;
            }
            if (count > 0 && (sample.tool != tool || sample.timeNanos < lastTime)) {
                failure = "input-changed"; return;
            }
            if (count == MAX_CALIBRATION_SAMPLES) return;
            if (count == 0) { firstTime = sample.timeNanos; tool = sample.tool; }
            lastTime = sample.timeNanos; x[count] = sample.x; y[count] = sample.y; count++;
        }
        public CalibrationResult finish() {
            if (failure != null) return result(failure, null);
            if (count < MIN_CALIBRATION_SAMPLES) return result("not-enough-samples", null);
            if (durationNanos() < MIN_CALIBRATION_NANOS) return result("hold-longer", null);
            double noiseX = noise(x, count), noiseY = noise(y, count);
            if (!stationary(x, count, noiseX) || !stationary(y, count, noiseY))
                return result("movement-detected", null);
            Calibration value = new Calibration((float) clamp(noiseX, MIN_NOISE, MAX_NOISE),
                (float) clamp(noiseY, MIN_NOISE, MAX_NOISE), count, durationNanos(), width, height, rotation);
            return result("ok", value);
        }
        private CalibrationResult result(String reason, Calibration calibration) {
            return new CalibrationResult(reason, calibration, count, durationNanos());
        }
        private static double noise(double[] values, int count) {
            double center = median(values, 0, count);
            double[] deviations = new double[count];
            for (int i = 0; i < count; i++) deviations[i] = Math.abs(values[i] - center);
            return 1.4826 * median(deviations, 0, count);
        }
        private static boolean stationary(double[] values, int count, double noise) {
            if (noise > MAX_NOISE) return false;
            double[] differences = new double[count - 1];
            for (int i = 1; i < count; i++) differences[i - 1] = Math.abs(values[i] - values[i - 1]);
            double localNoise = 1.04836 * median(differences, 0, differences.length);
            double minCenter = Double.POSITIVE_INFINITY, maxCenter = Double.NEGATIVE_INFINITY;
            for (int block = 0; block < 6; block++) {
                int start = block * count / 6, end = (block + 1) * count / 6;
                double center = median(values, start, end - start);
                minCenter = Math.min(minCenter, center); maxCenter = Math.max(maxCenter, center);
            }
            if (maxCenter - minCenter > Math.max(0.00075, 4 * localNoise)) return false;
            if (noise > Math.max(0.0003, 4 * localNoise)) return false;
            double[] sorted = Arrays.copyOf(values, count); Arrays.sort(sorted);
            return sorted[(count - 1) * 95 / 100] - sorted[(count - 1) * 5 / 100] <= 0.01;
        }
        private static double median(double[] values, int offset, int count) {
            double[] sorted = Arrays.copyOfRange(values, offset, offset + count); Arrays.sort(sorted);
            return count % 2 == 0 ? (sorted[count / 2 - 1] + sorted[count / 2]) / 2 : sorted[count / 2];
        }
    }
}
