package io.github.linloir.reflection.core;

/** A measured raw channel can feed only one screen direction, including partial calibrations. */
public final class TiltCalibration {
    public static float[] apply(float x, float y, int horizontalAxis, int horizontalSign, int verticalAxis, int verticalSign) {
        int h = horizontalAxis >= 0 ? horizontalAxis : verticalAxis == 0 ? -1 : 0;
        int v = verticalAxis >= 0 ? verticalAxis : horizontalAxis == 1 ? -1 : 1;
        if (h == v) v = -1;
        return new float[]{value(x, y, h) * (horizontalAxis >= 0 ? horizontalSign : 1),
            value(x, y, v) * (verticalAxis >= 0 ? verticalSign : 1)};
    }
    private static float value(float x, float y, int axis) { return axis == 0 ? x : axis == 1 ? y : 0; }
}
