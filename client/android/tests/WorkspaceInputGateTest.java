import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.input.PenInput;
import io.github.linloir.reflection.input.WorkspaceInputGate;
import java.util.ArrayList;
import java.util.List;

public final class WorkspaceInputGateTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static MotionEvent event(int action, long time, int device, int pointer, float x) {
        return new MotionEvent(action, 0, new int[]{pointer}, new int[]{MotionEvent.TOOL_TYPE_STYLUS},
            new MotionEvent.Sample[]{new MotionEvent.Sample(time, x, 100, .5f)}).withDevice(new InputDevice(device, true));
    }
    private static final class Workspace {
        final List<PenSample> samples = new ArrayList<>();
        final PenInput input = new PenInput(samples::add);
        boolean contact;
        int device = -1, pointer = -1, boundsCancels, ignored;
        void dispatch(MotionEvent event) {
            int index = 0;
            for (int i = 0; i < event.getPointerCount(); i++)
                if (event.getPointerId(i) == pointer) index = i;
            if (WorkspaceInputGate.consumes(event, index, contact, device, pointer)) { ignored++; return; }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                contact = true; device = event.getDeviceId(); pointer = event.getPointerId(index);
            }
            // InkView checks the active rectangle before forwarding to PenInput.
            if (event.getX(event.getActionIndex()) < 0 && (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_MOVE)) {
                boundsCancels++; input.cancel(); return;
            }
            input.onEvent(event, 1001, 1001);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL)
                contact = false;
        }
    }
    public static void main(String[] args) {
        Workspace workspace = new Workspace();
        workspace.dispatch(event(MotionEvent.ACTION_DOWN, 1000, 7, 3, 100));
        workspace.dispatch(event(MotionEvent.ACTION_DOWN, 1001, 8, 3, -20));
        workspace.dispatch(event(MotionEvent.ACTION_DOWN, 1002, 7, 9, -20));
        for (int action : new int[]{MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT})
            workspace.dispatch(event(action, 1003 + action, 7, 3, -20));
        for (int action : new int[]{MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP})
            workspace.dispatch(new MotionEvent(action, 1, new int[]{3, 9},
                new int[]{MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS},
                new MotionEvent.Sample[]{new MotionEvent.Sample(1020 + action, 100, 100, .5f)},
                new MotionEvent.Sample[]{new MotionEvent.Sample(1020 + action, -20, 100, .5f)}).withDevice(new InputDevice(7, true)));
        check(workspace.ignored == 7 && workspace.boundsCancels == 0, "foreign and late hover events never reach workspace cancellation");
        check(workspace.samples.size() == 1 && workspace.samples.get(0).phase == PenSample.DOWN,
            "active contact survives unrelated outside-area events");
        workspace.dispatch(new MotionEvent(MotionEvent.ACTION_MOVE, 0, new int[]{9, 3},
            new int[]{MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS},
            new MotionEvent.Sample[]{new MotionEvent.Sample(1040, -20, 100, .5f)},
            new MotionEvent.Sample[]{new MotionEvent.Sample(1040, 200, 100, .5f)}).withDevice(new InputDevice(7, true)));
        workspace.dispatch(new MotionEvent(MotionEvent.ACTION_POINTER_UP, 1, new int[]{9, 3},
            new int[]{MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS},
            new MotionEvent.Sample[]{new MotionEvent.Sample(1050, -20, 100, .5f)},
            new MotionEvent.Sample[]{new MotionEvent.Sample(1050, 200, 100, 0)}).withDevice(new InputDevice(7, true)));
        check(workspace.samples.size() == 3 && workspace.samples.get(1).phase == PenSample.MOVE
            && workspace.samples.get(2).phase == PenSample.UP, "tracked pointer moves and releases after index reordering");
        check(Math.abs(workspace.samples.get(1).x - .2f) < .00001f, "pen coordinates never come from the unrelated finger");
        workspace.dispatch(event(MotionEvent.ACTION_DOWN, 1060, 8, 9, 300));
        workspace.dispatch(event(MotionEvent.ACTION_CANCEL, 1070, 8, 9, 300));
        check(workspace.samples.size() == 5 && workspace.samples.get(3).phase == PenSample.DOWN
            && workspace.samples.get(4).phase == PenSample.CANCEL, "new device can start after release and real cancellation passes");
        MotionEvent hover = event(MotionEvent.ACTION_HOVER_MOVE, 1080, 7, 3, -20);
        check(WorkspaceInputGate.consumes(hover, 0, true, 7, 3), "edge contacts also suppress late hover");
        check(!WorkspaceInputGate.consumes(hover, 0, false, 7, 3), "hover resumes when contact ends");
        System.out.println("WorkspaceInputGateTest passed: " + checks + " checks");
    }
}
