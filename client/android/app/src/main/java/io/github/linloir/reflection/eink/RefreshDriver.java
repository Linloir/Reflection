package io.github.linloir.reflection.eink;

import android.graphics.Rect;
import android.view.View;

/** A refresh request affects only this application's view, never system-wide display policy. */
public interface RefreshDriver {
    enum Update { INK, FADE, CLEAN }
    String name();
    boolean regionalWaveforms();
    void refresh(View view, Rect dirty, Update update);
}
