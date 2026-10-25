import android.view.MotionEvent;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.input.PenInput;
import java.util.ArrayList;
import java.util.List;

public final class InputAdapterTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
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
    public static void main(String[] args) {
        batchedStrokePreservesBeginning();
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
        long before = PenInput.invalidTiltSamples();
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE, 0, 0, 2.8f, 1), 1001, 1001);
        PenSample invalid = samples.get(samples.size()-1);
        check(invalid.tiltX == 0 && invalid.tiltY == 0 && (invalid.capabilities & 2) == 0, "invalid vendor tilt is disabled");
        check(PenInput.invalidTiltSamples() == before + 1 && !PenInput.tiltReliable(), "invalid tilt is observable");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE, 0, 0, (float)Math.PI/4, (float)Math.PI/2), 1001, 1001);
        PenSample valid = samples.get(samples.size()-1);
        check(Math.abs(valid.tiltX - 45) < 0.001 && Math.abs(valid.tiltY) < 0.001 && PenInput.tiltReliable(), "valid tilt recovers after invalid sample");
        adapter.onEvent(new MotionEvent(MotionEvent.ACTION_HOVER_MOVE, MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY, 0, 0, 0), 1001, 1001);
        check(samples.get(samples.size()-1).buttons == 3, "Android 5.x legacy stylus buttons are retained");
        System.out.println("Android input adapter batched-history, release, legacy-button and vendor-tilt regression tests passed");
    }
}
