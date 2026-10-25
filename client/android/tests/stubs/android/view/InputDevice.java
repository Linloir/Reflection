package android.view;

/** Minimal host-test fixture, never included in the APK. */
public final class InputDevice {
    public MotionRange getMotionRange(int axis, int source) {
        if (axis == MotionEvent.AXIS_PRESSURE || axis == MotionEvent.AXIS_DISTANCE) return new MotionRange(0, 1);
        if (axis == MotionEvent.AXIS_TILT) return new MotionRange(0, (float) (Math.PI / 2));
        if (axis == MotionEvent.AXIS_ORIENTATION) return new MotionRange(-(float) Math.PI, (float) Math.PI);
        return null;
    }
    public static final class MotionRange {
        private final float min, max;
        public MotionRange(float min, float max) { this.min = min; this.max = max; }
        public float getMin() { return min; }
        public float getRange() { return max - min; }
    }
}
