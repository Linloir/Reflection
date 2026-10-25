package io.github.linloir.reflection.ui;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.github.linloir.reflection.control.Controller;

/** Deliberately small diagnostic UI over the same controller as the CLI. */
public final class MainActivity extends Activity {
    private Controller controller;
    private InkView ink;
    private EditText host, port, token, bluetoothAddress;
    private TextView status;
    private CheckBox preview, bluetooth, stabilization;
    private long displayedConfigurationRevision;
    private final Handler handler = new Handler();
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (displayedConfigurationRevision != controller.configurationRevision()) syncConfiguration();
            String value = controller.statusLine();
            if (!value.contentEquals(status.getText())) status.setText(value);
            if (preview.isChecked() != controller.preview()) { preview.setChecked(controller.preview()); ink.invalidate(); }
            if (stabilization.isChecked() != controller.stabilizationEnabled())
                stabilization.setChecked(controller.stabilizationEnabled());
            ink.refreshSettings(); handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        controller = Controller.get(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), 0);
        TextView title = new TextView(this); title.setText("Reflection / tablet input"); title.setTextSize(20); root.addView(title);
        LinearLayout address = new LinearLayout(this);
        host = field("Host", controller.host(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field("Port", String.valueOf(controller.port()), InputType.TYPE_CLASS_NUMBER);
        address.addView(host, new LinearLayout.LayoutParams(0, -2, 3)); address.addView(port, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(address);
        bluetooth = new CheckBox(this); bluetooth.setText("Bluetooth RFCOMM (paired host)"); bluetooth.setChecked("bluetooth".equals(controller.mode())); root.addView(bluetooth);
        bluetoothAddress = field("Paired host Bluetooth address", controller.bluetoothAddress(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        bluetoothAddress.setVisibility(bluetooth.isChecked() ? View.VISIBLE : View.GONE); root.addView(bluetoothAddress);
        bluetooth.setOnCheckedChangeListener((button, checked) -> bluetoothAddress.setVisibility(checked ? View.VISIBLE : View.GONE));
        token = field("64-character pairing token", controller.token(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setTransformationMethod(PasswordTransformationMethod.getInstance());
        root.addView(token);
        LinearLayout controls = new LinearLayout(this);
        button(controls, "Connect", v -> {
            if (displayedConfigurationRevision != controller.configurationRevision()) syncConfiguration();
            if (bluetooth.isChecked() && Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1); return;
            }
            try { controller.configure(host.getText().toString(), Integer.parseInt(port.getText().toString()), token.getText().toString().trim(), preview.isChecked(), bluetooth.isChecked() ? "bluetooth" : "tcp", bluetoothAddress.getText().toString().trim().toUpperCase(java.util.Locale.ROOT)); syncConfiguration(); controller.connect(); }
            catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        button(controls, "Disconnect", v -> { ink.cancelInput(); controller.disconnect(); });
        button(controls, "Clear", v -> { controller.clearPreview(); ink.refreshSettings(); });
        root.addView(controls);
        preview = new CheckBox(this); preview.setText("Local preview (standard Android rendering)"); preview.setChecked(controller.preview());
        preview.setOnCheckedChangeListener((button, checked) -> { controller.setPreview(checked); if (ink != null) ink.invalidate(); }); root.addView(preview);
        stabilization = new CheckBox(this); stabilization.setText("Reduce pen jitter"); stabilization.setChecked(controller.stabilizationEnabled());
        stabilization.setOnCheckedChangeListener((button, checked) -> {
            if (checked == controller.stabilizationEnabled()) return;
            try { controller.setStabilization(checked); }
            catch (Exception e) {
                stabilization.setChecked(controller.stabilizationEnabled());
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(stabilization);
        LinearLayout calibration = new LinearLayout(this);
        button(calibration, "Calibrate", v -> calibrationAction(() -> { ink.cancelInput(); controller.startCalibration(); }));
        button(calibration, "Finish", v -> calibrationAction(controller::finishCalibration));
        button(calibration, "Cancel", v -> calibrationAction(controller::cancelCalibration));
        button(calibration, "Reset", v -> calibrationAction(controller::resetCalibration));
        root.addView(calibration);
        status = new TextView(this); status.setTextSize(12); status.setLines(2); root.addView(status);
        ink = new InkView(this, controller); root.addView(ink, new LinearLayout.LayoutParams(-1, 0, 1));
        displayedConfigurationRevision = controller.configurationRevision();
        setContentView(root);
    }
    private void syncConfiguration() {
        // External CLI changes replace the form once; periodic status refresh preserves drafts.
        host.setText(controller.host());
        port.setText(String.valueOf(controller.port()));
        token.setText(controller.token());
        token.setTransformationMethod(PasswordTransformationMethod.getInstance());
        bluetooth.setChecked("bluetooth".equals(controller.mode()));
        bluetoothAddress.setText(controller.bluetoothAddress());
        displayedConfigurationRevision = controller.configurationRevision();
    }
    @Override protected void onResume() { super.onResume(); handler.post(update); }
    @Override protected void onPause() {
        controller.cancelCalibration(); ink.cancelInput(); handler.removeCallbacks(update); super.onPause();
    }
    @Override protected void onDestroy() { handler.removeCallbacks(update); super.onDestroy(); }
    private EditText field(String hint, String value, int type) {
        EditText field = new EditText(this); field.setHint(hint); field.setText(value); field.setSingleLine(true); field.setInputType(type); return field;
    }
    private void button(LinearLayout parent, String text, View.OnClickListener click) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(click); parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void calibrationAction(Runnable action) {
        try { action.run(); }
        catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
        ink.refreshSettings();
        status.setText(controller.statusLine());
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
