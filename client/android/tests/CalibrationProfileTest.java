import io.github.linloir.reflection.core.CalibrationProfile;
import io.github.linloir.reflection.core.PenStabilizer;

public final class CalibrationProfileTest {
    public static void main(String[] args) {
        PenStabilizer.Calibration saved = new PenStabilizer.Calibration(
            0.0006f, 0.0005f, 120, 1000000000L, 1600, 1200, 1);
        CalibrationProfile profile = new CalibrationProfile();
        profile.replace(saved);
        require(!profile.updateGeometry(0, 0, 1), "pre-layout must not request persistent deletion");
        require(!profile.ready() && profile.active() == null && profile.saved() == saved,
            "a restored profile must remain pending before the first layout");
        require(!profile.updateGeometry(1600, 1200, 1), "matching geometry keeps the profile");
        require(profile.ready() && profile.active() == saved, "matching layout activates the profile");

        require(!profile.updateGeometry(1600, 0, 3), "temporary zero height must preserve stored calibration");
        require(!profile.ready() && profile.active() == null && profile.saved() == saved,
            "an unready surface suspends only the live calibration");
        require(!profile.updateGeometry(1600, 1200, 1) && profile.active() == saved,
            "returning to the calibrated geometry restores the live profile");
        require(profile.updateGeometry(1600, 1200, 3), "a known rotation mismatch invalidates the stored profile");
        require(profile.saved() == null && profile.active() == null, "an incompatible profile is not applied");
        require(!profile.updateGeometry(1600, 1200, 1) && profile.active() == null,
            "an invalidated profile must not silently return after a later rotation");

        profile.replace(saved);
        require(profile.updateGeometry(1601, 1200, 1), "a known size mismatch invalidates the stored profile");
        profile.replace(saved);
        profile.replace(null);
        require(profile.saved() == null && profile.active() == null, "explicit reset clears pending and live profiles");
        System.out.println("Calibration profile lifecycle checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
