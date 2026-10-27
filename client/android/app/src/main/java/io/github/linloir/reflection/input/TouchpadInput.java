package io.github.linloir.reflection.input;

import android.view.MotionEvent;
import io.github.linloir.reflection.core.TouchpadEvent;
import io.github.linloir.reflection.core.RawTouchpadFrame;

/** Finger gestures are independent of pen capture and never synthesize pen samples. */
public final class TouchpadInput {
    public interface Sink { void accept(TouchpadEvent event); }
    public interface RawSupport { boolean supported(); }
    public interface RawSink { void accept(RawTouchpadFrame frame); }
    private final float density;
    private final Sink sink;
    private boolean tracking, suppressed, moved, emittedSwipe, scrolling;
    private int count, maximumCount, gesture;
    private float lastX, lastY, startX, startY, startSpan, lastSpan;
    private long started, lastTime;
    private RawSupport rawSupported = () -> false;
    private RawSink rawSink = frame -> {};
    private final RawTouchpadFrame.State rawState = new RawTouchpadFrame.State();
    private boolean rawMode, rawTracking;
    private int rawDevice;
    private long rawLastTime;
    private float left, top, width = 1, height = 1;

    public TouchpadInput(float density, Sink sink) {
        this.density = Math.max(0.5f, density); this.sink = sink;
    }

    public void setRawSink(RawSupport supported, RawSink sink) {
        cancel(); rawSupported = supported; rawSink = sink;
    }
    public void setBounds(float left, float top, float right, float bottom) {
        float width = right - left, height = bottom - top;
        if (!finite(left) || !finite(top) || !finite(width)
                || !finite(height) || width <= 0 || height <= 0) { cancel(); return; }
        if (this.left != left || this.top != top || this.width != width || this.height != height) cancel();
        this.left = left; this.top = top; this.width = width; this.height = height;
    }
    public boolean active() { return tracking || rawTracking; }

    public boolean onTouch(MotionEvent event, boolean penPresent) {
        boolean finger = false, pen = penPresent;
        for (int i = 0; i < event.getPointerCount(); i++) {
            int tool = event.getToolType(i);
            finger |= tool == MotionEvent.TOOL_TYPE_FINGER;
            pen |= tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
        }
        lastTime = Math.max(lastTime, event.getEventTime() * 1000000L);
        if (pen || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            cancel(); suppressed = true; return finger;
        }
        if (!finger) {
            if (active() || scrolling) { cancel(); suppressed = true; }
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            if (rawSupported.supported()) {
                rawMode = rawTracking = true; suppressed = false; rawDevice = event.getDeviceId();
                return rawTouch(event);
            }
            tracking = true; suppressed = false; moved = false; emittedSwipe = false;
            count = maximumCount = 1; gesture = 0; started = event.getEventTime();
            baseline(event);
            return true;
        }
        if (rawMode) return rawTouch(event);
        if (!tracking || suppressed) return true;
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
            && event.getPointerCount() == maximumCount) {
            if (Math.hypot(center(event, true) - startX, center(event, false) - startY) > 7
                || maximumCount == 2 && Math.abs(span(event) - startSpan) > 8) moved = true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            endScroll();
            count = event.getPointerCount();
            maximumCount = Math.max(maximumCount, count);
            if (maximumCount > 4) { cancel(); suppressed = true; return true; }
            gesture = 0; baseline(event);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            endScroll(); count = event.getPointerCount() - 1;
            // After a finger lifts, consume the remainder without changing gesture category.
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!moved && event.getEventTime() - started <= 300 && maximumCount <= 2)
                emit(maximumCount == 1 ? TouchpadEvent.LEFT_TAP : TouchpadEvent.RIGHT_TAP, maximumCount, 0, 0);
            endScroll();
            if (gesture == 2) emit(TouchpadEvent.CANCEL, 0, 0, 0);
            tracking = false; return true;
        }
        if (action != MotionEvent.ACTION_MOVE || count != maximumCount || event.getPointerCount() != count)
            return true;
        float x = center(event, true), y = center(event, false);
        float dx = x - lastX, dy = y - lastY;
        float distance = (float) Math.hypot(x - startX, y - startY);
        float span = span(event);
        if (distance > 7 || count == 2 && Math.abs(span - startSpan) > 8) moved = true;
        if (count == 1) {
            if (moved) emit(TouchpadEvent.MOVE, 1, bounded(dx), bounded(dy));
        } else if (count == 2) {
            if (gesture == 0 && moved) {
                gesture = Math.abs(span - startSpan) > distance * 1.3f && Math.abs(span - startSpan) > 8 ? 2 : 1;
                if (gesture == 1) { emit(TouchpadEvent.SCROLL_BEGIN, 2, 0, 0); scrolling = true; }
            }
            if (gesture == 1) emit(TouchpadEvent.SCROLL, 2, bounded(dx), bounded(dy));
            else if (gesture == 2 && lastSpan > 1 && span > 1)
                emit(TouchpadEvent.ZOOM, 2, Math.max(-1, Math.min(1, (float) Math.log(span / lastSpan))), 0);
        } else if (!emittedSwipe && distance >= 64) {
            float totalX = x - startX, totalY = y - startY;
            emit(TouchpadEvent.SWIPE, count, Math.abs(totalX) >= Math.abs(totalY) ? Math.signum(totalX) : 0,
                Math.abs(totalY) > Math.abs(totalX) ? Math.signum(totalY) : 0);
            emittedSwipe = true;
        }
        lastX = x; lastY = y; lastSpan = span;
        return true;
    }

    public void cancel() {
        if (rawTracking) rawSink.accept(RawTouchpadFrame.cancel(Math.max(rawLastTime, lastTime)));
        rawTracking = rawMode = false; rawState.reset();
        if (tracking || scrolling) emit(TouchpadEvent.CANCEL, 0, 0, 0);
        tracking = false; scrolling = false; gesture = 0;
    }

    private boolean rawTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (!rawTracking || suppressed) return true;
        if (!rawSupported.supported() || event.getDeviceId() != rawDevice
                || event.getPointerCount() > RawTouchpadFrame.MAX_CONTACTS) {
            cancel(); suppressed = true; return true;
        }
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN
                && action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_POINTER_UP
                && action != MotionEvent.ACTION_UP) return true;
        try {
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) && event.getPointerCount() != 1
                    || event.getActionIndex() < 0 || event.getActionIndex() >= event.getPointerCount())
                throw new IllegalArgumentException("Invalid contact transition");
            if (action == MotionEvent.ACTION_MOVE) {
                for (int history = 0; history < event.getHistorySize(); history++) rawFrame(event, history);
            }
            rawFrame(event, -1);
            if (action == MotionEvent.ACTION_UP) { rawTracking = rawMode = false; rawState.reset(); }
        } catch (IllegalArgumentException error) { cancel(); suppressed = true; }
        return true;
    }
    private void rawFrame(MotionEvent event, int history) {
        RawTouchpadFrame.Contact[] contacts = new RawTouchpadFrame.Contact[event.getPointerCount()];
        int action = event.getActionMasked();
        for (int i = 0; i < contacts.length; i++) {
            if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) throw new IllegalArgumentException("Not a finger");
            int phase = RawTouchpadFrame.UPDATE;
            if (history < 0 && i == event.getActionIndex()) {
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) phase = RawTouchpadFrame.DOWN;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) phase = RawTouchpadFrame.UP;
            }
            float x = history < 0 ? event.getX(i) : event.getHistoricalX(i, history);
            float y = history < 0 ? event.getY(i) : event.getHistoricalY(i, history);
            if (!finite(x) || !finite(y)) throw new IllegalArgumentException("Invalid contact location");
            contacts[i] = new RawTouchpadFrame.Contact(event.getPointerId(i), phase,
                Math.max(0, Math.min(1, (x - left) / width)), Math.max(0, Math.min(1, (y - top) / height)), true);
        }
        long time = (history < 0 ? event.getEventTime() : event.getHistoricalEventTime(history)) * 1000000L;
        rawLastTime = Math.max(rawLastTime, time);
        RawTouchpadFrame frame = new RawTouchpadFrame(contacts, false, rawLastTime);
        if (!rawState.accept(frame)) throw new IllegalArgumentException("Incomplete contact lifecycle");
        rawSink.accept(frame);
    }

    private void baseline(MotionEvent event) {
        lastX = startX = center(event, true); lastY = startY = center(event, false);
        lastSpan = startSpan = span(event);
    }
    private float center(MotionEvent event, boolean x) {
        float sum = 0;
        for (int i = 0; i < event.getPointerCount(); i++) sum += x ? event.getX(i) : event.getY(i);
        return sum / event.getPointerCount() / density;
    }
    private float span(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0;
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)) / density;
    }
    private float bounded(float value) { return Math.max(-1024, Math.min(1024, value)); }
    private void endScroll() {
        if (scrolling) emit(TouchpadEvent.SCROLL_END, 2, 0, 0);
        scrolling = false;
    }
    private void emit(int kind, int fingers, float x, float y) {
        sink.accept(new TouchpadEvent(kind, fingers, x, y, lastTime));
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
