package io.github.linloir.reflection.core;

/** Normalized client coordinates; all mapping belongs to the host. */
public final class PenSample {
    public static final int HOVER = 0, DOWN = 1, MOVE = 2, UP = 3, CANCEL = 4, LEAVE = 5;
    public static final int PEN = 1, ERASER = 2;
    public final long sequence, timeNanos;
    public final float x, y, pressure, tiltX, tiltY, distance;
    public final int phase, tool, buttons, capabilities;

    public PenSample(long sequence, long timeNanos, float x, float y, float pressure,
                     float tiltX, float tiltY, float distance, int phase, int tool,
                     int buttons, int capabilities) {
        this.sequence = sequence; this.timeNanos = timeNanos;
        this.x = x; this.y = y; this.pressure = pressure;
        this.tiltX = tiltX; this.tiltY = tiltY; this.distance = distance;
        this.phase = phase; this.tool = tool; this.buttons = buttons;
        this.capabilities = capabilities;
    }

    public boolean isMotion() { return phase == MOVE || phase == HOVER; }

    public PenSample cancelled(long sequence) {
        return new PenSample(sequence, timeNanos, x, y, 0, tiltX, tiltY,
            distance, CANCEL, tool, 0, capabilities);
    }
}
