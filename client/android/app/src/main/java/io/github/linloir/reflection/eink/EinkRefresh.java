package io.github.linloir.reflection.eink;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import java.util.Locale;

/** Device selection and bounded redraw scheduling are separate from pen sampling. */
public final class EinkRefresh {
    private final View view;
    private final RefreshDriver driver;
    private final boolean enabled;
    private final Rect pending = new Rect();
    private RefreshDriver.Update pendingUpdate = RefreshDriver.Update.INK;
    private boolean scheduled;
    private final Runnable flush = new Runnable() {
        @Override public void run() {
            scheduled = false;
            if (pending.isEmpty()) return;
            Rect dirty = new Rect(pending); pending.setEmpty();
            RefreshDriver.Update update = pendingUpdate;
            pendingUpdate = RefreshDriver.Update.INK;
            driver.refresh(view, dirty, update);
        }
    };

    public EinkRefresh(View view, String mode) {
        this.view = view;
        enabled = isEnabled(view.getContext(), mode);
        RefreshDriver candidate = enabled ? OnyxRefreshDriver.discover() : null;
        driver = candidate != null ? candidate : new AndroidRefreshDriver(
            enabled ? "Android regional redraw / e-paper cadence" : "Android regional redraw");
    }

    public static boolean isEnabled(Context context, String mode) {
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        return detected();
    }

    public static boolean detected() {
        String identity = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        if (isOnyx() || identity.contains("bigme") || identity.contains("boyue")
            || identity.contains("meebook") || identity.contains("inkbook")
            || identity.contains("dasung") || identity.contains("pocketbook")
            || identity.contains("moaan") || identity.contains("reinkstone")) return true;
        try { Class.forName("android.onyx.ViewUpdateHelper"); return true; }
        catch (ClassNotFoundException | LinkageError | SecurityException unavailable) { return false; }
    }

    private static boolean isOnyx() {
        String identity = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return identity.contains("onyx") || identity.contains("boox");
    }

    public boolean enabled() { return enabled; }
    public String driverName() { return driver.name(); }
    public boolean regionalWaveforms() { return driver.regionalWaveforms(); }
    public int fadeIntervalMillis() { return enabled ? 120 : 32; }

    public void request(Rect dirty, RefreshDriver.Update update) {
        Rect clipped = new Rect(dirty);
        if (!clipped.intersect(0, 0, view.getWidth(), view.getHeight())) return;
        pending.union(clipped);
        if (update.ordinal() > pendingUpdate.ordinal()) pendingUpdate = update;
        if (!scheduled) {
            scheduled = true;
            view.postDelayed(flush, enabled ? 20 : 0);
        }
    }

    public void clean() {
        request(new Rect(0, 0, view.getWidth(), view.getHeight()), RefreshDriver.Update.CLEAN);
    }

    public void cancel() {
        view.removeCallbacks(flush); scheduled = false; pending.setEmpty();
        pendingUpdate = RefreshDriver.Update.INK;
    }
}
