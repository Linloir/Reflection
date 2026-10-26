using System.Globalization;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;

namespace Reflection.Desktop;

public sealed partial class MainWindow : Window
{
    private readonly HostClient host = new();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly Queue<string> logLines = new();
    private bool ready, busy, loading, closed, running;
    private string revision = "";
    private int draggedPoint = -1;
    private List<Point> dragKnots = [];
    private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

    public MainWindow()
    {
        InitializeComponent();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1120, 900));
        if (MicaController.IsSupported())
        {
            SystemBackdrop = new MicaBackdrop();
            Root.Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        }
        host.Log += OnLog;
        timer.Tick += async (_, _) =>
        {
            if (!busy && ready)
            {
                busy = true;
                try { await RefreshStatusAsync(); }
                catch (Exception error)
                {
                    if (!closed) { StatusText.Text = "Status unavailable"; StatusDetail.Text = error.Message; }
                }
                finally { busy = false; }
            }
        };
        Root.Loaded += async (_, _) =>
        {
            if (ready) return;
            await PerformAsync(async () =>
            {
                await host.InitializeAsync();
                await ReloadAsync();
                ready = true;
                timer.Start();
            });
        };
        Closed += (_, _) =>
        {
            closed = true;
            timer.Stop();
            host.Log -= OnLog;
            host.Dispose();
        };
    }

    private async Task PerformAsync(Func<Task> operation)
    {
        if (busy || closed) return;
        busy = true;
        Settings.IsHitTestVisible = false;
        try { await operation(); }
        catch (Exception error) { if (!closed) ShowNotice(error.Message, InfoBarSeverity.Error); }
        finally
        {
            busy = false;
            if (!closed) Settings.IsHitTestVisible = true;
        }
    }

    private void ShowNotice(string message, InfoBarSeverity severity = InfoBarSeverity.Success)
    {
        if (closed) return;
        Notice.Message = message;
        Notice.Severity = severity;
        Notice.IsOpen = true;
    }

    private async Task ReloadAsync()
    {
        var snapshot = await host.SnapshotAsync();
        var displayList = await host.RunAsync("displays");
        if (closed) return;
        var config = snapshot.Values;
        revision = snapshot.Revision;
        loading = true;
        try
        {
            MappingMode.SelectedIndex = config["mapping.mode"] == "relative" ? 1 : 0;
            Source.Text = config["mapping.source"];
            Target.Text = config["mapping.target"];
            Rotation.SelectedIndex = int.Parse(config["mapping.rotation"], Invariant) / 90;
            Gamma.Value = double.Parse(config["mapping.pressure_gamma"], Invariant);
            Sensitivity.Value = double.Parse(config["mapping.sensitivity"], Invariant);
            TiltEnabled.IsOn = config["mapping.tilt_enabled"] == "true";
            TiltScale.Value = double.Parse(config["mapping.tilt_scale"], Invariant);
            StepsX.Value = double.Parse(config["mapping.position_steps_x"], Invariant);
            StepsY.Value = double.Parse(config["mapping.position_steps_y"], Invariant);
            var curve = config["mapping.pressure_curve"];
            PressureMode.SelectedIndex = curve.Length == 0 ? 0 : 1;
            CurvePoints.Text = curve.Length == 0 ? "0,0;0.25,0.1;0.75,0.9;1,1" : curve;
            Transport.SelectedIndex = config["transport"] == "bluetooth" ? 1 : 0;
            Bind.Text = config["bind"];
            Port.Value = double.Parse(config["port"], Invariant);
            Token.Password = "";
            Displays.Items.Clear();
            Displays.Items.Add(new ComboBoxItem { Content = "Entire desktop", Tag = "desktop" });
            foreach (var display in displayList.EnumerateArray())
            {
                var rectangle = string.Join(",", new[] { "x", "y", "width", "height" }
                    .Select(key => display.GetProperty(key).GetDouble().ToString(Invariant)));
                Displays.Items.Add(new ComboBoxItem { Content = display.GetProperty("name").GetString(), Tag = rectangle });
            }
            await RefreshProfilesAsync();
        }
        finally { loading = false; }
        RenderCurve();
        await RefreshStatusAsync();
    }

    private async Task RefreshProfilesAsync()
    {
        var selected = Profiles.SelectedItem as string;
        var list = await host.RunAsync("profile", "list");
        if (closed) return;
        Profiles.Items.Clear();
        foreach (var profile in list.GetProperty("profiles").EnumerateArray())
            Profiles.Items.Add(profile.GetString()!);
        if (selected is not null && Profiles.Items.Contains(selected)) Profiles.SelectedItem = selected;
    }

    private async Task RefreshStatusAsync()
    {
        var state = await host.RunAsync("status");
        if (closed) return;
        running = state.GetProperty("running").GetBoolean();
        StatusText.Text = running
            ? (state.GetProperty("connected").GetBoolean() ? "Tablet connected" :
                state.GetProperty("listening").GetBoolean() ? "Listening" : "Starting")
            : "Stopped";
        StatusDetail.Text = running
            ? $"Port {state.GetProperty("port").GetUInt32()} / {state.GetProperty("samples").GetUInt64()} samples" +
              (state.GetProperty("dry_run").GetBoolean() ? "\nDry run" : "\nWindows Ink input") +
              (state.GetProperty("restartRequired").GetBoolean() ? "\nSaved settings need a restart." : "")
            : "Ready when you are.";
        StartButton.IsEnabled = !running;
        StopButton.IsEnabled = running;
    }

    private Dictionary<string, string> CollectSettings()
    {
        if (PressureMode.SelectedIndex == 1) ParseKnots();
        var values = new Dictionary<string, string>
        {
            ["mapping.mode"] = MappingMode.SelectedIndex == 1 ? "relative" : "absolute",
            ["mapping.source"] = Source.Text.Trim(),
            ["mapping.target"] = Target.Text.Trim(),
            ["mapping.rotation"] = (Rotation.SelectedIndex * 90).ToString(Invariant),
            ["mapping.pressure_gamma"] = Gamma.Value.ToString(Invariant),
            ["mapping.pressure_curve"] = PressureMode.SelectedIndex == 1 ? CurvePoints.Text.Trim() : "",
            ["mapping.sensitivity"] = Sensitivity.Value.ToString(Invariant),
            ["mapping.tilt_enabled"] = TiltEnabled.IsOn ? "true" : "false",
            ["mapping.tilt_scale"] = TiltScale.Value.ToString(Invariant),
            ["mapping.position_steps_x"] = StepsX.Value.ToString(Invariant),
            ["mapping.position_steps_y"] = StepsY.Value.ToString(Invariant),
            ["transport"] = Transport.SelectedIndex == 1 ? "bluetooth" : "tcp",
            ["bind"] = Bind.Text.Trim(),
            ["port"] = Port.Value.ToString(Invariant)
        };
        if (Token.Password.Length > 0) values["token"] = Token.Password;
        return values;
    }

    private async Task SaveAsync()
    {
        var result = await host.ApplyAsync(CollectSettings(), revision);
        if (closed) return;
        revision = result.GetProperty("revision").GetString()!;
        Token.Password = "";
        await RefreshStatusAsync();
        ShowNotice(running ? "Settings saved. Restart the host to apply them." : "Settings saved.");
    }

    private async Task RestartAsync()
    {
        await host.StopAsync();
        await host.StartAsync(DryRun.IsChecked == true);
        await RefreshStatusAsync();
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        await host.InitializeAsync();
        await ReloadAsync();
        ready = true;
        timer.Start();
    });
    private async void Save_Click(object sender, RoutedEventArgs e) => await PerformAsync(SaveAsync);
    private async void SaveRestart_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        await SaveAsync();
        await RestartAsync();
        ShowNotice("Settings saved and host restarted.");
    });
    private async void Start_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        await host.StartAsync(DryRun.IsChecked == true);
        await RefreshStatusAsync();
    });
    private async void Stop_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        await host.StopAsync();
        await RefreshStatusAsync();
    });
    private async void Restart_Click(object sender, RoutedEventArgs e) => await PerformAsync(RestartAsync);

    private string SelectedProfile() => Profiles.SelectedItem as string
        ?? throw new InvalidOperationException("Select a saved profile first.");

    private async void SaveProfile_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        var name = ProfileName.Text.Trim();
        if (name.Length is < 1 or > 64 || name.Any(ch => !char.IsAsciiLetterOrDigit(ch) && ch != '_' && ch != '-'))
            throw new InvalidOperationException("Use 1-64 letters, digits, underscores or hyphens for a profile name.");
        if (ReplaceProfile.IsChecked != true &&
            (await host.RunAsync("profiles", "list")).GetProperty("profiles").EnumerateArray()
                .Any(item => string.Equals(item.GetString(), name, StringComparison.OrdinalIgnoreCase)))
            throw new InvalidOperationException("A profile with this name exists. Enable Replace existing profile to overwrite it.");
        // Save all fields through the same atomic CLI command before taking the mapping snapshot.
        await SaveAsync();
        await host.RunAsync(ReplaceProfile.IsChecked == true
            ? ["profile", "save", name, "--replace"]
            : ["profile", "save", name]);
        await RefreshProfilesAsync();
        ShowNotice("Mapping profile saved.");
    });
    private async void LoadProfile_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        await host.RunAsync("profile", "load", SelectedProfile());
        await ReloadAsync();
        ShowNotice("Profile loaded. Restart a running host to apply it.");
    });
    private async void DeleteProfile_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        var name = SelectedProfile();
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot, Title = $"Delete {name}?",
            Content = "This removes the saved mapping profile. Current settings stay active.",
            PrimaryButtonText = "Delete", CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        await host.RunAsync("profile", "delete", name);
        await RefreshProfilesAsync();
        ShowNotice("Profile deleted.");
    });
    private async void Pairing_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText((await host.ReadConfigAsync(true))["token"]);
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
        ShowNotice("Pairing key copied to the clipboard. Paste it only into your tablet pairing settings.",
            InfoBarSeverity.Informational);
    });
    private async void Doctor_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        var result = await host.RunAsync("doctor");
        ShowNotice(result.GetProperty("available").GetBoolean()
            ? "Windows Synthetic Pointer APIs are available. Test your target app with Windows Ink enabled."
            : "Windows Synthetic Pointer APIs are unavailable.", InfoBarSeverity.Informational);
    });

    private void DisplayChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!loading && Displays.SelectedItem is ComboBoxItem item && item.Tag is string rectangle)
            Target.Text = rectangle;
    }
    private void Preset_Click(object sender, RoutedEventArgs e)
    {
        PressureMode.SelectedIndex = 0;
        Gamma.Value = double.Parse((string)((Button)sender).Tag, Invariant);
        RenderCurve();
    }
    private void CurveChanged(object sender, SelectionChangedEventArgs e) => RenderCurve();
    private void GammaChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) => RenderCurve();
    private void CurveTextChanged(object sender, TextChangedEventArgs e) => RenderCurve();
    private void CurveSizeChanged(object sender, SizeChangedEventArgs e) => RenderCurve();

    private List<Point> ParseKnots()
    {
        var knots = CurvePoints.Text.Split(';').Select(knot =>
        {
            var pair = knot.Split(',');
            if (pair.Length != 2) throw new FormatException("Use input,output pairs separated by semicolons.");
            return new Point(double.Parse(pair[0], Invariant), double.Parse(pair[1], Invariant));
        }).ToList();
        if (knots.Count < 2 || knots.Count > 32 || knots[0] != new Point(0, 0) || knots[^1] != new Point(1, 1))
            throw new FormatException("Use 2-32 points, beginning at 0,0 and ending at 1,1.");
        for (var i = 0; i < knots.Count; ++i)
        {
            var p = knots[i];
            if (!double.IsFinite(p.X) || !double.IsFinite(p.Y) || p.X < 0 || p.X > 1 || p.Y < 0 || p.Y > 1 ||
                (i > 0 && (p.X <= knots[i - 1].X || p.Y < knots[i - 1].Y)))
                throw new FormatException("Inputs must increase and outputs must not decrease within 0-1.");
        }
        return knots;
    }

    private void RenderCurve()
    {
        if (closed || loading || CurveCanvas is null || CurveValidation is null || CurvePoints is null) return;
        CurveCanvas.Children.Clear();
        var width = Math.Max(CurveCanvas.ActualWidth, 1);
        var height = CurveCanvas.Height;
        var brush = new SolidColorBrush(Microsoft.UI.Colors.DodgerBlue);
        try
        {
            CurvePoints.IsEnabled = PressureMode.SelectedIndex == 1;
            Gamma.IsEnabled = PressureMode.SelectedIndex != 1;
            for (var i = 0; i <= 4; ++i)
            {
                CurveCanvas.Children.Add(new Line { X1 = i * width / 4, X2 = i * width / 4, Y1 = 0, Y2 = height,
                    Stroke = brush, StrokeThickness = 1, Opacity = 0.15 });
                CurveCanvas.Children.Add(new Line { X1 = 0, X2 = width, Y1 = i * height / 4, Y2 = i * height / 4,
                    Stroke = brush, StrokeThickness = 1, Opacity = 0.15 });
            }
            var knots = PressureMode.SelectedIndex == 1 ? ParseKnots() : Enumerable.Range(0, 101)
                .Select(i => new Point(i / 100.0, Math.Pow(i / 100.0, Gamma.Value))).ToList();
            var line = new Polyline { Stroke = brush, StrokeThickness = 2.5 };
            foreach (var p in knots) line.Points.Add(new Point(p.X * width, (1 - p.Y) * height));
            CurveCanvas.Children.Add(line);
            if (PressureMode.SelectedIndex == 1)
            {
                foreach (var point in knots)
                {
                    var handle = new Ellipse { Width = 10, Height = 10, Fill = brush };
                    Canvas.SetLeft(handle, point.X * width - 5);
                    Canvas.SetTop(handle, (1 - point.Y) * height - 5);
                    CurveCanvas.Children.Add(handle);
                }
            }
            CurveValidation.Text = PressureMode.SelectedIndex == 1
                ? "Custom points override gamma. Drag a point or edit the list to add or remove points."
                : "Gamma below 1 makes light pressure stronger; above 1 gives a firmer response.";
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            CurveValidation.Text = error.Message;
        }
    }

    private void CurvePointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (PressureMode.SelectedIndex != 1 || CurveCanvas.ActualWidth <= 0) return;
        try { dragKnots = ParseKnots(); }
        catch (Exception error) when (error is FormatException or OverflowException) { return; }
        var point = e.GetCurrentPoint(CurveCanvas).Position;
        for (var i = 1; i < dragKnots.Count - 1; ++i)
        {
            var x = dragKnots[i].X * CurveCanvas.ActualWidth - point.X;
            var y = (1 - dragKnots[i].Y) * CurveCanvas.Height - point.Y;
            if (x * x + y * y > 400) continue;
            draggedPoint = i;
            CurveCanvas.CapturePointer(e.Pointer);
            e.Handled = true;
            break;
        }
    }
    private void CurvePointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (draggedPoint < 1 || CurveCanvas.ActualWidth <= 0) return;
        var point = e.GetCurrentPoint(CurveCanvas).Position;
        var previous = dragKnots[draggedPoint - 1];
        var next = dragKnots[draggedPoint + 1];
        var gap = Math.Min(0.0001, (next.X - previous.X) / 4);
        dragKnots[draggedPoint] = new Point(
            Math.Clamp(point.X / CurveCanvas.ActualWidth, previous.X + gap, next.X - gap),
            Math.Clamp(1 - point.Y / CurveCanvas.Height, previous.Y, next.Y));
        CurvePoints.Text = string.Join(";", dragKnots.Select(p =>
            $"{p.X.ToString("G17", Invariant)},{p.Y.ToString("G17", Invariant)}"));
        e.Handled = true;
    }
    private void CurvePointerReleased(object sender, PointerRoutedEventArgs e)
    {
        draggedPoint = -1;
        CurveCanvas.ReleasePointerCapture(e.Pointer);
    }
    private void CurveCaptureLost(object sender, PointerRoutedEventArgs e) => draggedPoint = -1;

    private void OnLog(string line)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (closed) return;
            logLines.Enqueue(line);
            while (logLines.Count > 120) logLines.Dequeue();
            Log.Text = string.Join(Environment.NewLine, logLines);
        });
    }
}
