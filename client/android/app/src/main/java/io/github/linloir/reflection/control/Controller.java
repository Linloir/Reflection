package io.github.linloir.reflection.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import io.github.linloir.reflection.core.PenSample;
import io.github.linloir.reflection.core.MappingState;
import io.github.linloir.reflection.core.RawTouchpadFrame;
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
    private int surfaceWidth, surfaceHeight, surfaceRotation;
    private float calibrationNoise;
    private long received;
    private int clearGeneration;
    private volatile long configurationRevision;
    private volatile long tiltRevision;
    private AutomaticConnection automatic;
    private volatile boolean automaticSuppressed;
    private volatile String automaticStatus = "";
    private volatile long usbLease;
    private volatile String usbToken = "";
    private volatile PairingIdentity identity;
    private volatile TrustedHost pendingPairing;
    public static final class TrustedHost {
        public final String token, address, confirmationCode;
        public final int port;
        TrustedHost(String token, String address, int port) { this(token, address, port, ""); }
        TrustedHost(String token, String address, int port, String confirmationCode) {
            this.token = token; this.address = address; this.port = port; this.confirmationCode = confirmationCode;
        }
    }

    private Controller(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("reflection", Context.MODE_PRIVATE);
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
    public String tiltMode() { return prefs.getString("tiltMode", "auto"); }
    public long tiltRevision() { return tiltRevision; }
    public synchronized void setTiltMode(String mode) {
        if (!"auto".equals(mode) && !"standard".equals(mode) && !"off".equals(mode))
            throw new IllegalArgumentException("Tilt mode must be auto, standard or off");
        if (mode.equals(tiltMode())) return;
        if (!prefs.edit().putString("tiltMode", mode).commit())
            throw new IllegalStateException("Could not save tilt mode");
        tiltRevision++;
    }
    public synchronized void resetTiltDetection() { tiltRevision++; }

    // Legacy commands remain explicit, but the tablet never filters or consumes calibration strokes.
    public boolean stabilizationEnabled() { return false; }
    public boolean calibrating() { return false; }
    public void setStabilization(boolean enabled) {
        if (enabled) throw hostManaged();
    }
    public void startCalibration() { throw hostManaged(); }
    public void finishCalibration() { throw hostManaged(); }
    public void cancelCalibration() {}
    public void resetCalibration() { throw hostManaged(); }
    private static IllegalStateException hostManaged() {
        return new IllegalStateException("Pen stabilization and calibration are managed in the desktop host");
    }
    public synchronized void setCalibrationNoise(float noise) {
        if (!io.github.linloir.reflection.core.NoiseCalibration.valid(noise))
            throw new IllegalArgumentException("Calibration noise must be zero or 0.00001..0.02");
        calibrationNoise = noise;
        if (transport != null) transport.setCalibrationNoise(noise);
    }
    public synchronized boolean setInputGeometry(int width, int height, int rotation) {
        if (width < 0 || height < 0 || rotation < 0 || rotation > 3) return false;
        if (surfaceWidth == width && surfaceHeight == height && surfaceRotation == rotation) return false;
        surfaceWidth = width; surfaceHeight = height; surfaceRotation = rotation;
        if (transport != null) updateRawTouchpadSurface(transport);
        return true;
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
        configureTransport(next);
        transport = next; next.start();
    }
    public synchronized void disconnect() {
        stopAutomaticConnection();
        disconnectTransport();
    }
    private void disconnectTransport() {
        PenTransport current = transport; transport = null;
        if (current != null) current.stop();
    }
    public synchronized void startAutomaticConnection() {
        automaticSuppressed = false;
        if (automatic != null) return;
        identity = new PairingIdentity(context, this);
        automatic = new AutomaticConnection(context, this);
        automatic.start();
    }
    public synchronized void stopAutomaticConnection() {
        automaticSuppressed = true;
        AutomaticConnection current = automatic; automatic = null;
        if (current != null) current.stop();
        automaticStatus = "";
    }
    public boolean connected() { PenTransport current = transport; return current != null && current.connected(); }
    public String connectionSummary() {
        String value = automaticStatus;
        return value.isEmpty() ? (connected() ? "已连接" : "未连接") : value;
    }
    void setAutomaticStatus(String status) { automaticStatus = status; }
    public boolean penContact() {
        PenSample sample = latest;
        return sample != null && (sample.phase == PenSample.DOWN || sample.phase == PenSample.MOVE);
    }
    boolean usbLeaseFresh() { return usbLease != 0 && android.os.SystemClock.elapsedRealtime() - usbLease < 12000; }
    String usbToken() { return usbToken.isEmpty() ? token() : usbToken; }
    PairingIdentity pairingIdentity() { return identity; }
    public String pairingCode() { PairingIdentity current = identity; return current == null ? "------" : current.code(); }
    public synchronized String pairingIdentityID() {
        if (identity == null) identity = new PairingIdentity(context, this);
        return identity.identifier();
    }
    public int trustedHostCount() { return trustedHosts().size(); }
    public String pendingPairingSummary() {
        TrustedHost pending = pendingPairing;
        return pending == null ? "" : "核对电脑上显示的确认码：" + pending.confirmationCode
            + "\n宿主机 " + pending.address + " 请求连接。仅在两端确认码一致时允许。";
    }
    public synchronized void approvePendingPairing() {
        TrustedHost pending = pendingPairing; pendingPairing = null;
        if (pending != null) trustHost(pending.token, pending.address, pending.port, true);
    }
    public void rejectPendingPairing() { pendingPairing = null; }
    public java.util.List<TrustedHost> trustedHosts() {
        java.util.List<TrustedHost> result = new java.util.ArrayList<>();
        try {
            JSONArray stored = new JSONArray(prefs.getString("trustedHosts", "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject host = stored.getJSONObject(i);
                result.add(new TrustedHost(host.getString("token"), host.getString("address"), host.getInt("port")));
            }
        } catch (JSONException ignored) {}
        String legacy = token();
        if (!legacy.isEmpty()) {
            boolean found = false;
            for (TrustedHost host : result) if (host.token.equals(legacy)) found = true;
            if (!found) result.add(new TrustedHost(legacy, host(), port()));
        }
        return result;
    }
    String preferredHostToken() { return prefs.getString("preferredHost", token()); }
    public synchronized void trustHost(String token, String address, int port, boolean approved) {
        trustHost(token, address, port, approved, "");
    }
    synchronized void requestHostTrust(PairingIdentity owner, String token, String address, int port, String nonce) throws java.io.IOException {
        if (identity != owner || automatic == null) return;
        byte[] digest = Wire.hmac(Wire.token(token), ("Reflection/pair-confirm/v1\n" + nonce).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        int number = ((digest[0] & 255) << 16 | (digest[1] & 255) << 8 | (digest[2] & 255)) % 1000000;
        trustHost(token, address, port, false, String.format(java.util.Locale.ROOT, "%06d", number));
    }
    synchronized void requestBluetoothHostTrust(PairingIdentity owner, String token, String address, String nonce) throws java.io.IOException {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IllegalArgumentException("Invalid Bluetooth host address");
        requestHostTrust(owner, token, "bluetooth:" + address, 24819, nonce);
    }
    private void trustHost(String token, String address, int port, boolean approved, String confirmationCode) {
        Wire.token(token);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid host port");
        java.util.List<TrustedHost> hosts = trustedHosts();
        boolean known = false;
        for (TrustedHost host : hosts) if (host.token.equals(token)) known = true;
        if (!known && !approved) {
            if (pendingPairing == null) pendingPairing = new TrustedHost(token, address, port, confirmationCode);
            return;
        }
        saveTrustedHost(token, address, port, true);
    }
    private void saveTrustedHost(String token, String address, int port, boolean select) {
        JSONArray stored = new JSONArray();
        try {
            stored.put(new JSONObject().put("token", token).put("address", address).put("port", port));
            for (TrustedHost host : trustedHosts()) if (!host.token.equals(token) && stored.length() < 16)
                stored.put(new JSONObject().put("token", host.token).put("address", host.address).put("port", host.port));
        } catch (JSONException error) { throw new IllegalStateException(error); }
        SharedPreferences.Editor edit = prefs.edit().putString("trustedHosts", stored.toString());
        if (select) edit.putString("preferredHost", token).putString("token", token).putString("host", address).putInt("port", port);
        if (!edit.commit()) throw new IllegalStateException("Could not save trusted host");
        configurationRevision++;
    }
    synchronized void connectAutomatically(AutomaticConnection owner, String mode, String address, int port, String token) {
        if (automatic != owner) return;
        disconnectTransport();
        final PenTransport next;
        if ("bluetooth".equals(mode)) {
            requireBluetoothPermission();
            BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            next = new PenTransport(() -> new BluetoothLink(adapter, address), token);
        } else next = new PenTransport(() -> new TcpLink(address, port), token);
        configureTransport(next);
        transport = next; next.start();
    }
    private void configureTransport(PenTransport next) {
        next.setCalibrationNoise(calibrationNoise);
        updateRawTouchpadSurface(next);
    }
    private void updateRawTouchpadSurface(PenTransport next) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        next.setRawTouchpadDimensions(
            RawTouchpadFrame.physicalDimension(surfaceWidth > 0 ? surfaceWidth : metrics.widthPixels, metrics.xdpi, metrics.density, 10000),
            RawTouchpadFrame.physicalDimension(surfaceHeight > 0 ? surfaceHeight : metrics.heightPixels, metrics.ydpi, metrics.density, 6000));
    }
    /** Called only by the DUMP-protected ADB receiver after the system USB trust prompt. */
    public synchronized void provisionUsb(String token, boolean select) {
        Wire.token(token);
        token = token.toLowerCase(java.util.Locale.ROOT);
        TrustedHost known = null;
        for (TrustedHost host : trustedHosts()) if (host.token.equals(token)) known = host;
        if (known == null)
            saveTrustedHost(token, "127.0.0.1", 24819, select || preferredHostToken().isEmpty());
        else if (select)
            saveTrustedHost(token, known.address, known.port, true);
        usbToken = token;
        usbLease = android.os.SystemClock.elapsedRealtime();
        // A lease refresh is not a user selection, even after either process restarts.
        if (select || !automaticSuppressed) startAutomaticConnection();
    }
    public synchronized void forgetHost() {
        disconnect();
        prefs.edit().remove("token").remove("host").remove("bluetooth").remove("trustedHosts").remove("preferredHost").apply();
        usbLease = 0; usbToken = ""; pendingPairing = null; configurationRevision++;
        startAutomaticConnection();
    }
    /** Raw normalized capture is shared unchanged by local preview and the host transport. */
    public synchronized PenSample accept(PenSample sample) {
        received++; latest = sample;
        PenTransport current = transport;
        if (current != null) current.submit(sample);
        return sample;
    }
    public PenSample latest() { return latest; }
    public void submitTouchpad(io.github.linloir.reflection.core.TouchpadEvent event) {
        PenTransport current = transport;
        if (current != null) current.submitTouchpad(event);
    }
    public boolean touchpadSupported() {
        PenTransport current = transport;
        return current != null && current.touchpadSupported();
    }
    public void submitRawTouchpad(RawTouchpadFrame frame) {
        PenTransport current = transport;
        if (current != null) current.submitRawTouchpad(frame);
    }
    public boolean rawTouchpadSupported() {
        PenTransport current = transport;
        return current != null && current.rawTouchpadSupported();
    }
    public MappingState mapping() {
        PenTransport current = transport;
        return current == null ? null : current.mapping();
    }
    public synchronized void requestMapping(String action, String display) {
        PenTransport current = transport;
        if (current == null) throw new IllegalStateException("Connect to a host before switching screens");
        final int code;
        switch (action) {
            case "query": code = MappingState.QUERY; break;
            case "next": code = MappingState.NEXT; break;
            case "previous": code = MappingState.PREVIOUS; break;
            case "all": code = MappingState.ALL; break;
            case "select": code = MappingState.SELECT; break;
            default: throw new IllegalArgumentException("Mapping action must be query, next, previous, all or select");
        }
        current.requestMapping(code, display);
    }
    public JSONObject mappingStatus() throws JSONException {
        MappingState value = mapping();
        return value == null ? new JSONObject().put("supported", false)
            : new JSONObject(value.json()).put("supported", true);
    }
    public synchronized String statusLine() {
        PenTransport t = transport;
        return (t == null ? "Disconnected" : t.status()) + " | samples " + received + " | sent " + (t == null ? 0 : t.sent())
            + " | RTT " + (t == null ? 0 : t.rttMicros() / 1000.0) + " ms";
    }
    public synchronized JSONObject status() throws JSONException {
        PenTransport t = transport;
        JSONObject o = new JSONObject().put("mode", mode()).put("bluetooth", bluetoothAddress()).put("host", host()).put("port", port()).put("configured", !token().isEmpty())
            .put("automaticConnection", automatic != null).put("connectionSummary", connectionSummary())
            .put("usbAvailable", usbLeaseFresh())
            .put("preview", preview()).put("connected", t != null && t.connected())
            .put("status", t == null ? "Disconnected" : t.status()).put("samples", received)
            .put("sent", t == null ? 0 : t.sent()).put("queueOverflows", t == null ? 0 : t.overflows())
            .put("rttMicros", t == null ? 0 : t.rttMicros())
            .put("invalidTiltSamples", PenInput.invalidTiltSamples()).put("tiltReliable", PenInput.tiltReliable())
            .put("lastRawTilt", safeFloat(PenInput.lastRawTilt())).put("lastRawOrientation", safeFloat(PenInput.lastRawOrientation()))
            .put("tiltStatus", PenInput.tiltStatus()).put("suppressedTiltSamples", PenInput.suppressedTiltSamples())
            .put("tiltMode", tiltMode()).put("recoveredTiltSamples", PenInput.recoveredTiltSamples())
            .put("tiltAxes", PenInput.tiltAxes()).put("tiltInputRotation", PenInput.tiltInputRotation())
            .put("recoveredTiltDegrees", safeFloat(PenInput.recoveredTiltDegrees()))
            .put("eraserSamples", PenInput.eraserSamples()).put("barrelButtonSamples", PenInput.barrelButtonSamples())
            .put("lastRawToolType", PenInput.lastRawToolType()).put("lastRawButtons", PenInput.lastRawButtons())
            .put("inputDevice", new JSONObject().put("id", PenInput.lastDeviceId()).put("name", PenInput.lastDeviceName())
                .put("vendorId", PenInput.lastDeviceVendor()).put("productId", PenInput.lastDeviceProduct()).put("source", PenInput.lastSource()))
            .put("stabilization", stabilizationStatus()).put("mapping", mappingStatus())
            .put("rawCoordinates", true).put("hostStabilization", true)
            .put("tabletCalibration", new JSONObject()
                .put("noise", calibrationNoise).put("negotiated", t != null && t.calibrationSupported())
                .put("acknowledged", t == null ? 0 : t.calibrationAcknowledged())
                .put("appliedNoise", t == null ? 0 : t.appliedCalibrationNoise()))
            .put("inputDiagnostics", new JSONObject().put("ignoredForeignEvents", PenInput.ignoredForeignEvents())
                .put("ignoredHoverDuringContact", PenInput.ignoredHoverDuringContact())
                .put("invalidCoordinateSamples", PenInput.invalidCoordinateSamples()).put("staleSamples", PenInput.staleSamples()));
        PenSample p = latest;
        if (p != null) o.put("lastSample", new JSONObject().put("x", p.x).put("y", p.y).put("pressure", p.pressure)
            .put("tiltX", p.tiltX).put("tiltY", p.tiltY).put("tool", p.tool).put("phase", p.phase).put("capabilities", p.capabilities));
        return o;
    }
    private JSONObject stabilizationStatus() throws JSONException {
        JSONObject state = new JSONObject().put("enabled", false).put("calibrated", false)
            .put("managedBy", "host").put("rawCoordinates", true)
            .put("geometry", new JSONObject().put("width", surfaceWidth).put("height", surfaceHeight)
                .put("rotation", surfaceRotation).put("ready", surfaceWidth > 1 && surfaceHeight > 1))
            .put("calibration", new JSONObject().put("active", false).put("lastResult", "host-managed"));
        if (latest != null) state.put("lastRawPosition", new JSONObject().put("x", safeFloat(latest.x)).put("y", safeFloat(latest.y)));
        return state;
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
