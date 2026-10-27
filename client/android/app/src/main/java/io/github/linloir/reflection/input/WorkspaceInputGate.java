package io.github.linloir.reflection.input;

import android.view.MotionEvent;

/** Reject unrelated events before workspace bounds or edge gestures can cancel the captured pen. */
public final class WorkspaceInputGate {
    private WorkspaceInputGate() {}
    public static boolean consumes(MotionEvent event, int penIndex, boolean contact, int device, int pointer) {
        int action = event.getActionMasked();
        if (contact) {
            if (event.getDeviceId() != device || event.getPointerId(penIndex) != pointer) return true;
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_HOVER_EXIT) return true;
        }
        return (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            && event.getActionIndex() != penIndex;
    }
}
