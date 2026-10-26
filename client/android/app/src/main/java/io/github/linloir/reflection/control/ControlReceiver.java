package io.github.linloir.reflection.control;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Protected by the platform DUMP permission: shell/system, never ordinary apps. */
public final class ControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            Controller c = Controller.get(context);
            String command = intent.getStringExtra("command");
            if (command == null) command = "status";
            switch (command) {
                case "configure":
                    c.configure(intent.hasExtra("host") ? intent.getStringExtra("host") : c.host(),
                        intent.getIntExtra("port", c.port()), intent.hasExtra("token") ? intent.getStringExtra("token") : c.token(),
                        intent.hasExtra("preview") ? intent.getBooleanExtra("preview", false) : null,
                        intent.hasExtra("mode") ? intent.getStringExtra("mode") : c.mode(),
                        intent.hasExtra("bluetooth") ? intent.getStringExtra("bluetooth") : c.bluetoothAddress()); break;
                case "connect": c.connect(); break;
                case "disconnect": c.disconnect(); break;
                case "clear": c.clearPreview(); break;
                case "preview": c.setPreview(intent.getBooleanExtra("enabled", false)); break;
                case "stabilization":
                    if (intent.hasExtra("enabled")) c.setStabilization(intent.getBooleanExtra("enabled", true));
                    break;
                case "tilt":
                    if (intent.hasExtra("mode")) c.setTiltMode(intent.getStringExtra("mode"));
                    break;
                case "tilt-reset": c.resetTiltDetection(); break;
                case "calibration-start": c.startCalibration(); break;
                case "calibration-finish": c.finishCalibration(); break;
                case "calibration-cancel": c.cancelCalibration(); break;
                case "calibration-reset": c.resetCalibration(); break;
                case "status": break;
                case "selftest":
                    if (c.calibrating()) throw new IllegalStateException("Finish or cancel calibration before running a synthetic diagnostic");
                    int count = io.github.linloir.reflection.input.SyntheticDiagnostic.run(c::accept);
                    setResultCode(0); setResultData(new JSONObject().put("synthetic", true).put("samples", count).put("hardwareVerified", false).toString()); return;
                case "paired": setResultCode(0); setResultData(c.pairedDevices().toString()); return;
                case "devices": setResultCode(0); setResultData(Controller.devices().toString()); return;
                default: throw new IllegalArgumentException("Unknown command");
            }
            setResultCode(0); setResultData(c.status().toString());
        } catch (Exception e) {
            setResultCode(1);
            setResultData("{\"error\":" + JSONObject.quote(e.getMessage() == null ? "Command failed" : e.getMessage()) + "}");
        }
    }
}
