package io.github.linloir.reflection.core;

/** Matches one guided direction only after a stable neutral and a deliberate two-sided swing. */
public final class TiltMatcher {
    public enum Stage { UNAVAILABLE, CENTERING, NEUTRAL, SWINGING, COMPLETE }
    private static final long NEUTRAL_NANOS = 200000000L, SWING_NANOS = 1600000000L;
    private final Axis[] axes = {new Axis(), new Axis()};
    private final float[] baseline = new float[2], low = new float[2], high = new float[2];
    private Stage stage = Stage.UNAVAILABLE;
    private long lastTime = -1, neutralStart, swingStart;
    private int neutralSamples, swingSamples, selected = -1;
    private float progress;

    public Stage stage() { return stage; }
    public boolean complete() { return stage == Stage.COMPLETE; }
    public int axis() { return complete() ? selected : -1; }
    public int sign() { return complete() ? axes[selected].firstSign : 0; }
    public float progress() { return progress; }
    public boolean hasData() { return stage == Stage.SWINGING && swingSamples > 0 || complete(); }
    public float range() { return Math.max(axes[0].range(), axes[1].range()); }

    public void reset() {
        clearMeasurement();
        lastTime = -1;
    }

    private void clearMeasurement() {
        stage = Stage.UNAVAILABLE; neutralSamples = swingSamples = 0; selected = -1; progress = 0;
        axes[0].reset(); axes[1].reset();
    }

    public void sample(float x, float y, long timeNanos, boolean reliable) {
        if (complete() || timeNanos < 0 || timeNanos <= lastTime) return;
        lastTime = timeNanos;
        if (!reliable || !finite(x) || !finite(y) || Math.abs(x) > 90 || Math.abs(y) > 90) {
            clearMeasurement(); return;
        }
        if (stage != Stage.SWINGING) {
            if (Math.hypot(x, y) > 12) {
                neutralSamples = 0; stage = Stage.CENTERING; progress = 0; return;
            }
            float[] values = {x, y};
            boolean unstable = neutralSamples > 0 && (Math.max(high[0], x) - Math.min(low[0], x) > 3
                || Math.max(high[1], y) - Math.min(low[1], y) > 3);
            if (unstable) neutralSamples = 0;
            if (neutralSamples == 0) {
                neutralStart = timeNanos;
                low[0] = high[0] = x; low[1] = high[1] = y;
                baseline[0] = baseline[1] = 0;
            }
            for (int i = 0; i < 2; i++) {
                low[i] = Math.min(low[i], values[i]); high[i] = Math.max(high[i], values[i]);
                baseline[i] += (values[i] - baseline[i]) / (neutralSamples + 1);
            }
            neutralSamples++; stage = Stage.NEUTRAL;
            progress = .2f * Math.min(1, Math.min(neutralSamples / 8f, (timeNanos - neutralStart) / (float) NEUTRAL_NANOS));
            if (neutralSamples >= 8 && timeNanos - neutralStart >= NEUTRAL_NANOS) {
                stage = Stage.SWINGING; swingStart = timeNanos;
            }
            return;
        }
        swingSamples++;
        axes[0].sample(x - baseline[0], timeNanos); axes[1].sample(y - baseline[1], timeNanos);
        int candidate = axes[0].range() > axes[1].range() ? 0 : 1;
        Axis main = axes[candidate], other = axes[1 - candidate];
        float fraction = Math.min(1, Math.min(main.range() / 24f, Math.min(main.positive / 8, main.negative / 8)));
        fraction = Math.min(fraction, Math.min(swingSamples / 40f, (timeNanos - swingStart) / (float) SWING_NANOS));
        progress = Math.min(.95f, .2f + .8f * fraction);
        if (main.positive >= 8 && main.negative >= 8 && main.range() >= 24
            && main.range() > Math.max(other.positive, other.negative) * 2.8f && main.firstSign != 0
            && swingSamples >= 40 && timeNanos - swingStart >= SWING_NANOS) {
            selected = candidate; stage = Stage.COMPLETE; progress = 1;
        }
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static final class Axis {
        private int firstSign, direction, count;
        private long since;
        private float positive, negative;
        void reset() { firstSign = direction = count = 0; positive = negative = 0; }
        float range() { return positive + negative; }
        void sample(float delta, long time) {
            int next = delta >= 8 ? 1 : delta <= -8 ? -1 : 0;
            if (next == 0) { direction = count = 0; return; }
            if (next != direction) { direction = next; count = 0; since = time; }
            count++;
            // A single decoder spike cannot choose the sign or satisfy either side.
            if (count < 3 || time - since < 40000000L) return;
            if (firstSign == 0) firstSign = next;
            if (next > 0) positive = Math.max(positive, delta);
            else negative = Math.max(negative, -delta);
        }
    }
}
