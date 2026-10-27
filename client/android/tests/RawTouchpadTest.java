import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.linloir.reflection.core.RawTouchpadFrame;
import io.github.linloir.reflection.core.TouchpadEvent;
import io.github.linloir.reflection.core.Wire;
import io.github.linloir.reflection.input.TouchpadInput;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class RawTouchpadTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static MotionEvent event(int action, int index, long time, int[] ids, float... points) {
        int[] tools = new int[ids.length];
        MotionEvent.Sample[][] samples = new MotionEvent.Sample[ids.length][1];
        for (int i = 0; i < ids.length; i++) {
            tools[i] = MotionEvent.TOOL_TYPE_FINGER;
            samples[i][0] = new MotionEvent.Sample(time, points[2 * i], points[2 * i + 1], 0);
        }
        return new MotionEvent(action, index, ids, tools, samples);
    }
    private static RawTouchpadFrame frame(int id, int phase) {
        return new RawTouchpadFrame(new RawTouchpadFrame.Contact[] {
            new RawTouchpadFrame.Contact(id, phase, .25f, .5f, true)}, false, 1000);
    }
    public static void main(String[] args) {
        List<TouchpadEvent> interpreted = new ArrayList<>();
        List<RawTouchpadFrame> frames = new ArrayList<>();
        boolean[] available = {true};
        TouchpadInput input = new TouchpadInput(1, interpreted::add);
        input.setRawSink(() -> available[0], frames::add);
        input.setBounds(10, 20, 210, 120);
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 100, new int[] {7}, 60, 70), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 1, 110, new int[] {7, 3}, 70, 80, 110, 90), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_UP, 0, 120, new int[] {7, 3}, 80, 80, 120, 90), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 130, new int[] {3}, 130, 90), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 0, 140, new int[] {3}, 140, 90), false);
        check(frames.size() == 5 && interpreted.isEmpty(), "native input must not also emit interpreted gestures");
        check(frames.get(0).contacts[0].id == 7 && frames.get(0).contacts[0].x == .25f
            && frames.get(0).contacts[0].y == .5f, "active rectangle normalization");
        check(frames.get(1).contacts[0].phase == 2 && frames.get(1).contacts[1].phase == 1, "new contact preserves existing contacts");
        check(frames.get(2).contacts[0].phase == 3 && frames.get(2).contacts[1].phase == 2, "terminal contact remains in complete snapshot");
        check(frames.get(3).contacts[0].id == 3 && frames.get(3).contacts.length == 1, "pointer indices may change but IDs remain stable");
        check(!input.active() && frames.get(4).contacts[0].phase == 3, "final release ends gesture");
        frames.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 200, new int[] {5}, 50, 50), false);
        input.onTouch(new MotionEvent(MotionEvent.ACTION_MOVE, 0, new int[] {5},
            new int[] {MotionEvent.TOOL_TYPE_FINGER}, new MotionEvent.Sample[] {
                new MotionEvent.Sample(210, 60, 60, 0), new MotionEvent.Sample(220, 70, 70, 0),
                new MotionEvent.Sample(230, 80, 80, 0)}), false);
        check(frames.size() == 4 && frames.get(1).timeNanos == 210000000L && frames.get(3).timeNanos == 230000000L,
            "historical contact snapshots preserve event times");
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 240, new int[] {5}, 90, 90), true);
        check(frames.get(4).cancelled && !input.active(), "pen proximity releases every native contact");
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 250, new int[] {5}, 100, 90), false);
        check(frames.size() == 5 && interpreted.isEmpty(), "palm cannot resume as a new gesture");
        frames.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 300, new int[] {1}, 50, 50), false);
        input.setBounds(10, 20, 220, 120);
        check(frames.size() == 2 && frames.get(1).cancelled, "area changes cancel native gesture");
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 310, new int[] {1}, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 320, new int[] {1}, Float.NaN, 50), false);
        check(frames.get(frames.size() - 1).cancelled, "nonfinite coordinates release native contacts");
        frames.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 400, new int[] {1}, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 5, 410, new int[] {1, 2, 3, 4, 5, 6},
            1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6), false);
        check(frames.size() == 2 && frames.get(1).cancelled, "sixth finger cancels rather than truncating contact set");
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 420, new int[] {1}, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 430, new int[] {1}, 60, 50).withDevice(new InputDevice(2, true)), false);
        check(frames.get(frames.size() - 1).cancelled, "device switch releases old contacts");
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 440, new int[] {1}, 50, 50), false);
        available[0] = false;
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 450, new int[] {1}, 60, 50), false);
        check(frames.get(frames.size() - 1).cancelled, "loss of native negotiation cancels gesture");
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 460, new int[] {1}, 50, 50), false);
        available[0] = true;
        input.onTouch(event(MotionEvent.ACTION_UP, 0, 470, new int[] {1}, 50, 50), false);
        check(interpreted.size() == 1 && interpreted.get(0).kind == TouchpadEvent.LEFT_TAP, "late capability does not change gesture mode");
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 480, new int[] {1}, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 490, new int[] {1}, 60, 50).withTool(0), false);
        check(frames.get(frames.size() - 1).cancelled, "palm reclassification without any finger releases native contacts");
        available[0] = false; interpreted.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 500, new int[] {1}, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 510, new int[] {1}, 50, 50).withTool(0), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 0, 520, new int[] {1}, 50, 50), false);
        check(interpreted.size() == 1 && interpreted.get(0).kind == TouchpadEvent.CANCEL,
            "legacy palm reclassification cannot resume as an accidental tap");
        interpreted.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 530, new int[] {1}, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 1, 540, new int[] {1, 2}, 50, 50, 90, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 550, new int[] {1, 2}, 50, 70, 90, 70), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 0, 560, new int[] {1}, 50, 70).withTool(0), false);
        check(interpreted.size() == 3 && interpreted.get(2).kind == TouchpadEvent.CANCEL && !input.active(),
            "legacy palm reclassification releases scrolling state");
        available[0] = true; frames.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 570, new int[] {1}, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 1, 580, new int[] {1, 2}, 50, 50, 90, 50), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 0, 590, new int[] {1, 2}, 50, 50, 90, 50), false);
        check(frames.size() == 3 && frames.get(2).cancelled && !input.active(),
            "malformed multi-contact final UP releases all contacts");
        RawTouchpadFrame.State state = new RawTouchpadFrame.State();
        check(!state.accept(frame(1, 2)) && !state.active(), "orphan update after reconnect is rejected");
        check(state.accept(frame(1, 1)) && state.active(), "fresh down starts lifecycle");
        check(!state.accept(frame(1, 1)) && state.active(), "duplicate down preserves state for cancellation");
        check(!state.accept(frame(2, 1)), "omitted active contact rejected");
        check(state.accept(frame(1, 3)) && !state.active(), "up removes active contact");
        check(state.accept(RawTouchpadFrame.cancel(1001)), "empty cancellation is idempotent");
        check(RawTouchpadFrame.physicalDimension(1000, 254, 1, 10000) == 10000, "physical DPI conversion");
        check(RawTouchpadFrame.physicalDimension(1000, Float.NaN, 1, 10000) == 15875, "missing DPI uses density fallback");
        check(RawTouchpadFrame.physicalDimension(0, 254, 1, 10000) == 10000, "missing display uses virtual surface fallback");
        ByteBuffer bytes = ByteBuffer.wrap(Wire.rawTouchpad(frame(7, 1), 5, 1000));
        check(bytes.remaining() == 36 && bytes.getLong() == 5 && bytes.getLong() == 1000
            && bytes.get() == 1 && bytes.get() == 0 && bytes.getShort() == 0 && bytes.getInt() == 7
            && bytes.get() == 1 && bytes.get() == 1 && bytes.getShort() == 0 && bytes.getFloat() == .25f
            && bytes.getFloat() == .5f, "native wire schema");
        check(Wire.rawTouchpad(RawTouchpadFrame.cancel(1000), 6, 1000).length == 20, "bounded all-contact cancellation");
        RawTouchpadFrame shared = new RawTouchpadFrame(new RawTouchpadFrame.Contact[] {
            new RawTouchpadFrame.Contact(42, 1, .25f, .75f, true)}, false, 1234);
        StringBuilder vector = new StringBuilder();
        for (byte value : Wire.rawTouchpad(shared, 1, 1234)) vector.append(String.format("%02x", value & 255));
        check(vector.toString().equals("000000000000000100000000000004d2010000000000002a010100003e8000003f400000"),
            "Windows and Android share exact raw contact vector");
        System.out.println("Raw touchpad adapter/state/physical dimensions: " + checks + " checks passed");
    }
}
