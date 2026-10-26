package io.github.linloir.reflection.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import io.github.linloir.reflection.control.Controller;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.input.PenInput;

/** Replaceable demo renderer. Input capture and network operation do not depend on it. */
public final class InkView extends View {
    private final Controller controller;
    private final PenInput input;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;
    private Canvas ink;
    private float lastX, lastY;
    private boolean stroke;
    private int clearGeneration;
    private long tiltRevision = -1;

    public InkView(Context context, Controller controller) {
        super(context); this.controller = controller;
        paint.setStrokeCap(Paint.Cap.ROUND); setBackgroundColor(Color.WHITE);
        input = new PenInput(sample -> {
            drawSample(controller.accept(sample));
        });
        setFocusable(true);
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        input.cancel(); stroke = false;
        updateGeometry();
        if (bitmap != null) bitmap.recycle();
        bitmap = null; ink = null;
        if (w > 0 && h > 0) { bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); ink = new Canvas(bitmap); ink.drawColor(Color.WHITE); }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        updateGeometry();
        if (Build.VERSION.SDK_INT >= 30 && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int tool = event.getToolType(event.getActionIndex());
            // Older ViewRootImpl can strand pending batches when switching to unbuffered input.
            // Keep frame batching there; PenInput still consumes every historical sample.
            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER)
                requestUnbufferedDispatch(event);
        }
        refreshTiltSettings();
        input.onEvent(event, getWidth(), getHeight(), displayRotation());
        return true;
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        updateGeometry();
        refreshTiltSettings();
        return input.onEvent(event, getWidth(), getHeight(), displayRotation()) || super.onGenericMotionEvent(event);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null && controller.preview()) canvas.drawBitmap(bitmap, 0, 0, null);
        else if (!controller.preview()) {
            paint.setColor(Color.LTGRAY); paint.setTextSize(24 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("Pen input area", 24, 48, paint);
        }
    }
    private void drawSample(PenSample p) {
        if (!controller.preview() || ink == null) { stroke = false; return; }
        float x = p.x * Math.max(1, getWidth() - 1), y = p.y * Math.max(1, getHeight() - 1);
        paint.setColor(p.tool == PenSample.ERASER ? Color.WHITE : Color.BLACK);
        float width = p.tool == PenSample.ERASER ? 30 : 1 + p.pressure * 8;
        paint.setStrokeWidth(width);
        if (p.phase == PenSample.DOWN) { ink.drawPoint(x, y, paint); stroke = true; }
        else if (p.phase == PenSample.MOVE && stroke) ink.drawLine(lastX, lastY, x, y, paint);
        else if (p.phase == PenSample.UP || p.phase == PenSample.CANCEL || p.phase == PenSample.LEAVE) stroke = false;
        if (stroke) invalidate((int) Math.min(lastX, x) - 34, (int) Math.min(lastY, y) - 34,
            (int) Math.max(lastX, x) + 34, (int) Math.max(lastY, y) + 34);
        lastX = x; lastY = y;
    }
    public void refreshSettings() {
        updateGeometry();
        refreshTiltSettings();
        if (clearGeneration != controller.clearGeneration()) {
            clearGeneration = controller.clearGeneration(); if (ink != null) ink.drawColor(Color.WHITE); invalidate();
        }
    }
    private int displayRotation() { return getDisplay() == null ? 0 : getDisplay().getRotation(); }
    private void refreshTiltSettings() {
        if (tiltRevision == controller.tiltRevision()) return;
        input.setTiltMode(controller.tiltMode());
        input.resetTiltDetection();
        tiltRevision = controller.tiltRevision();
    }
    public void updateGeometry() {
        if (getDisplay() != null && controller.setInputGeometry(getWidth(), getHeight(), getDisplay().getRotation())) {
            input.cancel(); stroke = false;
        }
    }
    public void cancelInput() { input.cancel(); stroke = false; }
}
