package io.github.linloir.reflection.eink;

import android.graphics.Rect;
import android.view.View;
import java.lang.reflect.Method;

/**
 * The same view-scoped bridge used by Onyx's SDM device SDK. Firmware supplies both
 * method and waveform constants; no numeric waveform is guessed or globally installed.
 */
final class OnyxRefreshDriver implements RefreshDriver {
    private final Method invalidate;
    private final int fast, gray, clean;
    private boolean available = true;

    private OnyxRefreshDriver(Method invalidate, int fast, int gray, int clean) {
        this.invalidate = invalidate; this.fast = fast; this.gray = gray; this.clean = clean;
    }

    static RefreshDriver discover() {
        try {
            Class<?> helper = Class.forName("android.onyx.ViewUpdateHelper");
            int fast = helper.getField("UI_DU_MODE").getInt(null);
            int gray = helper.getField("UI_GU_MODE").getInt(null);
            int clean = helper.getField("UI_GC_MODE").getInt(null);
            if (fast == gray || fast == clean || gray == clean) return null;
            Method method;
            try {
                method = View.class.getMethod("invalidate", int.class, int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                method = View.class.getMethod("invalidateWithUpdateMode", int.class, int.class, int.class, int.class, int.class);
            }
            return new OnyxRefreshDriver(method, fast, gray, clean);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    @Override public String name() { return available ? "BOOX regional refresh" : "Android regional redraw (BOOX API unavailable)"; }
    @Override public boolean regionalWaveforms() { return available; }
    @Override public void refresh(View view, Rect dirty, Update update) {
        if (available) {
            try {
                invalidate.invoke(view, dirty.left, dirty.top, dirty.right, dirty.bottom,
                    update == Update.INK ? fast : update == Update.FADE ? gray : clean);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                available = false;
            }
        }
        view.invalidate(dirty);
    }
}
