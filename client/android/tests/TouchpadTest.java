import android.view.MotionEvent;
import io.github.linloir.reflection.core.TouchpadEvent;
import io.github.linloir.reflection.core.Wire;
import io.github.linloir.reflection.input.TouchpadInput;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class TouchpadTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static MotionEvent event(int action, long time, float... points) {
        int n = points.length / 2;
        int[] ids = new int[n], tools = new int[n];
        MotionEvent.Sample[][] samples = new MotionEvent.Sample[n][1];
        for (int i = 0; i < n; i++) {
            ids[i] = i; tools[i] = MotionEvent.TOOL_TYPE_FINGER;
            samples[i][0] = new MotionEvent.Sample(time, points[i * 2], points[i * 2 + 1], 0);
        }
        return new MotionEvent(action, n - 1, ids, tools, samples);
    }
    public static void main(String[] args) {
        List<TouchpadEvent> events = new ArrayList<>();
        TouchpadInput input = new TouchpadInput(1, events::add);
        input.onTouch(event(MotionEvent.ACTION_DOWN, 0, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 100, 50, 50), false);
        check(events.size() == 1 && events.get(0).kind == TouchpadEvent.LEFT_TAP, "one-finger tap");
        events.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 200, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 220, 50, 50, 90, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_UP, 250, 50, 50, 90, 50), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 260, 50, 50), false);
        check(events.size() == 1 && events.get(0).kind == TouchpadEvent.RIGHT_TAP, "two-finger tap never emits left tap");
        events.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 300, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 310, 70, 60), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 320, 70, 60), false);
        check(events.size() == 1 && events.get(0).kind == TouchpadEvent.MOVE && events.get(0).x == 20, "motion suppresses tap");
        events.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 330, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 340, 90, 90), false);
        check(events.isEmpty(), "release displacement cannot become an accidental tap");
        input.onTouch(event(MotionEvent.ACTION_DOWN, 400, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 410, 50, 50, 90, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 420, 50, 70, 90, 70), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_UP, 430, 50, 70, 90, 70), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 440, 150, 170), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 450, 150, 170), false);
        check(events.size() == 3 && events.get(0).kind == TouchpadEvent.SCROLL_BEGIN
            && events.get(1).kind == TouchpadEvent.SCROLL && events.get(1).y == 20
            && events.get(2).kind == TouchpadEvent.SCROLL_END, "finger lift ends scroll without cursor jump");
        events.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 500, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 510, 50, 50, 90, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 520, 40, 50, 100, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_UP, 530, 40, 50, 100, 50), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 540, 40, 50), false);
        check(events.get(0).kind == TouchpadEvent.ZOOM && events.get(0).x > 0
            && events.get(events.size() - 1).kind == TouchpadEvent.CANCEL, "pinch independent of scroll and clears remainder");
        events.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 600, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 610, 50, 50, 90, 50), false);
        input.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 620, 50, 50, 90, 50, 130, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 630, 150, 50, 190, 50, 230, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 640, 250, 50, 290, 50, 330, 50), false);
        check(events.size() == 1 && events.get(0).kind == TouchpadEvent.SWIPE
            && events.get(0).fingers == 3 && events.get(0).x == 1, "swipe emitted once");
        input.cancel(); events.clear();
        input.onTouch(event(MotionEvent.ACTION_DOWN, 700, 50, 50), true);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 710, 150, 50), false);
        input.onTouch(event(MotionEvent.ACTION_UP, 720, 150, 50), false);
        check(events.isEmpty(), "palm remains suppressed until next down");
        input.onTouch(event(MotionEvent.ACTION_DOWN, 800, 50, 50), false);
        input.onTouch(event(MotionEvent.ACTION_MOVE, 810, 150, 50), true);
        check(events.size() == 1 && events.get(0).kind == TouchpadEvent.CANCEL && !input.active(), "pen arrival cancels touch");
        byte[] bytes = Wire.touchpad(new TouchpadEvent(TouchpadEvent.SCROLL, 2, 12.5f, -20, 1234), 1, 1234);
        ByteBuffer wire = ByteBuffer.wrap(bytes);
        check(bytes.length == 32 && wire.getLong() == 1 && wire.getLong() == 1234
            && wire.getFloat() == 12.5f && wire.getFloat() == -20
            && wire.get() == 4 && wire.get() == 2 && wire.getShort() == 0 && wire.getInt() == 0, "wire fields");
        try { new TouchpadEvent(TouchpadEvent.SWIPE, 2, 1, 0, 1); throw new AssertionError("invalid swipe"); }
        catch (IllegalArgumentException expected) {}
        try { new TouchpadEvent(TouchpadEvent.MOVE, 1, Float.NaN, 0, 1); throw new AssertionError("NaN delta"); }
        catch (IllegalArgumentException expected) {}
        System.out.println("Touchpad gesture and wire tests passed");
    }
}
