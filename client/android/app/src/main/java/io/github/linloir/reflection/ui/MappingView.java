package io.github.linloir.reflection.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import io.github.linloir.reflection.core.MappingState;
import java.util.ArrayList;
import java.util.List;

/** A view of the host's screen rectangles in their real desktop arrangement. */
public final class MappingView extends View {
    public interface Selection { void select(String displayId); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> rectangles = new ArrayList<>();
    private final Selection selection;
    private MappingState state;
    private float downX, downY;
    private boolean moved;

    public MappingView(Context context, Selection selection) {
        super(context); this.selection = selection;
        setFocusable(true); setContentDescription("Computer screen layout; tap a screen to select it");
    }

    public void update(MappingState next) {
        if (state == next) return;
        state = next; invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); rectangles.clear();
        if (state == null) return;
        double left = Double.POSITIVE_INFINITY, top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
        for (MappingState.Display display : state.displays) {
            MappingState.Region r = display.bounds;
            left = Math.min(left, r.x); top = Math.min(top, r.y);
            right = Math.max(right, r.x + r.width); bottom = Math.max(bottom, r.y + r.height);
        }
        float padding = dp(10);
        double scale = Math.min((getWidth() - padding * 2) / (right - left), (getHeight() - padding * 2) / (bottom - top));
        if (scale <= 0 || Double.isInfinite(scale) || Double.isNaN(scale)) return;
        double ox = (getWidth() - (right - left) * scale) / 2;
        double oy = (getHeight() - (bottom - top) * scale) / 2;
        for (int i = 0; i < state.displays.size(); i++) {
            MappingState.Display display = state.displays.get(i);
            MappingState.Region r = display.bounds;
            RectF box = new RectF((float) (ox + (r.x - left) * scale), (float) (oy + (r.y - top) * scale),
                (float) (ox + (r.x + r.width - left) * scale), (float) (oy + (r.y + r.height - top) * scale));
            rectangles.add(box);
            boolean selected = state.mode.equals("all") || display.id.equals(state.selected);
            paint.setStyle(Paint.Style.FILL); paint.setColor(selected ? 0xffd8e5ec : 0xffeeeeee);
            canvas.drawRect(box, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(selected ? 3 : 1)); paint.setColor(Color.BLACK);
            RectF border = new RectF(box); border.inset(dp(2), dp(2)); canvas.drawRect(border, paint);
            canvas.save(); canvas.clipRect(border);
            paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(22));
            canvas.drawText(String.valueOf(i + 1), box.centerX(), box.centerY(), paint);
            paint.setTextSize(dp(10));
            canvas.drawText(display.primary ? "Primary" : "Display " + (i + 1), box.centerX(), box.centerY() + dp(16), paint);
            canvas.restore();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (state == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); moved = false; return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) moved = true;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!moved) for (int i = 0; i < rectangles.size(); i++) {
                if (rectangles.get(i).contains(event.getX(), event.getY())) {
                    selection.select(state.displays.get(i).id); performClick(); break;
                }
            }
            return true;
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
