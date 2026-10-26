package io.github.linloir.reflection.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import io.github.linloir.reflection.control.Controller;
import io.github.linloir.reflection.input.PenInput;
import io.github.linloir.reflection.core.MappingState;

/** Deliberately small diagnostic UI over the same controller as the CLI. */
public final class MainActivity extends Activity {
    private Controller controller;
    private InkView ink;
    private EditText host, port, token, bluetoothAddress;
    private TextView status;
    private CheckBox preview, bluetooth;
    private RadioGroup tiltModes;
    private TextView tiltDiagnostics;
    private TextView mappingLabel, mappingDialogLabel;
    private MappingView mappingDiagram;
    private LinearLayout mappingButtons, mappingChoices;
    private String displayedMappingChoices = "";
    private long displayedConfigurationRevision;
    private final Handler handler = new Handler();
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (displayedConfigurationRevision != controller.configurationRevision()) syncConfiguration();
            String value = controller.statusLine();
            if (!value.contentEquals(status.getText())) status.setText(value);
            if (preview.isChecked() != controller.preview()) { preview.setChecked(controller.preview()); ink.invalidate(); }
            refreshTiltDialog();
            refreshMapping();
            ink.refreshSettings(); handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        controller = Controller.get(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), 0);
        TextView title = new TextView(this); title.setText(getApplicationInfo().loadLabel(getPackageManager()) + " / tablet input"); title.setTextSize(20); root.addView(title);
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
        button(controls, "Tilt", v -> showTiltSettings());
        root.addView(controls);
        LinearLayout mappingRow = new LinearLayout(this); mappingRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // Keep live host status from changing the input surface height in the middle of a stroke.
        mappingLabel = new TextView(this); mappingLabel.setTextSize(12); mappingLabel.setLines(2);
        mappingRow.addView(mappingLabel, new LinearLayout.LayoutParams(0, -2, 3));
        button(mappingRow, "Screens", v -> showMapping()); root.addView(mappingRow);
        preview = new CheckBox(this); preview.setText("Local preview (standard Android rendering)"); preview.setChecked(controller.preview());
        preview.setOnCheckedChangeListener((button, checked) -> { controller.setPreview(checked); if (ink != null) ink.invalidate(); }); root.addView(preview);
        status = new TextView(this); status.setTextSize(12); status.setLines(2); root.addView(status);
        ink = new InkView(this, controller); root.addView(ink, new LinearLayout.LayoutParams(-1, 0, 1));
        displayedConfigurationRevision = controller.configurationRevision();
        setContentView(root);
    }

    private void showMapping() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(8), dp(16), 0);
        mappingDialogLabel = new TextView(this); content.addView(mappingDialogLabel);
        mappingDiagram = new MappingView(this, id -> switchMapping("select", id));
        content.addView(mappingDiagram, new LinearLayout.LayoutParams(-1, dp(180)));
        mappingButtons = new LinearLayout(this);
        button(mappingButtons, "Previous", v -> switchMapping("previous", null));
        button(mappingButtons, "All screens", v -> switchMapping("all", null));
        button(mappingButtons, "Next", v -> switchMapping("next", null)); content.addView(mappingButtons);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        mappingChoices = new LinearLayout(this); mappingChoices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mappingChoices); content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(110)));
        displayedMappingChoices = "";
        TextView help = new TextView(this); help.setTextSize(12);
        help.setText("Tap a screen to map the whole tablet to it. All screens follows the computer's display layout. "
            + "A switch during a stroke waits until you lift the pen."); content.addView(help);
        refreshMapping();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Screen mapping").setView(content)
            .setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(d -> {
            mappingDialogLabel = null; mappingDiagram = null; mappingButtons = null; mappingChoices = null;
        }); dialog.show();
    }

    private void switchMapping(String action, String display) {
        try { controller.requestMapping(action, display); }
        catch (Exception error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void refreshMapping() {
        MappingState current = controller.mapping();
        String label = current == null ? "Screen mapping: connect to an updated host" : "Mapping: " + current.label();
        if (mappingLabel != null && !label.contentEquals(mappingLabel.getText())) mappingLabel.setText(label);
        if (mappingDialogLabel == null) return;
        if (!label.contentEquals(mappingDialogLabel.getText())) mappingDialogLabel.setText(label);
        mappingDiagram.update(current);
        for (int i = 0; i < mappingButtons.getChildCount(); i++) mappingButtons.getChildAt(i).setEnabled(current != null);
        StringBuilder identity = new StringBuilder();
        if (current != null) for (MappingState.Display display : current.displays)
            identity.append(display.id).append('\n').append(display.name).append('\n');
        String choices = identity.toString();
        if (!choices.equals(displayedMappingChoices)) {
            displayedMappingChoices = choices; mappingChoices.removeAllViews();
            if (current != null) for (int i = 0; i < current.displays.size(); i++) {
                MappingState.Display display = current.displays.get(i);
                Button option = new Button(this); option.setAllCaps(false);
                option.setText((i + 1) + ": " + display.name + (display.primary ? " (primary)" : ""));
                option.setOnClickListener(v -> switchMapping("select", display.id)); mappingChoices.addView(option);
            }
        }
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
    private void showTiltSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(8), dp(20), 0);
        TextView explanation = new TextView(this);
        explanation.setText("Auto recognizes supported device quirks. Standard uses Android angles directly. "
            + "Recovery may provide only one tilt axis; the other axis stays unavailable.");
        content.addView(explanation);
        tiltModes = new RadioGroup(this);
        String[] labels = {"Auto (recommended)", "Standard Android", "Off"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(this); option.setId(i + 1); option.setText(labels[i]);
            tiltModes.addView(option);
        }
        content.addView(tiltModes);
        tiltDiagnostics = new TextView(this); tiltDiagnostics.setTextSize(12); content.addView(tiltDiagnostics);
        refreshTiltDialog();
        tiltModes.setOnCheckedChangeListener((group, checked) -> {
            String mode = checked == 1 ? "auto" : checked == 2 ? "standard" : "off";
            try { controller.setTiltMode(mode); ink.refreshSettings(); }
            catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
            refreshTiltDialog();
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Pen tilt").setView(content)
            .setNeutralButton("Retry detection", (d, which) -> {
                controller.resetTiltDetection(); ink.refreshSettings();
            }).setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(d -> { tiltModes = null; tiltDiagnostics = null; });
        dialog.show();
    }
    private void refreshTiltDialog() {
        if (tiltModes == null || tiltDiagnostics == null) return;
        String mode = controller.tiltMode();
        int selected = "auto".equals(mode) ? 1 : "standard".equals(mode) ? 2 : 3;
        if (tiltModes.getCheckedRadioButtonId() != selected) tiltModes.check(selected);
        String text = "Last sample: " + PenInput.tiltStatus()
            + "\nUsable axes: " + PenInput.tiltAxes() + " | recovered samples: " + PenInput.recoveredTiltSamples()
            + "\nDevice: " + PenInput.lastDeviceName();
        if (PenInput.tiltAxes() == 1)
            text += "\nRecovered angle: " + PenInput.recoveredTiltDegrees()
                + " deg | input rotation: " + PenInput.tiltInputRotation();
        if (!text.contentEquals(tiltDiagnostics.getText())) tiltDiagnostics.setText(text);
    }
    @Override protected void onResume() { super.onResume(); handler.post(update); }
    @Override protected void onPause() {
        ink.cancelInput(); handler.removeCallbacks(update); super.onPause();
    }
    @Override protected void onDestroy() { handler.removeCallbacks(update); super.onDestroy(); }
    private EditText field(String hint, String value, int type) {
        EditText field = new EditText(this); field.setHint(hint); field.setText(value); field.setSingleLine(true); field.setInputType(type); return field;
    }
    private void button(LinearLayout parent, String text, View.OnClickListener click) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(click); parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
