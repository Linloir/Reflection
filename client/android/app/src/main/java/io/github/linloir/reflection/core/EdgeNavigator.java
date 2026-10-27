package io.github.linloir.reflection.core;

/** Directional neighbor selection uses actual host display geometry, including negative origins. */
public final class EdgeNavigator {
    public static final int LEFT = 0, UP = 1, RIGHT = 2, DOWN = 3;
    public static String neighbor(MappingState state, int direction) {
        if (state == null || !"display".equals(state.mode) || state.pending) return null;
        MappingState.Display current = null;
        for (MappingState.Display display : state.displays)
            if (display.id.equals(state.selected)) current = display;
        if (current == null) return null;
        double cx = current.bounds.x + current.bounds.width / 2;
        double cy = current.bounds.y + current.bounds.height / 2;
        double best = Double.POSITIVE_INFINITY;
        String result = null;
        for (MappingState.Display candidate : state.displays) {
            if (candidate == current) continue;
            double dx = candidate.bounds.x + candidate.bounds.width / 2 - cx;
            double dy = candidate.bounds.y + candidate.bounds.height / 2 - cy;
            double along = direction == LEFT ? -dx : direction == RIGHT ? dx : direction == UP ? -dy : dy;
            double across = direction == LEFT || direction == RIGHT ? Math.abs(dy) : Math.abs(dx);
            if (along <= 0 || across > along * 2) continue;
            double score = along + across * 2;
            if (score < best) { best = score; result = candidate.id; }
        }
        return result;
    }
}
