package android.view;

/** Minimal host-test fixture, never included in the APK. */
public final class InputDevice {
    private final int id;
    private final boolean tiltSupported;
    private final String name;
    private final int vendorId, productId;
    public InputDevice() { this(1, true); }
    public InputDevice(int id, boolean tiltSupported) {
        this(id, tiltSupported, "Test digitizer", 10, 20);
    }
    public InputDevice(int id, boolean tiltSupported, String name, int vendorId, int productId) {
        this.id = id; this.tiltSupported = tiltSupported; this.name = name;
        this.vendorId = vendorId; this.productId = productId;
    }
    public int getId() { return id; }
    public String getName() { return name; }
    public int getVendorId() { return vendorId; }
    public int getProductId() { return productId; }
    public MotionRange getMotionRange(int axis, int source) {
        if (axis == MotionEvent.AXIS_PRESSURE || axis == MotionEvent.AXIS_DISTANCE) return new MotionRange(0, 1);
        if (axis == MotionEvent.AXIS_TILT && tiltSupported) return new MotionRange(0, (float) (Math.PI / 2));
        if (axis == MotionEvent.AXIS_ORIENTATION && tiltSupported) return new MotionRange(-(float) Math.PI, (float) Math.PI);
        return null;
    }
    public static final class MotionRange {
        private final float min, max;
        public MotionRange(float min, float max) { this.min = min; this.max = max; }
        public float getMin() { return min; }
        public float getRange() { return max - min; }
    }
}
