package io.github.linloir.reflection.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import io.github.linloir.reflection.control.Controller;
import io.github.linloir.reflection.core.InkTrail;
import io.github.linloir.reflection.core.MappingState;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.eink.EinkRefresh;
import io.github.linloir.reflection.eink.RefreshDriver;
import io.github.linloir.reflection.input.PenInput;
import java.util.List;

/** Pen capture, local visual feedback and device refresh policy remain independent. */
public final class InkView extends View {
    public interface EventInterceptor { boolean onEvent(MotionEvent event, RectF active); }
    public interface SampleTransform { PenSample transform(PenSample sample); }
    public static final class WorkspaceOptions {
        public String einkMode = "auto", background = "dots";
        public float backgroundSpacingDp = 24, marginDp = 24;
        public float regionLeft = 0, regionTop = 0, regionRight = 1, regionBottom = 1;
        public boolean showBorder = true, showScreenBoundaries = true, fadingInk = false;
        public int fadeDelayMs = 1500, fadeDurationMs = 450;
    }

    private final Controller controller;
    private final PenInput input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF active = new RectF();
    private final InkTrail trail = new InkTrail();
    private final PorterDuffXfermode erase = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
    private WorkspaceOptions options = new WorkspaceOptions();
    private EinkRefresh refresh;
    private EventInterceptor interceptor;
    private SampleTransform transform;
    private Bitmap bitmap;
    private Canvas ink;
    private float lastX, lastY;
    private boolean stroke, previewEnabled, fadingScheduled;
    private int clearGeneration, completedStrokes;
    private long tiltRevision = -1, mappingRevision = -1;
    private final Runnable cleanIdle = new Runnable() {
        @Override public void run() {
            if (stroke || !isShown()) return;
            completedStrokes = 0;
            refresh.request(new Rect((int) active.left, (int) active.top,
                (int) Math.ceil(active.right), (int) Math.ceil(active.bottom)), RefreshDriver.Update.CLEAN);
        }
    };
    private final Runnable fade = new Runnable() {
        @Override public void run() {
            fadingScheduled = false;
            if (!previewEnabled || !options.fadingInk || !isShown()) return;
            long now = SystemClock.uptimeMillis();
            Rect dirty = new Rect();
            for (InkTrail.Stroke item : trail.strokes()) {
                List<InkTrail.Segment> segments = item.segments();
                for (int i = 0; i < segments.size(); i++) {
                    InkTrail.Segment segment = segments.get(i);
                    int alpha = InkTrail.alpha(item, i, now, options.fadeDelayMs, options.fadeDurationMs, einkEnabled());
                    if (alpha != segment.displayedAlpha) {
                        dirty.union(bounds(segment.x1, segment.y1, segment.x2, segment.y2, segment.width));
                        segment.displayedAlpha = alpha;
                    }
                }
            }
            trail.prune(now, options.fadeDelayMs, options.fadeDurationMs);
            if (!dirty.isEmpty()) refresh.request(dirty, RefreshDriver.Update.FADE);
            scheduleFade();
        }
    };

    public InkView(Context context, Controller controller) {
        super(context); this.controller = controller;
        paint.setStrokeCap(Paint.Cap.ROUND);
        setBackgroundColor(Color.WHITE);
        setFocusable(true);
        setContentDescription("Reflection pen and touch workspace");
        refresh = new EinkRefresh(this, options.einkMode);
        previewEnabled = controller.preview();
        input = new PenInput(sample -> {
            PenSample output = transform == null ? sample : transform.transform(sample);
            if (output != null) controller.accept(output);
            drawSample(sample);
        });
    }

    public void setEventInterceptor(EventInterceptor interceptor) { this.interceptor = interceptor; }
    public void setSampleTransform(SampleTransform transform) { cancelInput(); this.transform = transform; }
    public RectF activeRect() { return new RectF(active); }
    public boolean einkEnabled() { return refresh.enabled(); }
    public String refreshDriverName() { return refresh.driverName(); }
    public boolean regionalWaveforms() { return refresh.regionalWaveforms(); }
    public void requestCleanRefresh() { refresh.clean(); }

    public void applyOptions(WorkspaceOptions value) {
        cancelInput();
        options = copy(value);
        refresh.cancel();
        refresh = new EinkRefresh(this, options.einkMode);
        updateActiveRect();
        clearVisuals();
        updateGeometry();
        refresh.clean();
    }

    private static WorkspaceOptions copy(WorkspaceOptions value) {
        WorkspaceOptions result = new WorkspaceOptions();
        result.einkMode = value.einkMode;
        result.background = value.background;
        result.backgroundSpacingDp = finite(value.backgroundSpacingDp, 24, 8, 96);
        result.marginDp = finite(value.marginDp, 24, 0, 160);
        result.regionLeft = finite(value.regionLeft, 0, 0, .9f);
        result.regionTop = finite(value.regionTop, 0, 0, .9f);
        result.regionRight = finite(value.regionRight, 1, result.regionLeft + .1f, 1);
        result.regionBottom = finite(value.regionBottom, 1, result.regionTop + .1f, 1);
        result.showBorder = value.showBorder;
        result.showScreenBoundaries = value.showScreenBoundaries;
        result.fadingInk = value.fadingInk;
        result.fadeDelayMs = Math.max(1000, Math.min(2000, value.fadeDelayMs));
        result.fadeDurationMs = Math.max(240, Math.min(1200, value.fadeDurationMs));
        return result;
    }

    private static float finite(float value, float fallback, float min, float max) {
        return Float.isNaN(value) || Float.isInfinite(value) ? fallback : Math.max(min, Math.min(max, value));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cancelInput();
        updateActiveRect(); clearVisuals(); updateGeometry();
    }

    private void updateActiveRect() {
        float margin = Math.min(dp(options.marginDp), Math.max(0, Math.min(getWidth(), getHeight()) / 2f - dp(24)));
        float width = Math.max(0, getWidth() - margin * 2), height = Math.max(0, getHeight() - margin * 2);
        active.set(margin + width * options.regionLeft, margin + height * options.regionTop,
            margin + width * options.regionRight, margin + height * options.regionBottom);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        dispatchInput(event);
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return dispatchInput(event) || super.onGenericMotionEvent(event);
    }

    private boolean dispatchInput(MotionEvent event) {
        updateGeometry();
        if (interceptor != null && interceptor.onEvent(event, new RectF(active))) return true;
        int index = event.getActionIndex();
        int tool = event.getToolType(index);
        boolean pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
        int action = event.getActionMasked();
        if (pen && !active.contains(event.getX(index), event.getY(index))
            && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_MOVE)) {
            input.cancel(); return true;
        }
        if (Build.VERSION.SDK_INT >= 30 && action == MotionEvent.ACTION_DOWN && pen)
            requestUnbufferedDispatch(event);
        refreshTiltSettings();
        MotionEvent local = MotionEvent.obtain(event);
        local.offsetLocation(-active.left, -active.top);
        try {
            return input.onEvent(local, Math.round(active.width()), Math.round(active.height()), displayRotation());
        } finally {
            local.recycle();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setXfermode(null); paint.setAlpha(255); paint.setStyle(Paint.Style.STROKE);
        int checkpoint = canvas.save();
        canvas.clipRect(active);
        drawBackground(canvas);
        drawDisplayBoundaries(canvas);
        if (previewEnabled) {
            if (options.fadingInk) drawTrail(canvas);
            else if (bitmap != null) canvas.drawBitmap(bitmap, 0, 0, null);
        }
        canvas.restoreToCount(checkpoint);
        if (options.showBorder) {
            paint.setColor(0xffa6a6a1); paint.setAlpha(255); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            canvas.drawRect(active, paint);
            paint.setColor(0xff242723); paint.setStrokeWidth(dp(2));
            float length = dp(12);
            canvas.drawLine(active.left, active.top + length, active.left, active.top, paint);
            canvas.drawLine(active.left, active.top, active.left + length, active.top, paint);
            canvas.drawLine(active.right - length, active.bottom, active.right, active.bottom, paint);
            canvas.drawLine(active.right, active.bottom, active.right, active.bottom - length, paint);
        }
    }

    private void drawBackground(Canvas canvas) {
        if ("blank".equals(options.background)) return;
        float spacing = dp(options.backgroundSpacingDp);
        paint.setColor(einkEnabled() ? 0xffc2c2bc : 0xffddddd7);
        paint.setStrokeWidth(dp(.7f)); paint.setAlpha(255);
        if ("grid".equals(options.background)) {
            for (float x = active.left + spacing; x < active.right; x += spacing)
                canvas.drawLine(x, active.top, x, active.bottom, paint);
            for (float y = active.top + spacing; y < active.bottom; y += spacing)
                canvas.drawLine(active.left, y, active.right, y, paint);
        } else {
            paint.setStrokeWidth(dp(1.3f)); paint.setStrokeCap(Paint.Cap.SQUARE);
            for (float x = active.left + spacing; x < active.right; x += spacing)
                for (float y = active.top + spacing; y < active.bottom; y += spacing) canvas.drawPoint(x, y, paint);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
    }

    private void drawDisplayBoundaries(Canvas canvas) {
        MappingState mapping = controller.mapping();
        if (!options.showScreenBoundaries || mapping == null || !"all".equals(mapping.mode)) return;
        paint.setColor(0xffa6a6a1); paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.STROKE);
        for (MappingState.Display display : mapping.displays) {
            MappingState.Region b = display.bounds, target = mapping.target;
            float left = active.left + (float) ((b.x - target.x) / target.width) * active.width();
            float top = active.top + (float) ((b.y - target.y) / target.height) * active.height();
            float right = left + (float) (b.width / target.width) * active.width();
            float bottom = top + (float) (b.height / target.height) * active.height();
            canvas.drawRect(left, top, right, bottom, paint);
        }
    }

    private void drawTrail(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        paint.setColor(0xff222622); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        for (InkTrail.Stroke item : trail.strokes()) {
            List<InkTrail.Segment> segments = item.segments();
            for (int i = 0; i < segments.size(); i++) {
                InkTrail.Segment segment = segments.get(i);
                int alpha = InkTrail.alpha(item, i, now, options.fadeDelayMs, options.fadeDurationMs, einkEnabled());
                if (alpha == 0) continue;
                paint.setAlpha(alpha); paint.setStrokeWidth(segment.width);
                if (segment.x1 == segment.x2 && segment.y1 == segment.y2)
                    canvas.drawPoint(segment.x2, segment.y2, paint);
                else canvas.drawLine(segment.x1, segment.y1, segment.x2, segment.y2, paint);
            }
        }
        paint.setAlpha(255);
    }

    private void drawSample(PenSample sample) {
        boolean end = sample.phase == PenSample.UP || sample.phase == PenSample.CANCEL || sample.phase == PenSample.LEAVE;
        long now = SystemClock.uptimeMillis();
        if (!previewEnabled) { stroke = false; return; }
        float x = active.left + sample.x * Math.max(1, Math.round(active.width()) - 1);
        float y = active.top + sample.y * Math.max(1, Math.round(active.height()) - 1);
        boolean eraser = sample.tool == PenSample.ERASER;
        float width = dp(eraser ? 26 : 1 + sample.pressure * 5);
        if (sample.phase == PenSample.DOWN) {
            removeCallbacks(cleanIdle);
            lastX = x; lastY = y; stroke = true;
            if (options.fadingInk && !eraser) {
                boolean atLimit = trail.segmentCount() >= InkTrail.MAX_SEGMENTS
                    || trail.strokes().size() >= InkTrail.MAX_STROKES;
                trail.begin(x, y, width, now);
                if (atLimit) refresh.request(new Rect((int) active.left, (int) active.top,
                    (int) Math.ceil(active.right), (int) Math.ceil(active.bottom)), RefreshDriver.Update.INK);
            }
        }
        boolean draw = sample.phase == PenSample.DOWN || sample.phase == PenSample.MOVE && stroke;
        if (draw) {
            Rect dirty = bounds(lastX, lastY, x, y, width);
            if (options.fadingInk) {
                if (eraser) {
                    trail.erase(lastX, lastY, x, y, width / 2);
                    for (InkTrail.Stroke item : trail.strokes()) for (InkTrail.Segment segment : item.segments())
                        if (segment.erased && segment.displayedAlpha != 0) {
                            dirty.union(bounds(segment.x1, segment.y1, segment.x2, segment.y2, segment.width));
                            segment.displayedAlpha = 0;
                        }
                } else if (sample.phase != PenSample.DOWN) {
                    boolean atLimit = trail.segmentCount() >= InkTrail.MAX_SEGMENTS;
                    trail.append(x, y, width);
                    if (atLimit) dirty.union((int) active.left, (int) active.top,
                        (int) Math.ceil(active.right), (int) Math.ceil(active.bottom));
                }
            } else {
                ensureBitmap();
                if (ink != null) {
                    paint.setColor(0xff222622); paint.setAlpha(255); paint.setStrokeWidth(width);
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setXfermode(eraser ? erase : null);
                    if (sample.phase == PenSample.DOWN) ink.drawPoint(x, y, paint);
                    else ink.drawLine(lastX, lastY, x, y, paint);
                    paint.setXfermode(null);
                }
            }
            refresh.request(dirty, RefreshDriver.Update.INK);
            lastX = x; lastY = y;
        }
        if (end) {
            if (stroke) completedStrokes++;
            stroke = false; trail.finish(now); scheduleFade();
            if (einkEnabled() && completedStrokes >= 24) {
                removeCallbacks(cleanIdle); postDelayed(cleanIdle, 400);
            }
        }
    }

    private Rect bounds(float x1, float y1, float x2, float y2, float width) {
        int pad = (int) Math.ceil(width / 2 + dp(2));
        return new Rect((int) Math.floor(Math.min(x1, x2)) - pad, (int) Math.floor(Math.min(y1, y2)) - pad,
            (int) Math.ceil(Math.max(x1, x2)) + pad, (int) Math.ceil(Math.max(y1, y2)) + pad);
    }

    private void ensureBitmap() {
        if (bitmap == null && getWidth() > 0 && getHeight() > 0) {
            bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            ink = new Canvas(bitmap);
        }
    }

    private void clearVisuals() {
        removeCallbacks(fade); fadingScheduled = false;
        removeCallbacks(cleanIdle); completedStrokes = 0;
        trail.clear();
        if (bitmap != null) bitmap.recycle();
        bitmap = null; ink = null; stroke = false;
    }

    private void scheduleFade() {
        if (fadingScheduled || !previewEnabled || !options.fadingInk || !trail.hasFinishedStroke() || !isShown()) return;
        long wait = Math.max(refresh.fadeIntervalMillis(), trail.nextFadeAt(options.fadeDelayMs) - SystemClock.uptimeMillis());
        fadingScheduled = true; postDelayed(fade, wait);
    }

    public void refreshSettings() {
        updateGeometry(); refreshTiltSettings();
        if (previewEnabled != controller.preview()) {
            previewEnabled = controller.preview(); clearVisuals(); refresh.clean();
        }
        if (clearGeneration != controller.clearGeneration()) {
            clearGeneration = controller.clearGeneration(); clearVisuals(); refresh.clean();
        }
        MappingState mapping = controller.mapping();
        long revision = mapping == null ? 0 : mapping.revision;
        if (mappingRevision != revision) { mappingRevision = revision; refresh.clean(); }
        scheduleFade();
    }

    private int displayRotation() { return getDisplay() == null ? 0 : getDisplay().getRotation(); }
    private void refreshTiltSettings() {
        if (tiltRevision == controller.tiltRevision()) return;
        input.setTiltMode(controller.tiltMode()); input.resetTiltDetection();
        tiltRevision = controller.tiltRevision();
    }
    public void updateGeometry() {
        if (getDisplay() != null && controller.setInputGeometry(Math.round(active.width()),
            Math.round(active.height()), displayRotation())) {
            input.cancel(); stroke = false;
        }
    }
    public void cancelInput() {
        input.cancel(); stroke = false; trail.finish(SystemClock.uptimeMillis());
        removeCallbacks(fade); removeCallbacks(cleanIdle); fadingScheduled = false;
    }
    @Override protected void onDetachedFromWindow() {
        cancelInput(); refresh.cancel(); super.onDetachedFromWindow();
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
