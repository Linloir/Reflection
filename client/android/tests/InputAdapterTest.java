import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.input.PenInput;
import java.util.ArrayList;
import java.util.List;

public final class InputAdapterTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static MotionEvent event(int action, long millis, float pressure, float tilt, float orientation) {
        return new MotionEvent(action, new MotionEvent.Sample(millis, 100, 200, pressure, tilt, orientation));
    }
    private static MotionEvent.Sample packedSample(long millis, float x, float y, float pressure, int degrees, int rotation) {
        int packed = (degrees * 100) & 0xffff;
        double rawX = Math.toRadians(packed & 255), rawY = Math.toRadians(packed >>> 8);
        double tilt = Math.acos(Math.cos(rawX) * Math.cos(rawY));
        double orientation = Math.atan2(-Math.sin(rawX), Math.sin(rawY));
        orientation += new double[]{0, -Math.PI / 2, -Math.PI, Math.PI / 2}[rotation];
        while (orientation > Math.PI) orientation -= 2 * Math.PI;
        while (orientation < -Math.PI) orientation += 2 * Math.PI;
        return new MotionEvent.Sample(millis, x, y, pressure, (float) tilt, (float) orientation);
    }
    private static InputDevice packedDevice() {
        return new InputDevice(7, true, "onyx_emp_Wacom I2C Digitizer", 11551, 116);
    }
    private static void batchedStrokePreservesBeginning() {
        List<PenSample> samples = new ArrayList<>();
        PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_DOWN,
            new MotionEvent.Sample(1000, 100, 100, 0)), 1001, 1001);
        check(samples.size() == 1 && samples.get(0).phase == PenSample.DOWN,
            "zero-pressure DOWN is emitted before any MOVE");
        // A rapid W can arrive as one MOVE with the first three vertices in history.
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
            new MotionEvent.Sample(1004, 200, 900, 0.2f),
            new MotionEvent.Sample(1008, 400, 300, 0.3f),
            new MotionEvent.Sample(1012, 600, 900, 0.4f),
            new MotionEvent.Sample(1016, 800, 100, 0.5f)), 1001, 1001);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_UP,
            new MotionEvent.Sample(1020, 800, 100, 0)), 1001, 1001);
        check(samples.size() == 6, "rapid W retains DOWN, every historical MOVE, current MOVE and UP");
        int[] phases = {PenSample.DOWN, PenSample.MOVE, PenSample.MOVE, PenSample.MOVE, PenSample.MOVE, PenSample.UP};
        float[] x = {0.1f, 0.2f, 0.4f, 0.6f, 0.8f, 0.8f};
        float[] y = {0.1f, 0.9f, 0.3f, 0.9f, 0.1f, 0.1f};
        float[] pressure = {0, 0.2f, 0.3f, 0.4f, 0.5f, 0};
        for (int i = 0; i < samples.size(); i++) {
            PenSample sample = samples.get(i);
            check(sample.phase == phases[i] && sample.sequence == i + 1, "rapid W phase/sequence at " + i);
            check(Math.abs(sample.x - x[i]) < 0.00001f && Math.abs(sample.y - y[i]) < 0.00001f,
                "rapid W position at " + i);
            check(Math.abs(sample.pressure - pressure[i]) < 0.00001f, "rapid W pressure at " + i);
            check(sample.timeNanos == (1000L + i * 4) * 1000000L, "rapid W timestamp at " + i);
        }
    }
    private static void cancellationAndButtons() {
        List<PenSample> samples = new ArrayList<>();
        PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.BUTTON_STYLUS_PRIMARY, 0, 0, 0), 1001, 1001);
        check(samples.get(0).buttons == 1 && samples.get(0).phase == PenSample.HOVER, "hover barrel press captured");
        adapter.cancel();
        PenSample cancelled = samples.get(1);
        check(cancelled.phase == PenSample.CANCEL && cancelled.buttons == 0 && cancelled.pressure == 0, "backgrounding releases hover barrel button");
        adapter.cancel();
        check(samples.size() == 2, "cancellation is idempotent");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_STYLUS_PRIMARY, 0.5f, 0, 0), 1001, 1001);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_UP, MotionEvent.BUTTON_STYLUS_PRIMARY, 0, 0, 0), 1001, 1001);
        adapter.cancel();
        check(samples.get(samples.size()-1).phase == PenSample.CANCEL, "backgrounding after tip-up releases retained barrel button");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY, 0, 0, 0), 1001, 1001);
        check(samples.get(samples.size()-1).buttons == 3, "Android 5.x legacy stylus buttons are retained");
    }
    private static void unreliableTiltNeedsConsecutiveValidSamples() {
        List<PenSample> samples = new ArrayList<>();
        PenInput adapter = new PenInput(samples::add);
        long before = PenInput.invalidTiltSamples();
        long suppressedBefore = PenInput.suppressedTiltSamples();
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1000, 0, 2.8f, 1), 1001, 1001);
        PenSample invalid = samples.get(samples.size()-1);
        check(invalid.tiltX == 0 && invalid.tiltY == 0 && (invalid.capabilities & 2) == 0, "invalid vendor tilt is disabled");
        check(PenInput.invalidTiltSamples() == before + 1 && !PenInput.tiltReliable(), "invalid tilt is observable");
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1004, 0, (float)Math.PI/4, (float)Math.PI/2), 1001, 1001);
        check(samples.get(samples.size()-1).tiltX == 0 && !PenInput.tiltReliable(), "a plausible sample must not undo suppression and flicker shading");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1008, .5f, (float)Math.PI/4, (float)Math.PI/2), 1001, 1001);
        check(samples.get(samples.size()-1).tiltX == 0 && samples.get(samples.size()-1).pressure == .5f, "tilt failure does not suppress pressure or contact");
        check(PenInput.tiltStatus().equals("standard-recovering"), "recovery reason is observable");
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1012, .5f, (float)Math.PI/4, (float)Math.PI/2), 1001, 1001);
        PenSample valid = samples.get(samples.size()-1);
        check(Math.abs(valid.tiltX - 45) < 0.001 && Math.abs(valid.tiltY) < 0.001 && PenInput.tiltReliable(),
            "three consecutive valid samples recover standard tilt without waiting for pen leave");
        check(PenInput.suppressedTiltSamples() == suppressedBefore + 3, "recovered samples are not counted as suppressed");
        check(PenInput.tiltAxes() == 2 && PenInput.tiltInputRotation() == -1, "standard devices report both axes without packed inference");
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1016, 0, Float.NaN, 1), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1020, 0, .5f, 0).withDevice(new InputDevice(2, true)), 1001, 1001);
        check(PenInput.tiltReliable(), "another digitizer does not inherit suppression");
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1024, 0, .5f, 0).withDevice(new InputDevice(3, false)), 1001, 1001);
        check(!PenInput.tiltReliable() && PenInput.tiltStatus().equals("unavailable"), "missing tilt support is distinct from malformed values");
        check(PenInput.lastDeviceId() == 3 && PenInput.lastDeviceName().equals("Test digitizer")
            && PenInput.lastDeviceVendor() == 10 && PenInput.lastDeviceProduct() == 20, "device diagnostics identify the input source");
    }
    private static void tiltHistoryAndContactOnlyDevices() {
        List<PenSample> samples = new ArrayList<>();
        PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1236, .5f, .5f, 0), 1001, 1001);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
            new MotionEvent.Sample(1240, 100, 200, .5f, 2.8f, 0),
            new MotionEvent.Sample(1244, 200, 300, .5f, .5f, 0)), 1001, 1001);
        check(samples.get(1).tiltY == 0 && samples.get(2).tiltY == 0, "an invalid historical sample suppresses the rest of its batch");
        adapter.onEvent(event(MotionEvent.ACTION_UP, 1248, 0, .5f, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1252, .5f, .5f, 0), 1001, 1001);
        check(PenInput.tiltReliable() && samples.get(samples.size()-1).tiltY < 0, "contact-only devices recover after three valid samples");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
            new MotionEvent.Sample(1256, 300, 400, .5f, .25f, 0),
            new MotionEvent.Sample(1260, 400, 500, .5f, .5f, 0)), 1001, 1001);
        check(Math.abs(samples.get(samples.size()-2).tiltY - Math.toDegrees(-.25)) < .001
            && Math.abs(samples.get(samples.size()-1).tiltY - Math.toDegrees(-.5)) < .001,
            "valid historical samples retain their own continuous tilt, not the current angle");
    }
    private static void packedTiltRecoveryPreservesInputAndControls() {
        List<PenSample> samples = new ArrayList<>();
        PenInput adapter = new PenInput(samples::add);
        long recoveredBefore = PenInput.recoveredTiltSamples();
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_DOWN, packedSample(2000, 100, 100, .2f, 12, 3))
            .withDevice(packedDevice()), 1001, 1001, 0);
        for (int n = 1; n <= 50; n++) {
            adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
                packedSample(2000 + n * 4, 100 + n, 100 + n * 2, .5f, n < 3 ? 12 + n * 2 : 16, 3))
                .withDevice(packedDevice()), 1001, 1001, 0);
        }
        PenSample recovered = samples.get(samples.size() - 1);
        check(samples.size() == 51 && samples.get(0).phase == PenSample.DOWN
            && recovered.phase == PenSample.MOVE && recovered.sequence == 51, "packed detection preserves every contact sample");
        check(recovered.x == .15f && recovered.y == .2f && recovered.pressure == .5f,
            "tilt recovery does not change coordinates or pressure");
        check(PenInput.tiltReliable() && PenInput.tiltAxes() == 1 && PenInput.tiltInputRotation() == 3,
            "known digitizer gains one validated recovered axis");
        check(recovered.tiltX == 0 && Math.abs(recovered.tiltY - 16) < .1f
            && Math.abs(PenInput.recoveredTiltDegrees() - 16) < .1f, "physical Note2 recovery maps positive angle toward screen bottom");
        check(PenInput.recoveredTiltSamples() > recoveredBefore, "recovery has a separate diagnostic counter");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
            packedSample(2204, 200, 300, .3f, 17, 3),
            packedSample(2208, 300, 400, .4f, 18, 3),
            packedSample(2212, 400, 500, .6f, 19, 3)).withDevice(packedDevice()), 1001, 1001, 0);
        check(samples.size() == 54, "recovered firmware samples retain every historical point");
        for (int n = 0; n < 3; n++) {
            PenSample historical = samples.get(51 + n);
            check(historical.phase == PenSample.MOVE && historical.timeNanos == (2204L + n * 4) * 1000000L
                && historical.tiltX == 0 && historical.tiltY > 0,
                "packed historical sample retains its timestamp, phase and available axis");
        }
        check(samples.get(51).pressure == .3f && samples.get(52).pressure == .4f && samples.get(53).pressure == .6f,
            "packed historical samples retain their individual pressure");
        long totalRecovered = PenInput.recoveredTiltSamples();
        adapter.cancel();
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_ENTER, packedSample(2216, 150, 200, 0, 19, 3))
            .withDevice(packedDevice()), 1001, 1001, 0);
        check(PenInput.tiltReliable() && PenInput.tiltInputRotation() == 3,
            "leaving contact or backgrounding preserves validated device decoding");
        adapter.setTiltMode("off");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_DOWN, packedSample(2220, 150, 200, .7f, 19, 3))
            .withDevice(packedDevice()), 1001, 1001, 0);
        PenSample off = samples.get(samples.size() - 1);
        check(off.tiltX == 0 && off.tiltY == 0 && off.pressure == .7f && (off.capabilities & 2) == 0
            && PenInput.tiltStatus().equals("off"), "off mode changes only tilt");
        adapter.setTiltMode("auto");
        adapter.resetTiltDetection();
        check(PenInput.tiltInputRotation() == -1 && !PenInput.tiltReliable(), "retry clears learned rotation and output capability");
        check(PenInput.recoveredTiltSamples() >= totalRecovered, "retry preserves lifetime diagnostic counters");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE, packedSample(2224, 150, 200, .7f, 19, 3))
            .withDevice(new InputDevice(8, true, "onyx_emp_Wacom I2C Digitizer", 11551, 117)), 1001, 1001, 0);
        check(!PenInput.tiltStatus().startsWith("packed"), "a different product cannot enter firmware-specific decoding");
    }
    private static void eraserAndButtonsAreDistinct() {
        List<PenSample> samples = new ArrayList<>();
        PenInput adapter = new PenInput(samples::add);
        long eraserBefore = PenInput.eraserSamples();
        long buttonBefore = PenInput.barrelButtonSamples();
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_DOWN, 0, .7f, .2f, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        check(samples.get(0).tool == PenSample.ERASER && samples.get(0).pressure == .7f, "hardware eraser retains tool and pressure");
        check(PenInput.eraserSamples() == eraserBefore + 1 && PenInput.lastRawToolType() == MotionEvent.TOOL_TYPE_ERASER,
            "raw eraser recognition is visible independently of host application behavior");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_UP, 0, 0, 0, 0).withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_STYLUS_PRIMARY, .5f, 0, 0), 1001, 1001);
        check(samples.get(2).tool == PenSample.PEN && samples.get(2).buttons == 1, "barrel button does not pretend to be an eraser");
        check(PenInput.lastRawButtons() == MotionEvent.BUTTON_STYLUS_PRIMARY && PenInput.barrelButtonSamples() == buttonBefore + 1,
            "button diagnostics distinguish raw eraser and barrel switch");
    }
    public static void main(String[] args) {
        batchedStrokePreservesBeginning(); cancellationAndButtons(); unreliableTiltNeedsConsecutiveValidSamples();
        tiltHistoryAndContactOnlyDevices(); packedTiltRecoveryPreservesInputAndControls(); eraserAndButtonsAreDistinct();
        System.out.println("Android input adapter history, release, tilt recovery and eraser diagnostics tests passed");
    }
}
