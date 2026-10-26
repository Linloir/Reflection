using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Reflection.Desktop;

public sealed partial class MainWindow
{
    private bool tabletConnected;
    private static bool Flag(JsonElement state, string name) => state.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;
    private static string Label(JsonElement state, string name) => state.TryGetProperty(name, out var value) ? value.ToString() : "";
    private static double Number(JsonElement state, string name) => state.TryGetProperty(name, out var value) && value.TryGetDouble(out var number) ? number : 0;

    private void UpdateStability(JsonElement state)
    {
        tabletConnected = running && Flag(state, "connected");
        CalibrateStart.IsEnabled = tabletConnected;
        if (!running)
        {
            StabilityStatus.Text = "Start the host to see input diagnostics.";
            NoiseStatus.Text = "Noise estimates become available while using the pen.";
            CalibrationStatus.Text = "Connect the tablet before starting calibration.";
            CalibrationProgress.Value = 0;
            CalibrateFinish.IsEnabled = CalibrateCancel.IsEnabled = false;
            return;
        }
        if (state.TryGetProperty("stabilization", out var stability))
        {
            StabilityStatus.Text = Flag(stability, "legacy_client")
                ? "This tablet uses client-side smoothing. Update Reflection on the tablet to use these controls."
                : Flag(stability, "active") ? "Computer smoothing active." : "Smoothing off; jump protection active.";
            StabilityStatus.Text += $" Protected jumps: {Number(stability, "guard_rejections"):0}. Interrupted strokes: {Number(stability, "suppressed_strokes"):0}.";
            if(Flag(stability,"independent_axes")) StabilityStatus.Text += " Extra horizontal/vertical stroke smoothing active.";
            NoiseStatus.Text = Flag(stability, "adaptive_noise")
                ? $"Current noise estimate: X {Number(stability, "estimated_noise_x"):0.000000}, Y {Number(stability, "estimated_noise_y"):0.000000} of the tablet area. {Number(stability, "noise_samples"):0} recent samples."
                : "Using the fixed calibrated noise level.";
            if (state.TryGetProperty("calibration", out var calibration)) UpdateCalibration(calibration);
            else if (stability.TryGetProperty("calibration", out calibration)) UpdateCalibration(calibration);
        }
        else
        {
            StabilityStatus.Text = "Restart the updated host to use computer-side pen stability.";
            CalibrateStart.IsEnabled = CalibrateFinish.IsEnabled = CalibrateCancel.IsEnabled = false;
        }
    }

    private void UpdateCalibration(JsonElement calibration)
    {
        var active = Flag(calibration, "active");
        var phase = Label(calibration, "phase");
        var reason = Label(calibration, "reason");
        CalibrationProgress.Value = Math.Clamp(Number(calibration, "progress"), 0, 1);
        CalibrateStart.IsEnabled = tabletConnected && !active;
        CalibrateFinish.IsEnabled = CalibrateCancel.IsEnabled = running && active;
        CalibrationStatus.Text = phase switch
        {
            "collecting" => $"Collecting: {Number(calibration, "samples"):0} points, {Number(calibration, "duration_ms") / 1000:0.0}s. Keep the pen on one spot.",
            "ready" => "Enough samples collected. Click Finish and apply, then lift the pen before writing.",
            "complete" => $"Applied. Estimated noise: {Number(calibration, "noise"):0.000000}. Lift the pen before writing.",
            "failed" => "Calibration was not applied. " + reason,
            "cancelled" => "Calibration cancelled. The previous noise setting is unchanged.",
            _ => "Ready to calibrate."
        };
        if (active && reason.Length > 0) CalibrationStatus.Text += " " + reason;
    }

    private async Task CalibrationAsync(string action)
    {
        var calibration = await host.RunAsync("calibration", action, "--json");
        if (closed) return;
        UpdateCalibration(calibration);
        if (action == "finish" && Label(calibration, "result") == "success")
        {
            // Only accept the new revision if calibration was the only external edit.
            // Otherwise keep optimistic concurrency protection for the unsaved form.
            var snapshot = await host.SnapshotAsync();
            Noise.Value = double.Parse(snapshot.Values["stabilization.noise"], Invariant);
            var compatible = snapshot.Values.Where(p => p.Key != "stabilization.noise")
                .All(p => loadedValues.TryGetValue(p.Key, out var original) && original == p.Value);
            if (compatible)
            {
                revision = snapshot.Revision;
                loadedValues = new(snapshot.Values);
            }
            ShowNotice(compatible ? "Noise calibration saved and applied. Other edits in this form are still unsaved."
                : "Noise calibration applied. Configuration also changed elsewhere; refresh before saving other settings.",
                compatible ? InfoBarSeverity.Success : InfoBarSeverity.Warning);
        }
        await RefreshStatusAsync();
    }

    private async void CalibrationStart_Click(object sender, RoutedEventArgs e) => await PerformAsync(() => CalibrationAsync("start"));
    private async void CalibrationFinish_Click(object sender, RoutedEventArgs e) => await PerformAsync(() => CalibrationAsync("finish"));
    private async void CalibrationCancel_Click(object sender, RoutedEventArgs e) => await PerformAsync(() => CalibrationAsync("cancel"));

    private void StabilizationDefaults_Click(object sender, RoutedEventArgs e)
    {
        StabilizationEnabled.IsOn = true;
        AdaptiveNoise.IsOn = true;
        IndependentAxes.IsOn = false;
        StabilizationStrength.Value = 0.5;
        MinCutoff.Value = 3;
        SpeedBeta.Value = 80;
        DerivativeCutoff.Value = 12;
        Noise.Value = 0.0006;
        MaxJump.Value = 0.25;
        ShowNotice("Default smoothing values restored in the form. Save and restart to apply them.", InfoBarSeverity.Informational);
    }
}
