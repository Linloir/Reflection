package io.github.linloir.reflection.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import io.github.linloir.reflection.core.SurfaceCalibration;
import io.github.linloir.reflection.input.PenInput;
import io.github.linloir.reflection.control.Controller;
import org.json.JSONArray;
import org.json.JSONObject;

final class CalibrationStore {
    private final SharedPreferences prefs;
    private final Controller controller;
    CalibrationStore(Context context) {
        prefs = context.getSharedPreferences("reflection_calibration", Context.MODE_PRIVATE);
        controller = Controller.get(context);
    }
    SurfaceCalibration surface() {
        try {
            if (!Build.FINGERPRINT.equals(prefs.getString("device", ""))) return null;
            JSONObject object = new JSONObject(prefs.getString("surface", ""));
            double[] x = new double[3], y = new double[3];
            for (int i = 0; i < 3; i++) { x[i] = object.getJSONArray("x").getDouble(i); y[i] = object.getJSONArray("y").getDouble(i); }
            return new SurfaceCalibration(x, y, object.getDouble("noise"));
        } catch (Exception invalid) { return null; }
    }
    void saveSurface(SurfaceCalibration value) {
        try {
            JSONObject object = new JSONObject();
            JSONArray x = new JSONArray(), y = new JSONArray();
            for (double v : value.x) x.put(v);
            for (double v : value.y) y.put(v);
            object.put("x", x).put("y", y).put("noise", value.noise);
            prefs.edit().putString("device", Build.FINGERPRINT).putString("surface", object.toString()).apply();
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    void clear(int step) {
        if (step == 1) prefs.edit().remove("surface").apply();
        else prefs.edit().remove("axis" + step).remove("sign" + step).apply();
    }
    boolean complete(int step) { return step == 1 ? surface() != null : axis(step) >= 0; }
    int axis(int step) {
        if (!Build.FINGERPRINT.equals(prefs.getString("tiltFingerprint" + step, ""))) return -1;
        return prefs.getInt("axis" + step, -1);
    }
    int axisForInput(int step, int rotation) {
        return axisForInput(step, rotation, controller.tiltMode());
    }
    int axisForInput(int step, int rotation, String decoderMode) {
        if (prefs.getInt("rotation" + step, -1) % 2 != rotation % 2
            || !decoderMode.equals(prefs.getString("decoder" + step, ""))
            || !inputIdentity().equals(prefs.getString("input" + step, ""))) return -1;
        return axis(step);
    }
    int sign(int step) { return prefs.getInt("sign" + step, 1); }
    void saveAxis(int step, int axis, int sign, int rotation, String decoder) {
        prefs.edit().putInt("axis" + step, axis).putInt("sign" + step, sign)
            .putString("tiltFingerprint" + step, Build.FINGERPRINT)
            .putInt("rotation" + step, rotation).putString("input" + step, inputIdentity())
            .putString("decoder" + step, decoder).apply();
    }
    private String inputIdentity() { return PenInput.lastDeviceVendor() + ":" + PenInput.lastDeviceProduct() + ":" + PenInput.lastDeviceName(); }
}
