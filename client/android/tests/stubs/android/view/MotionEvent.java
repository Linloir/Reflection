package android.view;

/** Single-pointer event fixture for running the production adapter on a host JVM. */
public final class MotionEvent {
    public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3,
        ACTION_POINTER_DOWN=5, ACTION_POINTER_UP=6, ACTION_HOVER_MOVE=7,
        ACTION_HOVER_ENTER=9, ACTION_HOVER_EXIT=10, ACTION_BUTTON_PRESS=11, ACTION_BUTTON_RELEASE=12;
    public static final int TOOL_TYPE_STYLUS=2, TOOL_TYPE_ERASER=4, BUTTON_STYLUS_PRIMARY=32, BUTTON_STYLUS_SECONDARY=64, BUTTON_SECONDARY=2, BUTTON_TERTIARY=4;
    public static final int AXIS_PRESSURE=2, AXIS_ORIENTATION=8, AXIS_DISTANCE=24, AXIS_TILT=25;
    private final int action, buttons;
    private final float tilt, orientation;
    private final Sample[] samples;
    public static final class Sample {
        final long millis;
        final float x, y, pressure;
        public Sample(long millis, float x, float y, float pressure) {
            this.millis=millis; this.x=x; this.y=y; this.pressure=pressure;
        }
    }
    public MotionEvent(int action, int buttons, float pressure, float tilt, float orientation) {
        this.action=action; this.buttons=buttons; this.tilt=tilt; this.orientation=orientation;
        samples=new Sample[]{new Sample(1234, 100, 200, pressure)};
    }
    /** Samples are chronological; the final sample is current, earlier samples are history. */
    public MotionEvent(int action, Sample... samples) {
        if (samples.length == 0) throw new IllegalArgumentException("At least one sample is required");
        this.action=action; buttons=0; tilt=0; orientation=0; this.samples=samples;
    }
    private Sample current() { return samples[samples.length - 1]; }
    public int getActionMasked() { return action; }
    public int getActionIndex() { return 0; }
    public int findPointerIndex(int id) { return id == 0 ? 0 : -1; }
    public int getPointerId(int index) { return 0; }
    public int getPointerCount() { return 1; }
    public int getToolType(int index) { return TOOL_TYPE_STYLUS; }
    public int getHistorySize() { return samples.length - 1; }
    public InputDevice getDevice() { return new InputDevice(); }
    public int getSource() { return 0x4002; }
    public float getX(int index) { return current().x; }
    public float getY(int index) { return current().y; }
    public float getHistoricalX(int index, int history) { return samples[history].x; }
    public float getHistoricalY(int index, int history) { return samples[history].y; }
    public float getAxisValue(int axis, int index) {
        if (axis == AXIS_PRESSURE) return current().pressure;
        if (axis == AXIS_TILT) return tilt;
        if (axis == AXIS_ORIENTATION) return orientation;
        return 0;
    }
    public float getHistoricalAxisValue(int axis, int index, int history) {
        return axis == AXIS_PRESSURE ? samples[history].pressure : getAxisValue(axis,index);
    }
    public int getButtonState() { return buttons; }
    public long getEventTime() { return current().millis; }
    public long getHistoricalEventTime(int history) { return samples[history].millis; }
}
