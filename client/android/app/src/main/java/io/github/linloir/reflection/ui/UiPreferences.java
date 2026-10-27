package io.github.linloir.reflection.ui;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.linloir.reflection.control.Controller;

/** A settings draft is detached from storage until the user chooses Save. */
public final class UiPreferences {
    public String toolbar = "bottom", background = "dots", eink = "auto", edgeGesture = "hover";
    public boolean collapsed, overlay, border = true, screenBorders = true;
    public boolean fading, touchpad, edgeSwitch;
    public boolean localPreview;
    public String tiltMode = "auto";
    public boolean toolScreens = true, toolClear = true, toolPreview = true, toolTouchpad = true;
    public int spacing = 24, margin = 24, fadeDelay = 1500, hoverDelay = 800;
    public float left, top, right = 1, bottom = 1;

    public static UiPreferences load(Context context) {
        SharedPreferences p = store(context);
        UiPreferences value = new UiPreferences();
        value.localPreview = Controller.get(context).preview();
        value.tiltMode = Controller.get(context).tiltMode();
        value.toolbar = p.getString("toolbar", "bottom");
        value.background = p.getString("background", "dots");
        value.eink = p.getString("eink", "auto");
        value.edgeGesture = p.getString("edgeGesture", "hover");
        value.collapsed = p.getBoolean("collapsed", false);
        value.overlay = p.getBoolean("overlay", false);
        value.border = p.getBoolean("border", true);
        value.screenBorders = p.getBoolean("screenBorders", true);
        value.fading = p.getBoolean("fading", false);
        value.touchpad = p.getBoolean("touchpad", false);
        value.edgeSwitch = p.getBoolean("edgeSwitch", false);
        value.toolScreens = p.getBoolean("toolScreens", true);
        value.toolClear = p.getBoolean("toolClear", true);
        value.toolPreview = p.getBoolean("toolPreview", true);
        value.toolTouchpad = p.getBoolean("toolTouchpad", true);
        value.spacing = p.getInt("spacing", 24);
        value.margin = p.getInt("margin", 24);
        value.fadeDelay = p.getInt("fadeDelay", 1500);
        value.hoverDelay = p.getInt("hoverDelay", 800);
        value.left = p.getFloat("left", 0); value.top = p.getFloat("top", 0);
        value.right = p.getFloat("right", 1); value.bottom = p.getFloat("bottom", 1);
        return value;
    }

    public void save(Context context) {
        Controller.get(context).setPreview(localPreview);
        Controller.get(context).setTiltMode(tiltMode);
        store(context).edit().putString("toolbar", toolbar).putString("background", background)
            .putString("eink", eink).putString("edgeGesture", edgeGesture)
            .putBoolean("collapsed", collapsed).putBoolean("overlay", overlay)
            .putBoolean("border", border).putBoolean("screenBorders", screenBorders)
            .putBoolean("fading", fading).putBoolean("touchpad", touchpad).putBoolean("edgeSwitch", edgeSwitch)
            .putBoolean("toolScreens", toolScreens).putBoolean("toolClear", toolClear)
            .putBoolean("toolPreview", toolPreview).putBoolean("toolTouchpad", toolTouchpad)
            .putInt("spacing", spacing).putInt("margin", margin).putInt("fadeDelay", fadeDelay)
            .putInt("hoverDelay", hoverDelay)
            .putFloat("left", left).putFloat("top", top).putFloat("right", right).putFloat("bottom", bottom)
            .apply();
    }

    public InkView.WorkspaceOptions workspace() {
        InkView.WorkspaceOptions options = new InkView.WorkspaceOptions();
        options.einkMode = eink; options.background = background; options.backgroundSpacingDp = spacing;
        options.showBorder = border; options.showScreenBoundaries = screenBorders; options.marginDp = margin;
        options.regionLeft = left; options.regionTop = top; options.regionRight = right; options.regionBottom = bottom;
        options.fadingInk = fading; options.fadeDelayMs = fadeDelay; options.fadeDurationMs = 900;
        return options;
    }

    public static SharedPreferences store(Context context) {
        return context.getSharedPreferences("reflection_ui", Context.MODE_PRIVATE);
    }
}
