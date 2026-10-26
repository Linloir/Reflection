package android.view;

/** Single-pointer event fixture for running the production adapter on a host JVM. */
public final class MotionEvent {
    public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3,
        ACTION_POINTER_DOWN=5, ACTION_POINTER_UP=6, ACTION_HOVER_MOVE=7,
        ACTION_HOVER_ENTER=9, ACTION_HOVER_EXIT=10, ACTION_BUTTON_PRESS=11, ACTION_BUTTON_RELEASE=12;
    public static final int TOOL_TYPE_FINGER=1, TOOL_TYPE_STYLUS=2, TOOL_TYPE_ERASER=4, BUTTON_STYLUS_PRIMARY=32, BUTTON_STYLUS_SECONDARY=64, BUTTON_SECONDARY=2, BUTTON_TERTIARY=4;
    public static final int AXIS_PRESSURE=2, AXIS_ORIENTATION=8, AXIS_DISTANCE=24, AXIS_TILT=25;
    private final int action;
    private int buttons;
    private int actionIndex;
    private int[] pointerIds = {0}, tools = {TOOL_TYPE_STYLUS};
    private InputDevice device = new InputDevice();
    private final Sample[][] samples;
    public static final class Sample {
        final long millis;
        final float x, y, pressure, tilt, orientation;
        public Sample(long millis, float x, float y, float pressure) {
            this(millis, x, y, pressure, 0, 0);
        }
        public Sample(long millis, float x, float y, float pressure, float tilt, float orientation) {
            this.millis=millis; this.x=x; this.y=y; this.pressure=pressure; this.tilt=tilt; this.orientation=orientation;
        }
    }
    public MotionEvent(int action, int buttons, float pressure, float tilt, float orientation) {
        this.action=action; this.buttons=buttons;
        samples=new Sample[][]{{new Sample(1234, 100, 200, pressure, tilt, orientation)}};
    }
    /** Samples are chronological; the final sample is current, earlier samples are history. */
    public MotionEvent(int action, Sample... samples) {
        if (samples.length == 0) throw new IllegalArgumentException("At least one sample is required");
        this.action=action; buttons=0; this.samples=new Sample[][]{samples};
    }
    public MotionEvent(int action, int actionIndex, int[] ids, int[] tools, Sample[]... pointerSamples) {
        if (ids.length != tools.length || ids.length != pointerSamples.length || ids.length == 0)
            throw new IllegalArgumentException("Pointer arrays must match");
        this.action = action; this.actionIndex = actionIndex; buttons = 0;
        pointerIds = ids; this.tools = tools; samples = pointerSamples;
        for (Sample[] history : samples) if (history.length != samples[0].length || history.length == 0)
            throw new IllegalArgumentException("History lengths must match");
    }
    public MotionEvent withTool(int tool) { tools[0] = tool; return this; }
    public MotionEvent withButtons(int value) { buttons = value; return this; }
    public MotionEvent withDevice(InputDevice value) { device = value; return this; }
    private Sample current(int index) { return samples[index][samples[index].length - 1]; }
    public int getActionMasked() { return action; }
    public int getActionIndex() { return actionIndex; }
    public int findPointerIndex(int id) { for (int i = 0; i < pointerIds.length; i++) if (pointerIds[i] == id) return i; return -1; }
    public int getPointerId(int index) { return pointerIds[index]; }
    public int getPointerCount() { return pointerIds.length; }
    public int getToolType(int index) { return tools[index]; }
    public int getHistorySize() { return samples[0].length - 1; }
    public InputDevice getDevice() { return device; }
    public int getDeviceId() { return device == null ? -1 : device.getId(); }
    public int getSource() { return 0x4002; }
    public float getX(int index) { return current(index).x; }
    public float getY(int index) { return current(index).y; }
    public float getHistoricalX(int index, int history) { return samples[index][history].x; }
    public float getHistoricalY(int index, int history) { return samples[index][history].y; }
    public float getAxisValue(int axis, int index) {
        if (axis == AXIS_PRESSURE) return current(index).pressure;
        if (axis == AXIS_TILT) return current(index).tilt;
        if (axis == AXIS_ORIENTATION) return current(index).orientation;
        return 0;
    }
    public float getHistoricalAxisValue(int axis, int index, int history) {
        if (axis == AXIS_PRESSURE) return samples[index][history].pressure;
        if (axis == AXIS_TILT) return samples[index][history].tilt;
        if (axis == AXIS_ORIENTATION) return samples[index][history].orientation;
        return 0;
    }
    public int getButtonState() { return buttons; }
    public long getEventTime() { return current(0).millis; }
    public long getHistoricalEventTime(int history) { return samples[0][history].millis; }
}
