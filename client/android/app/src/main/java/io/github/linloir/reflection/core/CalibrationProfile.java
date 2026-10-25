package io.github.linloir.reflection.core;

/** Keeps a saved profile while layout is pending; only a known geometry can invalidate it. */
public final class CalibrationProfile {
    private PenStabilizer.Calibration saved, active;
    private int width, height, rotation;

    public PenStabilizer.Calibration saved() { return saved; }
    public PenStabilizer.Calibration active() { return active; }
    public boolean ready() { return width > 0 && height > 0; }

    public void replace(PenStabilizer.Calibration calibration) {
        saved = calibration;
        active = ready() && saved != null && saved.matchesGeometry(width, height, rotation) ? saved : null;
    }

    /** Returns true only when a saved profile must also be removed from persistent storage. */
    public boolean updateGeometry(int width, int height, int rotation) {
        if (width < 0 || height < 0 || rotation < 0 || rotation > 3)
            throw new IllegalArgumentException("Invalid input surface");
        this.width = width; this.height = height; this.rotation = rotation;
        active = null;
        if (!ready()) return false;
        if (saved != null && !saved.matchesGeometry(width, height, rotation)) {
            saved = null;
            return true;
        }
        active = saved;
        return false;
    }
}
