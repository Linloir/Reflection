package io.github.linloir.reflection.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded local visual trail. It never filters or delays transmitted pen samples. */
public final class InkTrail {
    public static final int MAX_SEGMENTS = 8192;
    public static final int MAX_STROKES = 512;
    public static final class Segment {
        public final float x1, y1, x2, y2, width;
        public boolean erased;
        public int displayedAlpha = 255;
        Segment(float x1, float y1, float x2, float y2, float width) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.width = width;
        }
    }
    public static final class Stroke {
        private final ArrayList<Segment> segments = new ArrayList<>();
        private long endedAt = -1;
        public List<Segment> segments() { return Collections.unmodifiableList(segments); }
        public long endedAt() { return endedAt; }
    }
    private final ArrayList<Stroke> strokes = new ArrayList<>();
    private Stroke active;
    private float lastX, lastY;
    private int count;

    public List<Stroke> strokes() { return Collections.unmodifiableList(strokes); }
    public int segmentCount() { return count; }
    public boolean empty() { return strokes.isEmpty(); }

    public void begin(float x, float y, float width, long now) {
        finish(now);
        active = new Stroke(); strokes.add(active);
        lastX = x; lastY = y;
        append(x, y, width);
    }

    public void append(float x, float y, float width) {
        if (active == null) return;
        active.segments.add(new Segment(lastX, lastY, x, y, Math.max(1, width)));
        count++; lastX = x; lastY = y;
        while (strokes.size() > MAX_STROKES || count > MAX_SEGMENTS) {
            Stroke first = strokes.get(0);
            if (first == active) {
                first.segments.remove(0); count--;
            } else {
                count -= first.segments.size(); strokes.remove(0);
            }
        }
    }

    public void finish(long now) {
        if (active != null) active.endedAt = Math.max(0, now);
        active = null;
    }

    public void clear() { active = null; strokes.clear(); count = 0; }

    public void erase(float x, float y, float radius) {
        erase(x, y, x, y, radius);
    }

    public void erase(float x1, float y1, float x2, float y2, float radius) {
        double squared = radius * radius;
        for (Stroke stroke : strokes) for (Segment segment : stroke.segments) {
            if (segment.erased) continue;
            double vx = x2 - x1, vy = y2 - y1;
            double wx = segment.x2 - segment.x1, wy = segment.y2 - segment.y1;
            double dx = segment.x1 - x1, dy = segment.y1 - y1, cross = vx * wy - vy * wx;
            if (Math.abs(cross) > 1e-9) {
                double alongEraser = (dx * wy - dy * wx) / cross;
                double alongInk = (dx * vy - dy * vx) / cross;
                if (alongEraser >= 0 && alongEraser <= 1 && alongInk >= 0 && alongInk <= 1) {
                    segment.erased = true; continue;
                }
            }
            segment.erased = distanceSquared(x1, y1, segment.x1, segment.y1, segment.x2, segment.y2) <= squared
                || distanceSquared(x2, y2, segment.x1, segment.y1, segment.x2, segment.y2) <= squared
                || distanceSquared(segment.x1, segment.y1, x1, y1, x2, y2) <= squared
                || distanceSquared(segment.x2, segment.y2, x1, y1, x2, y2) <= squared;
        }
    }

    private static double distanceSquared(double x, double y, double x1, double y1, double x2, double y2) {
        double vx = x2 - x1, vy = y2 - y1, length = vx * vx + vy * vy;
        double t = length > 0 ? Math.max(0, Math.min(1, ((x - x1) * vx + (y - y1) * vy) / length)) : 0;
        double dx = x - x1 - t * vx, dy = y - y1 - t * vy;
        return dx * dx + dy * dy;
    }

    public static int alpha(Stroke stroke, int index, long now, int delayMillis, int durationMillis, boolean eink) {
        if (stroke.segments.get(index).erased) return 0;
        if (stroke.endedAt < 0) return 255;
        int size = stroke.segments.size();
        long sweep = size <= 1 ? 0 : (size - 1L - index) * 500L / (size - 1L);
        long age = now - stroke.endedAt - delayMillis - sweep;
        if (age <= 0) return 255;
        if (age >= durationMillis) return 0;
        int alpha = (int) (255L * (durationMillis - age) / Math.max(1, durationMillis));
        return eink ? Math.min(255, ((alpha + 63) / 64) * 64) : alpha;
    }

    public boolean prune(long now, int delayMillis, int durationMillis) {
        boolean changed = false;
        for (int i = strokes.size() - 1; i >= 0; i--) {
            Stroke stroke = strokes.get(i);
            if (stroke.endedAt >= 0 && now - stroke.endedAt >= delayMillis + 500L + durationMillis) {
                count -= stroke.segments.size(); strokes.remove(i); changed = true;
            }
        }
        return changed;
    }

    public boolean hasFinishedStroke() {
        for (Stroke stroke : strokes) if (stroke.endedAt >= 0) return true;
        return false;
    }

    public long nextFadeAt(int delayMillis) {
        long next = Long.MAX_VALUE;
        for (Stroke stroke : strokes) if (stroke.endedAt >= 0)
            next = Math.min(next, stroke.endedAt + delayMillis);
        return next;
    }
}
