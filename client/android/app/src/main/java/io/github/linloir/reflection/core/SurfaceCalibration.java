package io.github.linloir.reflection.core;

/** Affine screen correction in physical panel coordinates, independent of Android view layout. */
public final class SurfaceCalibration {
    public final double[] x, y;
    public final double noise;
    public SurfaceCalibration(double[] x, double[] y, double noise) {
        if (x.length != 3 || y.length != 3 || !finite(noise) || noise < 0 || noise > .02)
            throw new IllegalArgumentException("Invalid calibration");
        for (double value : x) if (!finite(value)) throw new IllegalArgumentException("Invalid calibration");
        for (double value : y) if (!finite(value)) throw new IllegalArgumentException("Invalid calibration");
        this.x = x.clone(); this.y = y.clone(); this.noise = noise;
    }
    public double[] correct(double a, double b) {
        return new double[]{x[0] * a + x[1] * b + x[2], y[0] * a + y[1] * b + y[2]};
    }
    public static double[] toPanel(double x, double y, int rotation) {
        switch (rotation) {
            case 1: return new double[]{1 - y, x};
            case 2: return new double[]{1 - x, 1 - y};
            case 3: return new double[]{y, 1 - x};
            default: return new double[]{x, y};
        }
    }
    public static double[] fromPanel(double x, double y, int rotation) {
        return toPanel(x, y, (4 - rotation) % 4);
    }
    public static SurfaceCalibration fit(double[][] measured, double[][] targets, double noise) {
        if (measured.length != targets.length || measured.length < 4) throw new IllegalArgumentException("Need four or more points");
        double[][] normal = new double[3][3];
        double[] bx = new double[3], by = new double[3];
        for (int n = 0; n < measured.length; n++) {
            double[] row = {measured[n][0], measured[n][1], 1};
            for (int i = 0; i < 3; i++) {
                bx[i] += row[i] * targets[n][0]; by[i] += row[i] * targets[n][1];
                for (int j = 0; j < 3; j++) normal[i][j] += row[i] * row[j];
            }
        }
        SurfaceCalibration fitted = new SurfaceCalibration(solve(normal, bx), solve(normal, by), noise);
        // Reject bad taps and nearly singular fits rather than magnifying errors across the surface.
        if (Math.abs(fitted.x[0] - 1) > .2 || Math.abs(fitted.y[1] - 1) > .2
            || Math.abs(fitted.x[1]) > .15 || Math.abs(fitted.y[0]) > .15
            || Math.abs(fitted.x[2]) > .1 || Math.abs(fitted.y[2]) > .1)
            throw new IllegalArgumentException("Calibration moved too far from the screen coordinates");
        for (int i = 0; i < measured.length; i++) {
            double[] p = fitted.correct(measured[i][0], measured[i][1]);
            if (Math.hypot(p[0] - targets[i][0], p[1] - targets[i][1]) > .015)
                throw new IllegalArgumentException("Calibration points do not agree");
        }
        return fitted;
    }
    private static double[] solve(double[][] a, double[] b) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) { System.arraycopy(a[i], 0, m[i], 0, 3); m[i][3] = b[i]; }
        for (int i = 0; i < 3; i++) {
            int pivot = i;
            for (int j = i + 1; j < 3; j++) if (Math.abs(m[j][i]) > Math.abs(m[pivot][i])) pivot = j;
            double[] swap = m[i]; m[i] = m[pivot]; m[pivot] = swap;
            if (Math.abs(m[i][i]) < 1e-8) throw new IllegalArgumentException("Calibration points overlap");
            double divisor = m[i][i];
            for (int k = i; k < 4; k++) m[i][k] /= divisor;
            for (int j = 0; j < 3; j++) if (j != i) {
                double ratio = m[j][i];
                for (int k = i; k < 4; k++) m[j][k] -= ratio * m[i][k];
            }
        }
        return new double[]{m[0][3], m[1][3], m[2][3]};
    }
    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
