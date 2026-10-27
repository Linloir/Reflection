import io.github.linloir.reflection.core.EdgeNavigator;
import io.github.linloir.reflection.core.MappingState;
import io.github.linloir.reflection.core.SurfaceCalibration;
import io.github.linloir.reflection.core.TiltCalibration;
import java.nio.charset.StandardCharsets;

public final class WorkspaceCoreTest {
    public static void main(String[] args) throws Exception {
        double[][] targets = {{.1,.1},{.9,.1},{.9,.9},{.1,.9},{.5,.5}};
        double[][] measured = new double[5][2];
        for (int i = 0; i < 5; i++) measured[i] = new double[]{targets[i][0] + .008, targets[i][1] - .006};
        SurfaceCalibration fit = SurfaceCalibration.fit(measured, targets, .0004);
        double[] result = fit.correct(.508, .494);
        near(result[0], .5); near(result[1], .5);
        for (int rotation = 0; rotation < 4; rotation++) {
            double[] panel = SurfaceCalibration.toPanel(.27, .73, rotation);
            double[] restored = SurfaceCalibration.fromPanel(panel[0], panel[1], rotation);
            near(restored[0], .27); near(restored[1], .73);
        }
        boolean invalid = false;
        try { SurfaceCalibration.fit(new double[][]{{.5,.5},{.5,.5},{.5,.5},{.5,.5}}, targets, .001); }
        catch (IllegalArgumentException expected) { invalid = true; }
        require(invalid, "Reject singular calibration");
        measured[4][0] += .1;
        invalid = false;
        try { SurfaceCalibration.fit(measured, targets, .001); } catch (IllegalArgumentException expected) { invalid = true; }
        require(invalid, "Reject inconsistent taps");
        float[] tilt = TiltCalibration.apply(32, 0, -1, 1, 0, -1);
        near(tilt[0], 0); near(tilt[1], -32);
        tilt = TiltCalibration.apply(0, -18, 1, -1, -1, 1);
        near(tilt[0], 18); near(tilt[1], 0);
        tilt = TiltCalibration.apply(12, 24, 1, -1, 0, 1);
        near(tilt[0], -24); near(tilt[1], 12);

        String json = "{\"revision\":1,\"mode\":\"display\",\"selected\":\"center\",\"pending\":false,"
            + "\"target\":{\"x\":0,\"y\":0,\"width\":1920,\"height\":1080},\"displays\":["
            + display("center",0,0) + "," + display("left",-1920,0) + "," + display("upper",0,-1080)
            + "," + display("right",1920,0) + "]}";
        MappingState state = MappingState.decode(json.getBytes(StandardCharsets.UTF_8));
        require("left".equals(EdgeNavigator.neighbor(state, EdgeNavigator.LEFT)), "Negative-origin left neighbor");
        require("upper".equals(EdgeNavigator.neighbor(state, EdgeNavigator.UP)), "Upper neighbor");
        require("right".equals(EdgeNavigator.neighbor(state, EdgeNavigator.RIGHT)), "Right neighbor");
        require(EdgeNavigator.neighbor(state, EdgeNavigator.DOWN) == null, "No wrap at outer boundary");
        MappingState all = MappingState.decode(json.replace("\"mode\":\"display\"", "\"mode\":\"all\"").getBytes(StandardCharsets.UTF_8));
        require(EdgeNavigator.neighbor(all, EdgeNavigator.LEFT) == null, "All-screen mode must not switch");
        MappingState pending = MappingState.decode(json.replace("\"pending\":false", "\"pending\":true").getBytes(StandardCharsets.UTF_8));
        require(EdgeNavigator.neighbor(pending, EdgeNavigator.RIGHT) == null, "Do not stack pending switches");
        System.out.println("Workspace calibration and directional mapping checks passed");
    }
    private static String display(String id, int x, int y) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"x\":" + x + ",\"y\":" + y
            + ",\"width\":1920,\"height\":1080,\"primary\":" + id.equals("center") + "}";
    }
    private static void near(double actual, double expected) { require(Math.abs(actual - expected) < 1e-8, "Coordinate correction"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
