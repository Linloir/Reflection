package io.github.linloir.reflection.core;

/** Relative touch input. Deltas use Android density-independent pixels, not tablet coordinates. */
public final class TouchpadEvent {
    public static final int MOVE = 0, LEFT_TAP = 1, RIGHT_TAP = 2, SCROLL_BEGIN = 3,
        SCROLL = 4, SCROLL_END = 5, ZOOM = 6, SWIPE = 7, CANCEL = 8;
    public final int kind, fingers;
    public final float x, y;
    public final long timeNanos;

    public TouchpadEvent(int kind, int fingers, float x, float y, long timeNanos) {
        if (kind < MOVE || kind > CANCEL || fingers < 0 || fingers > 4
            || Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)
            || Math.abs(x) > 1024 || Math.abs(y) > 1024
            || timeNanos < 0) throw new IllegalArgumentException("Invalid touchpad event");
        if ((kind == MOVE || kind == LEFT_TAP) && fingers != 1
            || (kind >= RIGHT_TAP && kind <= ZOOM) && fingers != 2
            || kind == SWIPE && (fingers < 3 || Math.abs(x) + Math.abs(y) != 1 || x != Math.rint(x) || y != Math.rint(y))
            || kind == ZOOM && (Math.abs(x) > 1 || y != 0)
            || (kind == LEFT_TAP || kind == RIGHT_TAP || kind == SCROLL_BEGIN || kind == SCROLL_END || kind == CANCEL) && (x != 0 || y != 0)
            || kind == CANCEL && fingers != 0) throw new IllegalArgumentException("Invalid touchpad gesture");
        this.kind = kind; this.fingers = fingers; this.x = x; this.y = y; this.timeNanos = timeNanos;
    }
}
