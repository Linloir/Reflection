package io.github.linloir.reflection.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.graphics.Rect;
import io.github.linloir.reflection.eink.EinkRefresh;
import io.github.linloir.reflection.eink.RefreshDriver;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.SurfaceCalibration;
import io.github.linloir.reflection.core.TiltMatcher;
import io.github.linloir.reflection.input.PenInput;
import io.github.linloir.reflection.control.Controller;
import java.util.Arrays;

/** Guided samples stay local: calibration must never draw on the computer. */
final class CalibrationView extends View {
    interface Listener { void changed(String instruction, boolean complete, boolean hasData); }
    private static final float[][] TARGETS = {{.16f, .2f}, {.84f, .2f}, {.84f, .8f}, {.16f, .8f}, {.5f, .5f}};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PenInput input;
    private final CalibrationStore store;
    private final Listener listener;
    private final int step;
    private final String decoderMode;
    private final double[][] measured = new double[5][2], targets = new double[5][2];
    private final double[] xs = new double[4096], ys = new double[4096];
    private final TiltMatcher tiltMatcher = new TiltMatcher();
    private int point, count;
    private long began, lastTime;
    private double totalNoise;
    private boolean contact, waitForLift, complete, anyData;
    private String lastMessage = "";
    private float progress;
    private final int[] location = new int[2];
    private final DisplayMetrics screen = new DisplayMetrics();
    private final EinkRefresh refresh;
    private int drawnProgress = -1;

    CalibrationView(Context context, int step, CalibrationStore store, Listener listener) {
        super(context); this.step = step; this.store = store; this.listener = listener;
        refresh = new EinkRefresh(this, UiPreferences.load(context).eink);
        decoderMode = "off".equals(Controller.get(context).tiltMode()) ? "auto" : Controller.get(context).tiltMode();
        input = new PenInput(this::sample); input.setTiltMode(decoderMode);
        complete = store.complete(step); setBackgroundColor(0xfffafbf8);
        setContentDescription(step == 1 ? "依次按住五个校准点" : "按住中心点，按提示倾斜笔身");
        post(() -> notifyState(complete ? "校准已保存，可以继续；也可以点重做重新采样。" : initialInstruction()));
    }
    boolean hasData() { return anyData || complete; }
    boolean complete() { return complete; }
    void cancel() { input.cancel(); refresh.cancel(); }
    private void redrawProgress() {
        int level = Math.round(progress * (refresh.enabled() ? 10 : 40));
        if (level == drawnProgress) return;
        drawnProgress = level;
        refresh.request(new Rect(0, 0, getWidth(), getHeight()), RefreshDriver.Update.FADE);
    }
    private String initialInstruction() {
        if (step == 1) return "用笔按住高亮点至少 1.2 秒，点变色后抬笔。共 5 个点。";
        return step == 2 ? "先竖直笔身按住中心点；稳定后，向下再向上倾斜。"
            : "先竖直笔身按住中心点；稳定后，向右再向左倾斜。";
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (complete) return true;
        input.onEvent(event, getWidth(), getHeight(), rotation()); return true;
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return input.onEvent(event, getWidth(), getHeight(), rotation());
    }
    private void sample(PenSample p) {
        if (complete) return;
        if (p.phase == PenSample.UP || p.phase == PenSample.CANCEL || p.phase == PenSample.LEAVE) {
            contact = false; count = 0; progress = 0; waitForLift = false;
            tiltMatcher.reset();
            if (step != 1) anyData = false;
            if (p.phase == PenSample.CANCEL) notifyState("采样已中断，请重新按住当前点。");
            else if (step != 1) notifyState(initialInstruction());
            drawnProgress = -1; redrawProgress(); return;
        }
        if (p.phase != PenSample.DOWN && p.phase != PenSample.MOVE || waitForLift) return;
        float tx = step == 1 ? TARGETS[point][0] : .5f;
        float ty = step == 1 ? TARGETS[point][1] : .5f;
        float radius = Math.max(dp(28), Math.min(getWidth(), getHeight()) * .10f);
        if (Math.hypot((p.x - tx) * getWidth(), (p.y - ty) * getHeight()) > radius) {
            contact = false; count = 0; progress = 0; tiltMatcher.reset();
            if (step != 1) anyData = false;
            notifyState("请将笔尖放在当前高亮点上。"); drawnProgress = -1; redrawProgress(); return;
        }
        if (p.phase == PenSample.DOWN) {
            contact = true; began = p.timeNanos; lastTime = began - 1; count = 0;
            tiltMatcher.reset();
        }
        if (!contact || p.timeNanos <= lastTime) return;
        lastTime = p.timeNanos; anyData = true;
        if (step == 1) position(p, tx, ty); else tilt(p);
    }
    private void position(PenSample p, float tx, float ty) {
        if (count >= xs.length) { contact = false; notifyState("采样时间过长，请抬笔重试。"); return; }
        getLocationOnScreen(location); getDisplay().getRealMetrics(screen);
        double[] panel = SurfaceCalibration.toPanel((location[0] + p.x * (getWidth() - 1)) / screen.widthPixels,
            (location[1] + p.y * (getHeight() - 1)) / screen.heightPixels, rotation());
        xs[count] = panel[0]; ys[count] = panel[1]; count++;
        progress = Math.min(1, Math.min(count / 100f, (p.timeNanos - began) / 1200000000f));
        if (progress < 1) { notifyState("保持不动  ·  第 " + (point + 1) + " / 5 点"); redrawProgress(); return; }
        double mx = median(xs, count), my = median(ys, count);
        double nx = noise(xs, count), ny = noise(ys, count);
        if (nx > .004 || ny > .004 || Math.hypot(xs[count - 1] - xs[0], ys[count - 1] - ys[0]) > .012) {
            contact = false; notifyState("这次移动幅度稍大，请抬笔后放松手腕重试。"); return;
        }
        measured[point] = new double[]{mx, my};
        targets[point] = SurfaceCalibration.toPanel((location[0] + tx * (getWidth() - 1)) / screen.widthPixels,
            (location[1] + ty * (getHeight() - 1)) / screen.heightPixels, rotation());
        totalNoise += Math.max(nx, ny);
        point++; waitForLift = true; progress = 0;
        if (point == 5) {
            try {
                store.saveSurface(SurfaceCalibration.fit(measured, targets, Math.max(.00001, totalNoise / 5)));
                complete = true; notifyState("5 个点已完成，坐标修正与抖动噪声已保存。");
            } catch (IllegalArgumentException invalid) {
                point = 0; totalNoise = 0; notifyState("校准点不一致，请抬笔后重新完成五个点。");
            }
        } else notifyState("这个点已完成。抬笔，移到第 " + (point + 1) + " 个点。");
        drawnProgress = -1; redrawProgress();
    }
    private void tilt(PenSample p) {
        tiltMatcher.sample(p.tiltX, p.tiltY, p.timeNanos, (p.capabilities & 2) != 0);
        anyData = tiltMatcher.hasData(); progress = tiltMatcher.progress();
        if (tiltMatcher.complete()) {
            if (store.axisForInput(step == 2 ? 3 : 2, rotation(), decoderMode) == tiltMatcher.axis()) {
                anyData = false;
                notifyState("设备当前只提供另一个方向已使用的轴。此方向无法独立校准，可跳过。");
            } else {
                store.saveAxis(step, tiltMatcher.axis(), tiltMatcher.sign(), rotation(), decoderMode); complete = true; drawnProgress = -1;
                notifyState((step == 2 ? "上下" : "左右") + "倾斜方案已匹配并保存。");
            }
        } else if (tiltMatcher.stage() == TiltMatcher.Stage.UNAVAILABLE)
            notifyState("尚未识别到可靠倾斜轴；缓慢倾斜笔身，也可以跳过此项。");
        else if (tiltMatcher.stage() == TiltMatcher.Stage.CENTERING)
            notifyState("先将笔身竖直，在中心点稳定片刻，再开始倾斜。");
        else if (tiltMatcher.stage() == TiltMatcher.Stage.NEUTRAL)
            notifyState("保持笔身竖直，正在记录起始角度。");
        else notifyState((step == 2 ? "先向下，再越过竖直位向上倾斜" : "先向右，再越过竖直位向左倾斜")
            + "  ·  " + Math.round(tiltMatcher.range()) + "°");
        redrawProgress();
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int total = step == 1 ? 5 : 1;
        for (int i = 0; i < total; i++) {
            float x = (step == 1 ? TARGETS[i][0] : .5f) * (getWidth() - 1);
            float y = (step == 1 ? TARGETS[i][1] : .5f) * (getHeight() - 1);
            boolean done = complete || step == 1 && i < point, active = !complete && (step != 1 || i == point);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(active ? 2 : 1));
            paint.setColor(done ? 0xff365c4c : active ? 0xff252a26 : 0xffc7ccc6);
            c.drawCircle(x, y, dp(12), paint);
            if (active) {
                c.drawLine(x - dp(24), y, x - dp(17), y, paint); c.drawLine(x + dp(17), y, x + dp(24), y, paint);
                c.drawLine(x, y - dp(24), x, y - dp(17), paint); c.drawLine(x, y + dp(17), x, y + dp(24), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, dp(done ? 7 : 3), paint);
            if (active && progress > 0) c.drawRect(x - dp(24), y + dp(28), x - dp(24) + dp(48) * progress, y + dp(30), paint);
        }
    }
    private void notifyState(String message) {
        if (message.equals(lastMessage)) return;
        lastMessage = message; listener.changed(message, complete, hasData());
    }
    private int rotation() { return getDisplay() == null ? 0 : getDisplay().getRotation(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static double median(double[] data, int count) {
        double[] copy = Arrays.copyOf(data, count); Arrays.sort(copy);
        return count % 2 == 0 ? (copy[count / 2] + copy[count / 2 - 1]) / 2 : copy[count / 2];
    }
    private static double noise(double[] data, int count) {
        double[] residuals = new double[count];
        double meanTime = (count - 1) / 2.0, mean = 0, slope = 0, denominator = 0;
        for (int i = 0; i < count; i++) mean += data[i] / count;
        for (int i = 0; i < count; i++) { slope += (i - meanTime) * (data[i] - mean); denominator += (i - meanTime) * (i - meanTime); }
        slope /= Math.max(1, denominator);
        for (int i = 0; i < count; i++) residuals[i] = data[i] - mean - slope * (i - meanTime);
        double center = median(residuals, count);
        for (int i = 0; i < count; i++) residuals[i] = Math.abs(residuals[i] - center);
        return 1.4826 * median(residuals, count);
    }
}
