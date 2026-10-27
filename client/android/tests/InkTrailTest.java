import io.github.linloir.reflection.core.InkTrail;

public final class InkTrailTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        InkTrail trail = new InkTrail();
        trail.begin(0, 0, 3, 100);
        trail.append(10, 0, 3); trail.append(20, 0, 4);
        InkTrail.Stroke stroke = trail.strokes().get(0);
        check(InkTrail.alpha(stroke, 2, 100000, 1500, 450, false) == 255, "active stroke stays visible");
        trail.finish(200);
        check(InkTrail.alpha(stroke, 2, 1700, 1500, 450, false) == 255, "delay starts at pen up");
        int tail = InkTrail.alpha(stroke, 2, 1925, 1500, 450, false);
        int head = InkTrail.alpha(stroke, 0, 1925, 1500, 450, false);
        check(tail > 0 && tail < 255 && head == 255, "tail dissolves before head");
        int eink = InkTrail.alpha(stroke, 2, 1925, 1500, 450, true);
        check(eink == 128, "e-paper uses discrete gray steps");
        check(!trail.prune(2600, 1500, 450), "unfinished fade is retained");
        check(trail.prune(2650, 1500, 450) && trail.empty(), "finished fade is removed");
        trail.begin(0, 0, 2, 3000); trail.append(100, 0, 2);
        stroke = trail.strokes().get(0);
        trail.erase(50, 0, 5);
        check(InkTrail.alpha(stroke, 1, 3000, 1500, 450, false) == 0, "eraser intersects segment interior");
        check(InkTrail.alpha(stroke, 0, 3000, 1500, 450, false) == 255, "eraser preserves distant points");
        InkTrail swept = new InkTrail();
        swept.begin(0, 0, 2, 3000); swept.append(100, 0, 2);
        swept.erase(50, -50, 50, 50, 5);
        check(swept.strokes().get(0).segments().get(1).erased, "fast eraser clears crossing between samples");
        check(!swept.strokes().get(0).segments().get(0).erased, "swept eraser preserves distant points");
        swept.begin(0, 10, 2, 3001); swept.append(100, 10, 2);
        swept.erase(20, 14, 80, 14, 5);
        check(swept.strokes().get(1).segments().get(1).erased, "parallel eraser sweep includes its radius");
        swept.begin(0, 30, 2, 3002); swept.append(100, 30, 2);
        swept.erase(20, 40, 80, 40, 5);
        check(!swept.strokes().get(2).segments().get(1).erased, "nearby parallel line outside radius survives");
        for (int i = 0; i < InkTrail.MAX_SEGMENTS + 100; i++) trail.append(i, i, 1);
        check(trail.segmentCount() == InkTrail.MAX_SEGMENTS, "active stroke memory is bounded");
        for (int i = 0; i < InkTrail.MAX_STROKES + 20; i++) {
            trail.begin(i, i, 1, 5000); trail.finish(5001);
        }
        check(trail.strokes().size() <= InkTrail.MAX_STROKES, "stroke count is bounded");
        check(trail.segmentCount() <= InkTrail.MAX_SEGMENTS, "total segment count is bounded");
        trail.clear();
        check(trail.segmentCount() == 0 && !trail.hasFinishedStroke(), "clear releases data");
        System.out.println("InkTrailTest passed");
    }
}
