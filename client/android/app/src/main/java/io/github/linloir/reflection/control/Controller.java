package io.github.linloir.reflection.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import io.github.linloir.reflection.core.CalibrationProfile;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.PenStabilizer;
import io.github.linloir.reflection.core.Wire;
import io.github.linloir.reflection.input.PenInput;
import io.github.linloir.reflection.transport.PenTransport;
import io.github.linloir.reflection.transport.TcpLink;
import io.github.linloir.reflection.transport.BluetoothLink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One application control surface shared by UI and adb shell commands. */
public final class Controller {
    private static Controller instance;
    public static synchronized Controller get(Context context) {
        if (instance == null) instance = new Controller(context.getApplicationContext());
        return instance;
    }
    private final SharedPreferences prefs;
    private final Context context;
    private volatile PenTransport transport;
    private volatile PenSample latest;
    private PenSample latestRaw, lastSent;
    private final PenStabilizer stabilizer = new PenStabilizer();
    private final CalibrationProfile calibrationProfile = new CalibrationProfile();
    private PenStabilizer.Calibrator calibrator;
    private int surfaceWidth, surfaceHeight, surfaceRotation;
    private boolean waitForDown;
    private String calibrationResult = "not-calibrated";
    private long received;
    private int clearGeneration;
    private volatile long configurationRevision;

    private Controller(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("reflection", Context.MODE_PRIVATE);
        stabilizer.setEnabled(prefs.getBoolean("stabilization", true));
        String stored = prefs.getString("stabilizationCalibration", null);
        if (stored != null) {
            try {
                JSONObject value = new JSONObject(stored);
                calibrationProfile.replace(new PenStabilizer.Calibration((float) value.getDouble("noiseX"),
                    (float) value.getDouble("noiseY"), value.getInt("sampleCount"), value.getLong("durationNanos"),
                    value.getInt("width"), value.getInt("height"), value.getInt("rotation")));
                calibrationResult = "awaiting-input-surface";
            } catch (JSONException | IllegalArgumentException e) {
                calibrationResult = "invalid-saved-calibration";
            }
        }
    }
    public long configurationRevision() { return configurationRevision; }
    public String mode() { return prefs.getString("mode", "tcp"); }
    public String bluetoothAddress() { return prefs.getString("bluetooth", ""); }
    public String host() { return prefs.getString("host", "127.0.0.1"); }
    public int port() { return prefs.getInt("port", 24819); }
    public String token() { return prefs.getString("token", ""); }
    public boolean preview() { return prefs.getBoolean("preview", false); }
    public int clearGeneration() { return clearGeneration; }
    public void setPreview(boolean enabled) { prefs.edit().putBoolean("preview", enabled).apply(); }
    public void clearPreview() { clearGeneration++; }

    public synchronized boolean stabilizationEnabled() { return stabilizer.enabled(); }
    public synchronized boolean calibrating() { return calibrator != null; }
    public synchronized void setStabilization(boolean enabled) {
        if (!prefs.edit().putBoolean("stabilization", enabled).commit())
            throw new IllegalStateException("Could not save stabilization setting");
        stabilizer.setEnabled(enabled);
    }

    public synchronized boolean setInputGeometry(int width, int height, int rotation) {
        if (width < 0 || height < 0 || rotation < 0 || rotation > 3) return false;
        if (surfaceWidth == width && surfaceHeight == height && surfaceRotation == rotation) return false;
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            releaseTransportInput();
            waitForDown = true;
        }
        if (calibrator != null) {
            calibrator = null;
            calibrationResult = "input-surface-changed";
            waitForDown = true;
        }
        surfaceWidth = width; surfaceHeight = height; surfaceRotation = rotation;
        boolean invalidated = calibrationProfile.updateGeometry(width, height, rotation);
        stabilizer.setCalibration(calibrationProfile.active());
        if (invalidated) {
            prefs.edit().remove("stabilizationCalibration").apply();
            calibrationResult = "input-surface-changed";
        } else if (!calibrationProfile.ready()) {
            calibrationResult = "awaiting-input-surface";
        } else if (calibrationProfile.active() != null) {
            calibrationResult = "ok";
        }
        return true;
    }

    public synchronized void startCalibration() {
        if (calibrator != null) throw new IllegalStateException("Calibration is already active");
        if (!calibrationProfile.ready()) throw new IllegalStateException("Input surface is not ready");
        PenStabilizer.Calibrator next = new PenStabilizer.Calibrator(surfaceWidth, surfaceHeight, surfaceRotation);
        releaseTransportInput();
        stabilizer.reset();
        waitForDown = true;
        calibrator = next;
        calibrationResult = "collecting";
    }

    public synchronized void finishCalibration() {
        if (calibrator == null) throw new IllegalStateException("No calibration is active");
        PenStabilizer.CalibrationResult result = calibrator.finish();
        if (!result.accepted) {
            calibrationResult = result.reason;
            throw new IllegalStateException("Calibration rejected: " + result.reason + " ("
                + result.sampleCount + " samples, " + result.durationNanos / 1000000L + " ms)");
        }
        final String serialized;
        try { serialized = calibrationJSON(result.calibration).toString(); }
        catch (JSONException e) { throw new IllegalStateException("Could not encode calibration", e); }
        if (!prefs.edit().putString("stabilizationCalibration", serialized).commit())
            throw new IllegalStateException("Could not save calibration; the previous profile is unchanged");
        calibrationProfile.replace(result.calibration);
        stabilizer.setCalibration(calibrationProfile.active());
        calibrator = null;
        calibrationResult = "ok";
        waitForDown = true;
    }

    public synchronized void cancelCalibration() {
        if (calibrator == null) return;
        calibrator = null;
        stabilizer.reset();
        calibrationResult = "cancelled";
        waitForDown = true;
    }

    public synchronized void resetCalibration() {
        if (!prefs.edit().remove("stabilizationCalibration").commit())
            throw new IllegalStateException("Could not reset calibration");
        if (calibrator != null) waitForDown = true;
        calibrator = null;
        calibrationProfile.replace(null);
        stabilizer.setCalibration(null);
        calibrationResult = "reset-to-defaults";
    }

    private void releaseTransportInput() {
        PenTransport current = transport;
        if (current != null && lastSent != null && lastSent.phase != PenSample.CANCEL && lastSent.phase != PenSample.LEAVE)
            current.submit(lastSent.cancelled(lastSent.sequence));
        lastSent = null;
    }

    public synchronized void configure(String host, int port, String token, Boolean preview) {
        configure(host, port, token, preview, mode(), bluetoothAddress());
    }
    public synchronized void configure(String host, int port, String token, Boolean preview, String mode, String bluetooth) {
        if (!"tcp".equals(mode) && !"bluetooth".equals(mode)) throw new IllegalArgumentException("Mode must be tcp or bluetooth");
        if ("bluetooth".equals(mode) && (bluetooth == null || !BluetoothAdapter.checkBluetoothAddress(bluetooth))) throw new IllegalArgumentException("A paired Bluetooth address is required");
        if (host == null || host.trim().isEmpty() || host.length() > 253) throw new IllegalArgumentException("Host is required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        Wire.token(token);
        disconnect();
        SharedPreferences.Editor edit = prefs.edit().putString("mode", mode).putString("bluetooth", bluetooth == null ? "" : bluetooth).putString("host", host.trim()).putInt("port", port).putString("token", token.toLowerCase(java.util.Locale.ROOT));
        if (preview != null) edit.putBoolean("preview", preview);
        if (!edit.commit()) throw new IllegalStateException("Could not save configuration");
        configurationRevision++;
    }
    public synchronized void connect() {
        disconnect();
        final PenTransport next;
        if ("bluetooth".equals(mode())) {
            requireBluetoothPermission();
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            final String address = bluetoothAddress();
            next = new PenTransport(() -> new BluetoothLink(adapter, address), token());
        } else {
            final String host = host(); final int port = port();
            next = new PenTransport(() -> new TcpLink(host, port), token());
        }
        transport = next; next.start();
    }
    public synchronized void disconnect() {
        PenTransport current = transport; transport = null;
        if (current != null) current.stop();
        lastSent = null;
        stabilizer.reset();
    }
    /** Returns exactly the sample used for preview and transmission, or null while calibration owns input. */
    public synchronized PenSample accept(PenSample sample) {
        latestRaw = sample; received++;
        if (calibrator != null) {
            calibrator.add(sample);
            return null;
        }
        if (waitForDown) {
            if (sample.phase == PenSample.MOVE || sample.phase == PenSample.UP) return null;
            if (sample.phase == PenSample.DOWN) {
                waitForDown = false;
                stabilizer.reset();
            }
        }
        PenSample filtered = stabilizer.apply(sample);
        latest = filtered;
        PenTransport current = transport;
        if (current != null) { current.submit(filtered); lastSent = filtered; }
        return filtered;
    }
    public PenSample latest() { return latest; }
    public synchronized String statusLine() {
        PenTransport t = transport;
        return (t == null ? "Disconnected" : t.status()) + " | samples " + received + " | sent " + (t == null ? 0 : t.sent())
            + " | RTT " + (t == null ? 0 : t.rttMicros() / 1000.0) + " ms"
            + (calibrator == null ? "" : " | Calibration: " + calibrator.sampleCount() + " samples, "
                + calibrator.durationNanos() / 1000000L + " ms");
    }
    public synchronized JSONObject status() throws JSONException {
        PenTransport t = transport;
        JSONObject o = new JSONObject().put("mode", mode()).put("bluetooth", bluetoothAddress()).put("host", host()).put("port", port()).put("configured", !token().isEmpty())
            .put("preview", preview()).put("connected", t != null && t.connected())
            .put("status", t == null ? "Disconnected" : t.status()).put("samples", received)
            .put("sent", t == null ? 0 : t.sent()).put("queueOverflows", t == null ? 0 : t.overflows())
            .put("rttMicros", t == null ? 0 : t.rttMicros())
            .put("invalidTiltSamples", PenInput.invalidTiltSamples()).put("tiltReliable", PenInput.tiltReliable())
            .put("lastRawTilt", safeFloat(PenInput.lastRawTilt())).put("lastRawOrientation", safeFloat(PenInput.lastRawOrientation()))
            .put("stabilization", stabilizationStatus());
        PenSample p = latest;
        if (p != null) o.put("lastSample", new JSONObject().put("x", p.x).put("y", p.y).put("pressure", p.pressure)
            .put("tiltX", p.tiltX).put("tiltY", p.tiltY).put("tool", p.tool).put("phase", p.phase).put("capabilities", p.capabilities));
        return o;
    }
    private JSONObject stabilizationStatus() throws JSONException {
        PenStabilizer.Calibration active = stabilizer.calibration();
        JSONObject state = new JSONObject().put("enabled", stabilizer.enabled())
            .put("calibrated", active != null)
            .put("noiseX", active == null ? PenStabilizer.DEFAULT_NOISE : active.noiseX)
            .put("noiseY", active == null ? PenStabilizer.DEFAULT_NOISE : active.noiseY)
            .put("geometry", new JSONObject().put("width", surfaceWidth).put("height", surfaceHeight)
                .put("rotation", surfaceRotation).put("ready", calibrationProfile.ready()))
            .put("calibration", new JSONObject().put("active", calibrator != null)
                .put("samples", calibrator == null ? 0 : calibrator.sampleCount())
                .put("durationMillis", calibrator == null ? 0 : calibrator.durationNanos() / 1000000L)
                .put("minimumSamples", PenStabilizer.MIN_CALIBRATION_SAMPLES)
                .put("minimumDurationMillis", PenStabilizer.MIN_CALIBRATION_NANOS / 1000000L)
                .put("lastResult", calibrationResult).put("waitingForDown", waitForDown));
        if (calibrationProfile.saved() != null) state.put("profile", calibrationJSON(calibrationProfile.saved()));
        if (latestRaw != null) state.put("lastRawPosition", new JSONObject().put("x", safeFloat(latestRaw.x)).put("y", safeFloat(latestRaw.y)));
        return state;
    }
    private static JSONObject calibrationJSON(PenStabilizer.Calibration value) throws JSONException {
        return new JSONObject().put("noiseX", value.noiseX).put("noiseY", value.noiseY)
            .put("sampleCount", value.sampleCount).put("durationNanos", value.durationNanos)
            .put("width", value.width).put("height", value.height).put("rotation", value.rotation);
    }
    private static Object safeFloat(float value) {
        return Float.isNaN(value) || Float.isInfinite(value) ? JSONObject.NULL : value;
    }
    private void requireBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Grant Nearby devices permission in the app or use adb shell pm grant io.github.linloir.reflection android.permission.BLUETOOTH_CONNECT");
    }
    public JSONArray pairedDevices() throws JSONException {
        requireBluetoothPermission();
        JSONArray devices = new JSONArray();
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return devices;
        for (BluetoothDevice d : adapter.getBondedDevices()) devices.put(new JSONObject().put("name", d.getName()).put("address", d.getAddress()));
        return devices;
    }
    public static JSONArray devices() throws JSONException {
        JSONArray devices = new JSONArray();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id); if (d == null) continue;
            JSONArray ranges = new JSONArray();
            for (InputDevice.MotionRange r : d.getMotionRanges()) ranges.put(new JSONObject()
                .put("axis", r.getAxis()).put("source", r.getSource()).put("min", r.getMin()).put("max", r.getMax())
                .put("resolution", r.getResolution()).put("flat", r.getFlat()).put("fuzz", r.getFuzz()));
            devices.put(new JSONObject().put("id", id).put("name", d.getName()).put("sources", d.getSources()).put("ranges", ranges));
        }
        return devices;
    }
}
