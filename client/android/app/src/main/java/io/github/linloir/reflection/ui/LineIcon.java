package io.github.linloir.reflection.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Original geometric marks drawn on a 48-unit grid with square line endings. */
public final class LineIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    public LineIcon(Context context, String name) {
        super(context); this.name = name;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float size = Math.min(getWidth(), getHeight());
        c.save(); c.translate((getWidth() - size) / 2, (getHeight() - size) / 2); c.scale(size / 48, size / 48);
        paint.setColor(0xff222824); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.SQUARE); paint.setStrokeJoin(Paint.Join.MITER);
        if ("welcome".equals(name)) {
            c.drawRect(6, 8, 28, 31, paint); c.drawRect(20, 17, 42, 40, paint);
            line(c, 6, 40, 12, 40); line(c, 6, 36, 6, 40);
            line(c, 36, 8, 42, 8); line(c, 42, 8, 42, 12);
            line(c, 24, 3, 24, 12); line(c, 24, 36, 24, 45);
        } else if ("calibrate".equals(name)) {
            corner(c, 7, 7, 1, 1); corner(c, 41, 7, -1, 1);
            corner(c, 7, 41, 1, -1); corner(c, 41, 41, -1, -1);
            line(c, 24, 14, 24, 34); line(c, 14, 24, 34, 24);
            c.drawRect(21, 21, 27, 27, paint);
        } else if ("vertical".equals(name) || "horizontal".equals(name)) {
            if ("horizontal".equals(name)) c.rotate(90, 24, 24);
            line(c, 24, 6, 24, 39); line(c, 19, 11, 24, 6); line(c, 29, 11, 24, 6);
            line(c, 19, 34, 24, 39); line(c, 29, 34, 24, 39);
            line(c, 8, 24, 17, 24); line(c, 31, 24, 40, 24);
        } else if ("host".equals(name) || "screens".equals(name)) {
            c.drawRect(5, 9, 43, 33, paint); line(c, 24, 33, 24, 40); line(c, 15, 40, 33, 40);
            if ("host".equals(name)) {
                line(c, 24, 14, 24, 27); line(c, 19, 22, 24, 27); line(c, 29, 22, 24, 27);
            } else line(c, 24, 9, 24, 33);
        } else if ("connect".equals(name)) {
            c.drawRect(4, 17, 15, 31, paint); c.drawRect(33, 11, 44, 37, paint);
            line(c, 15, 24, 33, 24); line(c, 20, 20, 24, 24); line(c, 24, 24, 20, 28);
        } else if ("settings".equals(name)) {
            for (int y = 12; y <= 36; y += 12) line(c, 7, y, 41, y);
            c.drawRect(15, 8, 21, 16, paint); c.drawRect(28, 20, 34, 28, paint); c.drawRect(13, 32, 19, 40, paint);
        } else if ("clear".equals(name)) {
            Path p = new Path(); p.moveTo(9, 29); p.lineTo(27, 9); p.lineTo(41, 22); p.lineTo(24, 40);
            p.lineTo(19, 40); p.close(); c.drawPath(p, paint); line(c, 17, 20, 32, 32); line(c, 9, 41, 42, 41);
        } else if ("preview".equals(name)) {
            Path p = new Path(); p.moveTo(4, 24); p.lineTo(15, 14); p.lineTo(33, 14); p.lineTo(44, 24);
            p.lineTo(33, 34); p.lineTo(15, 34); p.close(); c.drawPath(p, paint); c.drawRect(20, 20, 28, 28, paint);
        } else if ("touch".equals(name)) {
            c.drawRect(8, 7, 40, 41, paint); line(c, 8, 33, 40, 33);
            line(c, 20, 17, 20, 26); line(c, 28, 17, 28, 26);
        } else if ("more".equals(name)) {
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(9, 21, 15, 27, paint); c.drawRect(21, 21, 27, 27, paint); c.drawRect(33, 21, 39, 27, paint);
        } else {
            line(c, 10, 14, 38, 14); line(c, 10, 24, 38, 24); line(c, 10, 34, 38, 34);
        }
        c.restore();
    }
    private void line(Canvas c, float x, float y, float x2, float y2) { c.drawLine(x, y, x2, y2, paint); }
    private void corner(Canvas c, float x, float y, int dx, int dy) {
        line(c, x, y, x + dx * 8, y); line(c, x, y, x, y + dy * 8);
    }
}
