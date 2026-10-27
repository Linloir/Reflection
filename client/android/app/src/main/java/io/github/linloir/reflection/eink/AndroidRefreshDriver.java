package io.github.linloir.reflection.eink;

import android.graphics.Rect;
import android.view.View;

final class AndroidRefreshDriver implements RefreshDriver {
    private final String label;
    AndroidRefreshDriver(String label) { this.label = label; }
    @Override public String name() { return label; }
    @Override public boolean regionalWaveforms() { return false; }
    @Override public void refresh(View view, Rect dirty, Update update) { view.invalidate(dirty); }
}
