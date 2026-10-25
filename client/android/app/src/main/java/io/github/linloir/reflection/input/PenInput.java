package io.github.linloir.reflection.input;

import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.linloir.reflection.core.PenSample;

/** Android adapter; neither the transport nor protocol depends on MotionEvent. */
public final class PenInput {
    public interface Sink { void accept(PenSample sample); }
    private final Sink sink;
    private long sequence;
    private PenSample last;
    private int activePointer = -1;
    private boolean touching;
    private static volatile long invalidTiltSamples;
    private static volatile float lastRawTilt, lastRawOrientation;
    private static volatile boolean tiltReliable;
    public static long invalidTiltSamples() { return invalidTiltSamples; }
    public static float lastRawTilt() { return lastRawTilt; }
    public static float lastRawOrientation() { return lastRawOrientation; }
    public static boolean tiltReliable() { return tiltReliable; }

    public PenInput(Sink sink) { this.sink = sink; }

    public boolean onEvent(MotionEvent event, int width, int height) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) { cancel(); return true; }
        if (activePointer >= 0) {
            int activeIndex = event.findPointerIndex(activePointer);
            if (activeIndex < 0) { cancel(); return false; }
            if ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
                && event.getPointerId(index) != activePointer) return true;
            index = activeIndex;
        } else {
            boolean found = false;
            for (int i = 0; i < event.getPointerCount(); i++) {
                int tool = event.getToolType(i);
                if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                    index = i; found = true; break;
                }
            }
            if (!found) return false;
        }
        int tool = event.getToolType(index);
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return false;
        int phase;
        switch (action) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN:
                touching = true; activePointer = event.getPointerId(index); phase = PenSample.DOWN; break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_POINTER_UP:
                touching = false; phase = PenSample.UP; break;
            case MotionEvent.ACTION_HOVER_ENTER: case MotionEvent.ACTION_HOVER_MOVE:
                phase = PenSample.HOVER; break;
            case MotionEvent.ACTION_HOVER_EXIT: phase = PenSample.LEAVE; break;
            case MotionEvent.ACTION_MOVE: phase = touching ? PenSample.MOVE : PenSample.HOVER; break;
            case MotionEvent.ACTION_BUTTON_PRESS: case MotionEvent.ACTION_BUTTON_RELEASE:
                phase = touching ? PenSample.MOVE : PenSample.HOVER; break;
            default: return true;
        }
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
            for (int h = 0; h < event.getHistorySize(); h++) emit(event, index, h, width, height, phase);
        }
        emit(event, index, -1, width, height, phase);
        if (phase == PenSample.UP || phase == PenSample.LEAVE) activePointer = -1;
        return true;
    }

    private void emit(MotionEvent e, int i, int history, int width, int height, int phase) {
        InputDevice d = e.getDevice();
        int source = e.getSource();
        InputDevice.MotionRange pressureRange = range(d, MotionEvent.AXIS_PRESSURE, source);
        InputDevice.MotionRange tiltRange = range(d, MotionEvent.AXIS_TILT, source);
        InputDevice.MotionRange orientationRange = range(d, MotionEvent.AXIS_ORIENTATION, source);
        InputDevice.MotionRange distanceRange = range(d, MotionEvent.AXIS_DISTANCE, source);
        float x = history < 0 ? e.getX(i) : e.getHistoricalX(i, history);
        float y = history < 0 ? e.getY(i) : e.getHistoricalY(i, history);
        float pressure = normalized(axis(e, MotionEvent.AXIS_PRESSURE, i, history), pressureRange);
        float tilt = axis(e, MotionEvent.AXIS_TILT, i, history);
        float orientation = axis(e, MotionEvent.AXIS_ORIENTATION, i, history);
        lastRawTilt = tilt; lastRawOrientation = orientation;
        boolean hasTilt = tiltRange != null && orientationRange != null;
        boolean validTilt = finite(tilt) && finite(orientation) && tilt >= 0 && tilt <= Math.PI / 2 + 0.000001
            && orientation >= -Math.PI - 0.000001 && orientation <= Math.PI + 0.000001;
        tiltReliable = hasTilt && validTilt;
        if (hasTilt && !validTilt) invalidTiltSamples++;
        if (!tiltReliable) { tilt = 0; orientation = 0; }
        // Android azimuth is clockwise from up; host tilt is degrees towards right/down.
        float tiltX = (float) Math.toDegrees(Math.atan2(Math.sin(orientation) * Math.sin(tilt), Math.cos(tilt)));
        float tiltY = (float) Math.toDegrees(Math.atan2(-Math.cos(orientation) * Math.sin(tilt), Math.cos(tilt)));
        int caps = 0;
        if (pressureRange != null) caps |= 1;
        if (tiltReliable) caps |= 2;
        if (distanceRange != null) caps |= 4;
        tiltX = finite(tiltX) ? Math.max(-90, Math.min(90, tiltX)) : 0;
        tiltY = finite(tiltY) ? Math.max(-90, Math.min(90, tiltY)) : 0;
        if (phase == PenSample.UP || phase == PenSample.CANCEL || phase == PenSample.HOVER || phase == PenSample.LEAVE) pressure = 0;
        int buttons = 0;
        // Android 5.x reports pen buttons through the older mouse button bits.
        if ((e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0) buttons |= 1;
        if ((e.getButtonState() & (MotionEvent.BUTTON_STYLUS_SECONDARY | MotionEvent.BUTTON_TERTIARY)) != 0) buttons |= 2;
        long millis = history < 0 ? e.getEventTime() : e.getHistoricalEventTime(history);
        last = new PenSample(++sequence, millis * 1000000L, clamp(x / Math.max(1, width - 1)),
            clamp(y / Math.max(1, height - 1)), pressure, tiltX, tiltY,
            normalized(axis(e, MotionEvent.AXIS_DISTANCE, i, history), distanceRange),
            phase, e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ? PenSample.ERASER : PenSample.PEN,
            buttons, caps);
        sink.accept(last);
    }

    public void cancel() {
        // Hover can hold barrel buttons or proximity without an active touch pointer.
        if (last != null && last.phase != PenSample.CANCEL && last.phase != PenSample.LEAVE) {
            last = last.cancelled(++sequence); sink.accept(last);
        }
        touching = false; activePointer = -1;
    }

    private static InputDevice.MotionRange range(InputDevice d, int axis, int source) {
        return d == null ? null : d.getMotionRange(axis, source);
    }
    private static float axis(MotionEvent e, int axis, int i, int h) {
        return h < 0 ? e.getAxisValue(axis, i) : e.getHistoricalAxisValue(axis, i, h);
    }
    private static float normalized(float value, InputDevice.MotionRange range) {
        return range != null && range.getRange() > 0 ? clamp((value - range.getMin()) / range.getRange()) : clamp(value);
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static float clamp(float value) { return Float.isNaN(value) ? 0 : Math.max(0, Math.min(1, value)); }
}
