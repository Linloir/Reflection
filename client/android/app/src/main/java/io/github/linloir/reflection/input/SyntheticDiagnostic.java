package io.github.linloir.reflection.input;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/** Explicit adb-only diagnostic: exercises MotionEvent adapter, never a hardware test. */
public final class SyntheticDiagnostic {
    private SyntheticDiagnostic() {}
    public static int run(PenInput.Sink sink) {
        PenInput input = new PenInput(sink);
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_STYLUS;
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i < 6; i++) {
            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.x = 200 + i * 100; coords.y = 300 + i * 40;
            coords.pressure = i == 5 ? 0 : 0.2f + i * 0.15f;
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 5 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(start, start + i, action, 1,
                new MotionEvent.PointerProperties[]{property}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, -1, 0, InputDevice.SOURCE_STYLUS, 0);
            input.onEvent(event, 1001, 1001); event.recycle();
        }
        return 6;
    }
}
