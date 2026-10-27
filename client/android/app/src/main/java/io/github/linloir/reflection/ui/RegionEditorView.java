package io.github.linloir.reflection.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class RegionEditorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final UiPreferences draft;
    private float downX, downY;
    RegionEditorView(Context context, UiPreferences draft) {
        super(context); this.draft = draft;
        setContentDescription("拖动以选择操作区域，最小为屏幕的百分之二十");
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        paint.setColor(0xfff1f3ed); paint.setStyle(Paint.Style.FILL); c.drawRect(0, 0, getWidth(), getHeight(), paint);
        RectF r = new RectF(draft.left * getWidth(), draft.top * getHeight(), draft.right * getWidth(), draft.bottom * getHeight());
        paint.setColor(0xffffffff); c.drawRect(r, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setColor(0xff323d34); c.drawRect(r, paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity); paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(Math.round((draft.right - draft.left) * 100) + "% × " + Math.round((draft.bottom - draft.top) * 100) + "%",
            r.centerX(), r.centerY(), paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = Math.max(0, Math.min(1, event.getX() / getWidth()));
        float y = Math.max(0, Math.min(1, event.getY() / getHeight()));
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = x; downY = y; return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE || event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (Math.abs(x - downX) >= .2 && Math.abs(y - downY) >= .2) {
                draft.left = Math.min(x, downX); draft.top = Math.min(y, downY);
                draft.right = Math.max(x, downX); draft.bottom = Math.max(y, downY); invalidate();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
