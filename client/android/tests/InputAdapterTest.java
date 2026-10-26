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
        adapter.onEvent(event(MotionEvent.ACTION_UP, 1015, 0, .5f, 0), 1001, 1001);
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
        adapter.cancel();
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE, packedSample(2224, 150, 200, .7f, 19, 3))
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
    /** Shared input fixture; the TCP peer independently checks the serialized result. */
    public static void emitEraserFlipScenario(PenInput adapter) {
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_ENTER, 1000, .8f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE,
            new MotionEvent.Sample(1004, 110, 210, .4f), new MotionEvent.Sample(1008, 120, 220, .9f))
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_BUTTON_PRESS, 1012, .7f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER).withButtons(MotionEvent.BUTTON_STYLUS_PRIMARY), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_BUTTON_RELEASE, 1016, .7f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1020, .2f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        // Android may dispatch a delayed hover exit after contact has already begun.
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_EXIT, 1021, .8f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
            new MotionEvent.Sample(1024, 130, 230, .4f), new MotionEvent.Sample(1028, 140, 240, .6f))
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_BUTTON_PRESS, 1032, .7f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER).withButtons(MotionEvent.BUTTON_STYLUS_PRIMARY), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_BUTTON_RELEASE, 1036, .7f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_UP, 1040, .9f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1044, .8f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_EXIT, 1048, .8f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_ENTER, 1052, .7f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_BUTTON_PRESS, 1056, .6f, 0, 0)
            .withButtons(MotionEvent.BUTTON_STYLUS_SECONDARY), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_BUTTON_RELEASE, 1060, .6f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_EXIT, 1064, .6f, 0, 0), 1001, 1001);
    }
    private static void eraserHoverAndFlipNeverInventContact() {
        List<PenSample> samples = new ArrayList<>();
        emitEraserFlipScenario(new PenInput(samples::add));
        int[] phases = {0, 0, 0, 0, 0, 1, 2, 2, 2, 2, 3, 0, 5, 0, 0, 0, 5};
        float[] pressures = {0, 0, 0, 0, 0, .2f, .4f, .6f, .7f, .7f, 0, 0, 0, 0, 0, 0, 0};
        check(samples.size() == phases.length, "eraser flip preserves real contact and suppresses late hover exit");
        for (int i = 0; i < samples.size(); i++) {
            PenSample sample = samples.get(i);
            check(sample.phase == phases[i], "eraser flip phase at " + i);
            check(sample.tool == (i <= 12 ? PenSample.ERASER : PenSample.PEN), "eraser flip tool at " + i);
            check(sample.pressure == pressures[i], "non-contact raw pressure is discarded at " + i);
            check(sample.buttons == (i == 3 || i == 8 ? 1 : i == 14 ? 2 : 0), "buttons remain independent metadata at " + i);
            check(sample.sequence == i + 1 && sample.timeNanos == (1000L + i * 4) * 1000000L,
                "eraser history and lifecycle retain sequence/time at " + i);
        }
        samples.clear();
        PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 2000, .8f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 2004, .9f, 0, 0), 1001, 1001);
        check(samples.size() == 2 && samples.get(0).tool == PenSample.ERASER && samples.get(1).tool == PenSample.PEN
            && samples.get(0).phase == PenSample.HOVER && samples.get(1).phase == PenSample.HOVER
            && samples.get(0).pressure == 0 && samples.get(1).pressure == 0,
            "a hover tool flip without LEAVE never starts contact");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 2008, .5f, 0, 0)
            .withTool(MotionEvent.TOOL_TYPE_ERASER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 2012, .8f, 0, 0), 1001, 1001);
        check(samples.size() == 4 && samples.get(3).phase == PenSample.CANCEL
            && samples.get(3).tool == PenSample.ERASER && samples.get(3).pressure == 0,
            "an impossible tool flip during contact safely cancels the old eraser");
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 2016, .8f, 0, 0), 1001, 1001);
        check(samples.size() == 4, "tool flip cannot resume a contact without a real DOWN");
    }
    private static void contactIgnoresHoverAndForeignDevices() {
        List<PenSample> samples = new ArrayList<>(); PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1000, .5f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1001, 0, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_EXIT, 1002, 0, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1003, .9f, 0, 0).withDevice(new InputDevice(99, true)), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_CANCEL, 1003, 0, 0, 0).withDevice(new InputDevice(99, true)), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1004, 1, 0, 0).withTool(MotionEvent.TOOL_TYPE_FINGER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_CANCEL, 1004, 0, 0, 0).withTool(MotionEvent.TOOL_TYPE_FINGER), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1005, .6f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_UP, 1006, 0, 0, 0), 1001, 1001);
        check(samples.size() == 3 && samples.get(0).phase == PenSample.DOWN && samples.get(1).phase == PenSample.MOVE
            && samples.get(2).phase == PenSample.UP, "hover and other devices must not interrupt a contact stream");
        check(samples.get(1).pressure == .6f, "foreign same-ID pointer must not leak pressure or position");
    }
    private static MotionEvent pointers(int action, int actionIndex, long millis, boolean penFirst) {
        MotionEvent.Sample pen = new MotionEvent.Sample(millis, 250, 750, .5f);
        MotionEvent.Sample finger = new MotionEvent.Sample(millis, 990, 10, 1);
        return new MotionEvent(action, actionIndex, penFirst ? new int[]{7, 9} : new int[]{9, 7},
            penFirst ? new int[]{MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER}
                : new int[]{MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS},
            new MotionEvent.Sample[]{penFirst ? pen : finger}, new MotionEvent.Sample[]{penFirst ? finger : pen});
    }
    private static void pointerIndicesAndUncapturedTails() {
        List<PenSample> samples = new ArrayList<>(); PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(pointers(MotionEvent.ACTION_POINTER_DOWN, 1, 1000, true), 1001, 1001);
        check(samples.isEmpty(), "a finger pointer-down must not become a pen down");
        adapter.onEvent(pointers(MotionEvent.ACTION_POINTER_DOWN, 1, 1001, false), 1001, 1001);
        adapter.onEvent(pointers(MotionEvent.ACTION_MOVE, 0, 1002, true), 1001, 1001);
        adapter.onEvent(pointers(MotionEvent.ACTION_POINTER_UP, 1, 1003, true), 1001, 1001);
        adapter.onEvent(pointers(MotionEvent.ACTION_MOVE, 0, 1004, false), 1001, 1001);
        adapter.onEvent(pointers(MotionEvent.ACTION_POINTER_UP, 1, 1005, false), 1001, 1001);
        check(samples.size() == 4, "foreign pointer-up is ignored and pen UP is preserved");
        for (PenSample sample : samples) check(sample.x == .25f && sample.y == .75f, "pointer ID survives index reordering");
        check(samples.get(0).phase == PenSample.DOWN && samples.get(3).phase == PenSample.UP, "correct pointer owns boundaries");
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1010, .8f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_UP, 1011, 0, 0, 0), 1001, 1001);
        check(samples.size() == 4, "uncaptured touch MOVE/UP is not converted to hover");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1012, .5f, 0, 0), 1001, 1001); adapter.cancel();
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1013, .8f, 0, 0), 1001, 1001);
        check(samples.size() == 6 && samples.get(5).phase == PenSample.CANCEL, "cancelled stroke tail cannot move the pointer");
    }
    private static void geometryAndInvalidPositions() {
        List<PenSample> samples = new ArrayList<>(); PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1000, .5f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1004, .5f, 0, 0), 2001, 2001);
        adapter.onEvent(event(MotionEvent.ACTION_UP, 1008, 0, 0, 0), 2001, 2001);
        for (PenSample sample : samples) check(sample.x == .1f && sample.y == .2f, "normalization geometry is frozen until UP");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1012, .5f, 0, 0), 2001, 2001);
        check(samples.get(3).x == .05f && samples.get(3).y == .1f, "next stroke adopts new geometry");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_MOVE,
            new MotionEvent.Sample(1000, 1900, 1900, .5f), new MotionEvent.Sample(1014, Float.NaN, 200, .5f),
            new MotionEvent.Sample(1016, 100, 200, .6f)), 2001, 2001);
        check(samples.size() == 5 && samples.get(4).x == .05f, "stale/NaN history cannot create edge jumps");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_UP, new MotionEvent.Sample(1020, Float.POSITIVE_INFINITY, 200, 0)), 2001, 2001);
        check(samples.size() == 6 && samples.get(5).phase == PenSample.CANCEL && samples.get(5).x == .05f,
            "invalid UP releases at last valid position");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1024, .5f, 0, 0), 1, 1);
        check(samples.size() == 6, "unlaid-out surface cannot map to desktop corners");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1028, .5f, 0, 0), 1001, 1001, 0);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1032, .5f, 0, 0), 1001, 1001, 1);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1036, .5f, 0, 0), 1001, 1001, 1);
        check(samples.size() == 8 && samples.get(7).phase == PenSample.CANCEL, "rotation cancels once and suppresses old stroke tail");
    }
    private static void invalidHoverExitReleasesButtonsAndTimestampOverflow() {
        List<PenSample> samples = new ArrayList<>(); PenInput adapter = new PenInput(samples::add);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.BUTTON_STYLUS_PRIMARY, 0, 0, 0), 1001, 1001);
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_EXIT,
            new MotionEvent.Sample(1240, Float.NaN, 200, 0)), 1001, 1001);
        check(samples.size() == 2 && samples.get(1).phase == PenSample.CANCEL && samples.get(1).buttons == 0,
            "invalid hover exit releases held barrel buttons at the previous valid position");
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_MOVE, 1250, 0, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_HOVER_EXIT, 1200, 0, 0, 0), 1001, 1001);
        check(samples.size() == 4 && samples.get(3).phase == PenSample.CANCEL && samples.get(3).timeNanos == 1250000000L,
            "stale hover exit still releases safely without rewinding coordinates or time");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, Long.MAX_VALUE, .5f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1254, .5f, 0, 0), 1001, 1001);
        check(samples.size() == 4, "overflowing event time cannot begin a poisoned stroke");
        adapter.onEvent(event(MotionEvent.ACTION_DOWN, 1260, .5f, 0, 0), 1001, 1001);
        adapter.onEvent(event(MotionEvent.ACTION_MOVE, 1264, .5f, 0, 0), 1001, 1001, -1);
        check(samples.size() == 6 && samples.get(5).phase == PenSample.CANCEL, "invalid surface rotation releases existing contact");
    }
    public static void main(String[] args) {
        batchedStrokePreservesBeginning(); cancellationAndButtons(); unreliableTiltNeedsConsecutiveValidSamples();
        tiltHistoryAndContactOnlyDevices(); packedTiltRecoveryPreservesInputAndControls(); eraserAndButtonsAreDistinct();
        eraserHoverAndFlipNeverInventContact();
        contactIgnoresHoverAndForeignDevices(); pointerIndicesAndUncapturedTails(); geometryAndInvalidPositions();
        invalidHoverExitReleasesButtonsAndTimestampOverflow();
        System.out.println("Android input adapter history, pointer isolation, geometry, invalid coordinates, tilt and release tests passed");
    }
}
