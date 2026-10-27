using System.Globalization;
using System.Text.Json;
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
    private Dictionary<string, string> loadedValues = new();
    private int draggedPoint = -1;
    private uint? curvePointer;
    private int selectedPoint = -1;
    private bool updatingPoint;
    private List<Point> dragKnots = [];
    private ScreenShortcuts? screenShortcuts;
    private readonly List<ScreenHint> screenHints = [];
    private JsonElement? mappingState;
    private string mappingSignature = "";
    private bool mappingAvailable;
    private List<NearbyTablet> nearbyTablets = [];
    private string pairingSignature = "";
    private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

    public MainWindow()
    {
        InitializeComponent();
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
            var scale = Root.XamlRoot?.RasterizationScale ?? 1;
            var workArea = Microsoft.UI.Windowing.DisplayArea.GetFromWindowId(AppWindow.Id,
                Microsoft.UI.Windowing.DisplayAreaFallback.Nearest).WorkArea;
            AppWindow.Resize(new Windows.Graphics.SizeInt32(Math.Min(workArea.Width, (int)(1120 * scale)),
                Math.Min(workArea.Height, (int)(900 * scale))));
            await PerformAsync(async () =>
            {
                await host.InitializeAsync();
                await ReloadAsync();
                ready = true;
                ConfigureShortcuts();
                timer.Start();
            });
        };
        Closed += (_, _) =>
        {
            closed = true;
            timer.Stop();
            screenShortcuts?.Dispose();
            CloseScreenHints();
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
        loadedValues = new(config);
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
            ShowCursor.IsOn = !config.TryGetValue("mapping.show_cursor", out var showCursor) || showCursor == "true";
            TiltScale.Value = double.Parse(config["mapping.tilt_scale"], Invariant);
            StepsX.Value = double.Parse(config["mapping.position_steps_x"], Invariant);
            StepsY.Value = double.Parse(config["mapping.position_steps_y"], Invariant);
            var curve = config["mapping.pressure_curve"];
            PressureMode.SelectedIndex = curve.Length == 0 ? 0 :
                config.GetValueOrDefault("mapping.pressure_interpolation") == "smooth" ? 2 : 1;
            CurvePoints.Text = curve.Length == 0 ? "0,0;0.25,0.1;0.75,0.9;1,1" : curve;
            StabilizationEnabled.IsOn = config.GetValueOrDefault("stabilization.enabled", "true") == "true";
            AdaptiveNoise.IsOn = config.GetValueOrDefault("stabilization.adaptive_noise", "true") == "true";
            IndependentAxes.IsOn = config.GetValueOrDefault("stabilization.independent_axes", "false") == "true";
            StabilizationStrength.Value = double.Parse(config.GetValueOrDefault("stabilization.strength", "0.5"), Invariant);
            MinCutoff.Value = double.Parse(config.GetValueOrDefault("stabilization.min_cutoff_hz", "3"), Invariant);
            SpeedBeta.Value = double.Parse(config.GetValueOrDefault("stabilization.beta", "80"), Invariant);
            DerivativeCutoff.Value = double.Parse(config.GetValueOrDefault("stabilization.derivative_cutoff_hz", "12"), Invariant);
            Noise.Value = double.Parse(config.GetValueOrDefault("stabilization.noise", "0.0006"), Invariant);
            MaxJump.Value = double.Parse(config.GetValueOrDefault("stabilization.max_jump", "0.25"), Invariant);
            Transport.SelectedIndex = config["transport"] == "bluetooth" ? 1 : 0;
            Bind.Text = config["bind"];
            Port.Value = double.Parse(config["port"], Invariant);
            Token.Password = "";
            Displays.Items.Clear();
            Displays.Items.Add(new ComboBoxItem { Content = "All screens (Windows layout)", Tag = "desktop" });
            foreach (var display in displayList.EnumerateArray())
            {
                var name = display.GetProperty("name").GetString()!;
                Displays.Items.Add(new ComboBoxItem { Content = name, Tag = "display:" + name });
            }
            Displays.SelectedItem = Displays.Items.OfType<ComboBoxItem>().FirstOrDefault(item => (string)item.Tag == Target.Text);
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
        UpdateStability(state);
        await RefreshPairingAsync();
        try { UpdateMapping(await host.RunAsync("mapping", "status")); }
        catch (InvalidOperationException error)
        {
            mappingAvailable = false;
            ActiveMappingText.Text = "Screen controls unavailable";
            MappingDetail.Text = error.Message;
        }
    }

    private async Task RefreshPairingAsync()
    {
        EnableLan.Visibility = loadedValues.GetValueOrDefault("bind") == "127.0.0.1" ? Visibility.Visible : Visibility.Collapsed;
        if (!running)
        {
            PairingStatus.Text = "Start the host to discover tablets.";
            nearbyTablets = []; NearbyTablets.ItemsSource = nearbyTablets; pairingSignature = "";
            return;
        }
        try
        {
            var devices = await host.RunAsync("pairing", "list");
            if (closed) return;
            var signature = devices.GetRawText();
            if (signature != pairingSignature)
            {
                pairingSignature = signature;
                nearbyTablets = devices.EnumerateArray().Select(value => new NearbyTablet(
                    value.GetProperty("id").GetString()!, value.GetProperty("name").GetString()!,
                    value.GetProperty("address").GetString()!, value.GetProperty("code").GetString()!,
                    value.GetProperty("trusted").GetBoolean())).ToList();
                NearbyTablets.ItemsSource = nearbyTablets;
            }
            PairingStatus.Text = nearbyTablets.Count == 0
                ? "Looking for tablets. Keep Reflection open on the tablet."
                : $"{nearbyTablets.Count} nearby tablet(s). Select the one you want to use.";
            if (loadedValues.GetValueOrDefault("bind") == "127.0.0.1")
                PairingStatus.Text += "\nLAN discovery is disabled in this saved configuration; USB and paired Bluetooth remain available.";
        }
        catch (Exception error) { if (!closed) PairingStatus.Text = error.Message; }
    }

    private async void TrustTablet_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        if (sender is not Button { Tag: string id }) return;
        var tablet = nearbyTablets.FirstOrDefault(value => value.Id == id);
        if (tablet is null) return;
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot, Title = $"Connect {tablet.Name}?",
            Content = $"Check that the tablet displays {tablet.Code}.\n\nAddress: {tablet.Address}\nApproval connects this tablet and releases the current tablet session.",
            PrimaryButtonText = "Code matches - trust and connect", CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var result = await host.RunAsync("pairing", "trust", tablet.Id);
        if (result.TryGetProperty("usbSelected", out var usbSelected) && usbSelected.GetBoolean())
        {
            ShowNotice($"{tablet.Name} selected over its authorized USB connection.");
            await RefreshPairingAsync();
            return;
        }
        var confirmationCode = result.GetProperty("confirmationCode").GetString();
        var confirmation = new ContentDialog
        {
            XamlRoot = Root.XamlRoot, Title = $"Confirm on {tablet.Name}",
            Content = $"Connection code: {confirmationCode}\n\nOnly approve the computer on the tablet if it shows this same connection code.",
            CloseButtonText = "Done", DefaultButton = ContentDialogButton.Close
        };
        await confirmation.ShowAsync();
        await RefreshPairingAsync();
    });

    private async void EnableLan_Click(object sender, RoutedEventArgs e) => await PerformAsync(async () =>
    {
        var snapshot = await host.SnapshotAsync();
        await host.ApplyAsync(new Dictionary<string, string> { ["bind"] = "0.0.0.0" }, snapshot.Revision);
        Bind.Text = "0.0.0.0"; loadedValues["bind"] = "0.0.0.0";
        revision = (await host.SnapshotAsync()).Revision;
        if (running) await host.StopAsync();
        await host.StartAsync(DryRun.IsChecked == true);
        await RefreshStatusAsync();
    });

    private Dictionary<string, string> CollectSettings()
    {
        if (PressureMode.SelectedIndex > 0) ParseKnots();
        var values = new Dictionary<string, string>
        {
            ["mapping.mode"] = MappingMode.SelectedIndex == 1 ? "relative" : "absolute",
            ["mapping.source"] = Source.Text.Trim(),
            ["mapping.target"] = Target.Text.Trim(),
            ["mapping.rotation"] = (Rotation.SelectedIndex * 90).ToString(Invariant),
            ["mapping.pressure_gamma"] = Gamma.Value.ToString(Invariant),
            ["mapping.pressure_curve"] = PressureMode.SelectedIndex > 0 ? CurvePoints.Text.Trim() : "",
            ["mapping.pressure_interpolation"] = PressureMode.SelectedIndex == 2 ? "smooth" : "linear",
            ["stabilization.enabled"] = StabilizationEnabled.IsOn ? "true" : "false",
            ["stabilization.adaptive_noise"] = AdaptiveNoise.IsOn ? "true" : "false",
            ["stabilization.independent_axes"] = IndependentAxes.IsOn ? "true" : "false",
            ["stabilization.strength"] = StabilizationStrength.Value.ToString(Invariant),
            ["stabilization.min_cutoff_hz"] = MinCutoff.Value.ToString(Invariant),
            ["stabilization.beta"] = SpeedBeta.Value.ToString(Invariant),
            ["stabilization.derivative_cutoff_hz"] = DerivativeCutoff.Value.ToString(Invariant),
            ["stabilization.noise"] = Noise.Value.ToString(Invariant),
            ["stabilization.max_jump"] = MaxJump.Value.ToString(Invariant),
            ["mapping.sensitivity"] = Sensitivity.Value.ToString(Invariant),
            ["mapping.tilt_enabled"] = TiltEnabled.IsOn ? "true" : "false",
            ["mapping.show_cursor"] = ShowCursor.IsOn ? "true" : "false",
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
        var values = CollectSettings();
        var result = await host.ApplyAsync(values, revision);
        if (closed) return;
        revision = result.GetProperty("revision").GetString()!;
        foreach (var pair in values.Where(pair => pair.Key != "token")) loadedValues[pair.Key] = pair.Value;
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

    private void UpdateMapping(JsonElement value)
    {
        if (closed) return;
        mappingAvailable = true;
        mappingState = value.Clone();
        var mode = value.GetProperty("mode").GetString();
        var selected = value.GetProperty("selected").GetString() ?? "";
        var pending = value.GetProperty("pending").GetBoolean();
        var displayList = value.GetProperty("displays").EnumerateArray().ToList();
        var index = displayList.FindIndex(d => d.GetProperty("id").GetString() == selected);
        var label = mode == "all" ? "All screens" : mode == "custom" ? "Custom region" :
            index >= 0 ? $"Screen {index + 1} · {displayList[index].GetProperty("name").GetString()}" : "Selected screen";
        ActiveMappingText.Text = (running ? "Active: " : "Startup: ") + label;
        MappingDetail.Text = pending ? "Switch queued. Lift the pen to finish your stroke and change screens." :
            value.TryGetProperty("fallback", out var fallback) && fallback.ValueKind == JsonValueKind.True
                ? "The selected screen is unavailable. Using an available screen until it returns."
                : running ? "Tablet and host screen selection stay in sync." : "Start the host to switch screens live.";
        var signature = mode + "|" + selected + "|" + value.GetProperty("target").GetRawText() + "|" + value.GetProperty("displays").GetRawText();
        if (signature != mappingSignature)
        {
            var notify = mappingSignature.Length > 0 && running && !pending;
            mappingSignature = signature;
            RenderScreenLayout();
            if (notify) ShowScreenHints(false);
        }
    }

    private void RenderScreenLayout()
    {
        if (ScreenCanvas is null || mappingState is not JsonElement state) return;
        ScreenCanvas.Children.Clear();
        var screens = state.GetProperty("displays").EnumerateArray().ToList();
        if (screens.Count == 0) return;
        var left = screens.Min(d => d.GetProperty("x").GetDouble());
        var top = screens.Min(d => d.GetProperty("y").GetDouble());
        var right = screens.Max(d => d.GetProperty("x").GetDouble() + d.GetProperty("width").GetDouble());
        var bottom = screens.Max(d => d.GetProperty("y").GetDouble() + d.GetProperty("height").GetDouble());
        var width = Math.Max(ScreenCanvas.ActualWidth, 1);
        var scale = Math.Min(width / Math.Max(right - left, 1), ScreenCanvas.Height / Math.Max(bottom - top, 1));
        var offsetX = (width - (right - left) * scale) / 2;
        var offsetY = (ScreenCanvas.Height - (bottom - top) * scale) / 2;
        for (var i = 0; i < screens.Count; ++i)
        {
            var screen = screens[i];
            var id = screen.GetProperty("id").GetString()!;
            var active = state.GetProperty("mode").GetString() == "all" || state.GetProperty("selected").GetString() == id;
            var button = new Button
            {
                Content = new TextBlock { Text = $"{i + 1}" + (screen.GetProperty("primary").GetBoolean() ? " · Main" : ""),
                    FontSize = 18, TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center },
                Width = Math.Max(1, screen.GetProperty("width").GetDouble() * scale - 6),
                Height = Math.Max(1, screen.GetProperty("height").GetDouble() * scale - 6),
                Padding = new Thickness(2), HorizontalContentAlignment = HorizontalAlignment.Center,
                VerticalContentAlignment = VerticalAlignment.Center,
                BorderThickness = new Thickness(active ? 3 : 1),
                BorderBrush = new SolidColorBrush(active ? Microsoft.UI.Colors.DodgerBlue : Microsoft.UI.Colors.Gray),
                Tag = id
            };
            ToolTipService.SetToolTip(button, screen.GetProperty("name").GetString());
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, $"Map to screen {i + 1}: {screen.GetProperty("name").GetString()}");
            button.Click += async (_, _) => await SwitchScreenAsync("select", id);
            Canvas.SetLeft(button, offsetX + (screen.GetProperty("x").GetDouble() - left) * scale + 3);
            Canvas.SetTop(button, offsetY + (screen.GetProperty("y").GetDouble() - top) * scale + 3);
            ScreenCanvas.Children.Add(button);
        }
    }

    private async Task SwitchScreenAsync(string action, string? display = null)
    {
        await PerformAsync(async () =>
        {
            if (!running || !mappingAvailable) throw new InvalidOperationException("Start the updated host before switching screens.");
            var state = await host.RunAsync(display is null ? ["mapping", action] : ["mapping", action, display]);
            UpdateMapping(state);
        });
    }

    private void ConfigureShortcuts()
    {
        if (!ready || closed) return;
        screenShortcuts?.Dispose();
        screenShortcuts = null;
        if (!ScreenShortcuts.IsOn) { ShortcutHint.Text = "Screen switching shortcuts are off."; return; }
        screenShortcuts = new ScreenShortcuts(this, action => DispatcherQueue.TryEnqueue(async () => await SwitchScreenAsync(action)));
        ShortcutHint.Text = screenShortcuts.Error ?? "Ctrl+Alt+PageUp / PageDown: previous / next screen. Ctrl+Alt+0: all screens. Keep this window open (it can be minimized).";
    }

    private void CloseScreenHints()
    {
        foreach (var hint in screenHints.ToArray()) hint.Dismiss();
        screenHints.Clear();
    }

    private void ShowScreenHints(bool identify)
    {
        if (closed || mappingState is not JsonElement state) return;
        CloseScreenHints();
        var screens = state.GetProperty("displays").EnumerateArray().ToList();
        var mode = state.GetProperty("mode").GetString();
        var selected = state.GetProperty("selected").GetString();
        for (var i = 0; i < screens.Count; ++i)
        {
            var screen = screens[i];
            if (!identify && mode == "display" && screen.GetProperty("id").GetString() != selected) continue;
            var hint = new ScreenHint(screen, $"Reflection · Screen {i + 1}", identify ? screen.GetProperty("name").GetString()! :
                mode == "all" ? "Tablet mapped to all screens" : mode == "custom" ? "Tablet mapped to custom region" : "Tablet mapped to this screen");
            screenHints.Add(hint);
            hint.Closed += (_, _) => screenHints.Remove(hint);
            hint.ShowWithoutActivation();
        }
    }

    private void ScreenCanvas_SizeChanged(object sender, SizeChangedEventArgs e) => RenderScreenLayout();
    private void ScreenShortcuts_Toggled(object sender, RoutedEventArgs e) => ConfigureShortcuts();
    private async void AllScreens_Click(object sender, RoutedEventArgs e) => await SwitchScreenAsync("all");
    private async void PreviousScreen_Click(object sender, RoutedEventArgs e) => await SwitchScreenAsync("previous");
    private async void NextScreen_Click(object sender, RoutedEventArgs e) => await SwitchScreenAsync("next");
    private void IdentifyScreens_Click(object sender, RoutedEventArgs e) => ShowScreenHints(true);
    private void UseActiveMapping_Click(object sender, RoutedEventArgs e)
    {
        if (mappingState is not JsonElement state) return;
        var mode = state.GetProperty("mode").GetString();
        Target.Text = mode == "all" ? "desktop" : mode == "display" ? "display:" + state.GetProperty("selected").GetString() :
            string.Join(",", new[] { "x", "y", "width", "height" }.Select(key => state.GetProperty("target").GetProperty(key).GetDouble().ToString(Invariant)));
        ShowNotice("Startup target updated in the form. Save settings to keep it.", InfoBarSeverity.Informational);
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

    private List<Point> ParseKnots() => PressureCurve.Parse(CurvePoints.Text).Select(p => new Point(p.X, p.Y)).ToList();

    private void WriteKnots(List<Point> knots)
    {
        CurvePoints.Text = string.Join(";", knots.Select(p =>
            $"{p.X.ToString("G17", Invariant)},{p.Y.ToString("G17", Invariant)}"));
        // TextChanged can be deferred while the coordinate editor is collapsed.
        // Repaint from the updated points immediately during direct manipulation.
        RenderCurve();
    }

    private void UpdateSelectedPoint(List<Point>? knots)
    {
        if (SelectedInput is null || SelectedOutput is null || RemovePoint is null) return;
        updatingPoint = true;
        try
        {
            var editable = PressureMode.SelectedIndex > 0 && knots is not null && selectedPoint > 0 && selectedPoint < knots.Count - 1;
            SelectedInput.IsEnabled = SelectedOutput.IsEnabled = RemovePoint.IsEnabled = editable;
            SelectedInput.Value = editable ? knots![selectedPoint].X * 100 : double.NaN;
            SelectedOutput.Value = editable ? knots![selectedPoint].Y * 100 : double.NaN;
        }
        finally { updatingPoint = false; }
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
            CurvePoints.IsEnabled = PressureMode.SelectedIndex > 0;
            Gamma.IsEnabled = PressureMode.SelectedIndex == 0;
            for (var i = 0; i <= 4; ++i)
            {
                CurveCanvas.Children.Add(new Line { X1 = i * width / 4, X2 = i * width / 4, Y1 = 0, Y2 = height,
                    Stroke = brush, StrokeThickness = 1, Opacity = 0.15, IsHitTestVisible = false });
                CurveCanvas.Children.Add(new Line { X1 = 0, X2 = width, Y1 = i * height / 4, Y2 = i * height / 4,
                    Stroke = brush, StrokeThickness = 1, Opacity = 0.15, IsHitTestVisible = false });
            }
            var knots = PressureMode.SelectedIndex > 0 ? ParseKnots() : null;
            var control = knots?.Select(p => new CurveKnot(p.X, p.Y)).ToArray();
            CurveCanvas.Children.Add(new Line { X1 = 0, Y1 = height, X2 = width, Y2 = 0,
                Stroke = brush, StrokeThickness = 1, Opacity = 0.3, StrokeDashArray = new DoubleCollection { 4, 4 }, IsHitTestVisible = false });
            var line = new Polyline { Stroke = brush, StrokeThickness = 2.5, IsHitTestVisible = false };
            if (PressureMode.SelectedIndex == 1)
                foreach (var p in knots!) line.Points.Add(new Point(p.X * width, (1 - p.Y) * height));
            else
                for (var i = 0; i <= 256; ++i)
                {
                    var x = i / 256.0;
                    var y = control is null ? Math.Pow(x, Gamma.Value) : PressureCurve.Evaluate(control, x, true);
                    line.Points.Add(new Point(x * width, (1 - y) * height));
                }
            CurveCanvas.Children.Add(line);
            if (knots is not null)
            {
                for (var i = 0; i < knots.Count; ++i)
                {
                    var point = knots[i];
                    var handle = new Ellipse { Width = 12, Height = 12, Fill = i == selectedPoint ?
                        new SolidColorBrush(Microsoft.UI.Colors.White) : brush, Stroke = brush, StrokeThickness = 2,
                        IsHitTestVisible = false };
                    Canvas.SetLeft(handle, point.X * width - 6);
                    Canvas.SetTop(handle, (1 - point.Y) * height - 6);
                    CurveCanvas.Children.Add(handle);
                }
            }
            UpdateSelectedPoint(knots);
            CurveValidation.Text = PressureMode.SelectedIndex == 2
                ? "Smooth curve passes through each point without overshoot. More pressure never produces less output."
                : PressureMode.SelectedIndex == 1 ? "Straight segments connect the points. Click an empty area to add a point."
                : "Gamma below 1 makes light pressure stronger; above 1 gives a firmer response.";
        }
        catch (Exception error) when (error is FormatException or OverflowException)
        {
            CurveValidation.Text = error.Message;
            UpdateSelectedPoint(null);
        }
    }

    private void CurvePointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (curvePointer is not null || PressureMode.SelectedIndex < 1 || CurveCanvas.ActualWidth <= 0) return;
        try { dragKnots = ParseKnots(); }
        catch (Exception error) when (error is FormatException or OverflowException) { return; }
        e.Handled = true;
        CurveCanvas.CancelDirectManipulations();
        var point = e.GetCurrentPoint(CurveCanvas).Position;
        selectedPoint = -1;
        var nearest = 400.0;
        for (var i = 1; i < dragKnots.Count - 1; ++i)
        {
            var x = dragKnots[i].X * CurveCanvas.ActualWidth - point.X;
            var y = (1 - dragKnots[i].Y) * CurveCanvas.Height - point.Y;
            if (x * x + y * y > nearest) continue;
            nearest = x * x + y * y;
            selectedPoint = i;
        }
        if (selectedPoint < 0 && dragKnots.Count < 32)
        {
            var x = Math.Clamp(point.X / CurveCanvas.ActualWidth, 0, 1);
            var index = dragKnots.FindIndex(p => p.X > x);
            if (index > 0 && x - dragKnots[index - 1].X >= 0.0001 && dragKnots[index].X - x >= 0.0001)
            {
                dragKnots.Insert(index, new Point(x, Math.Clamp(1 - point.Y / CurveCanvas.Height,
                    dragKnots[index - 1].Y, dragKnots[index].Y)));
                selectedPoint = index;
                WriteKnots(dragKnots);
            }
        }
        draggedPoint = selectedPoint;
        if (draggedPoint > 0 && CurveCanvas.CapturePointer(e.Pointer)) curvePointer = e.Pointer.PointerId;
        else draggedPoint = -1;
        RenderCurve();
        e.Handled = true;
    }
    private void CurvePointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (curvePointer != e.Pointer.PointerId || draggedPoint < 1 || CurveCanvas.ActualWidth <= 0) return;
        var point = e.GetCurrentPoint(CurveCanvas).Position;
        var previous = dragKnots[draggedPoint - 1];
        var next = dragKnots[draggedPoint + 1];
        var gap = Math.Min(0.0001, (next.X - previous.X) / 4);
        dragKnots[draggedPoint] = new Point(
            Math.Clamp(point.X / CurveCanvas.ActualWidth, previous.X + gap, next.X - gap),
            Math.Clamp(1 - point.Y / CurveCanvas.Height, previous.Y, next.Y));
        WriteKnots(dragKnots);
        e.Handled = true;
    }
    private void CurvePointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (curvePointer != e.Pointer.PointerId) return;
        draggedPoint = -1;
        curvePointer = null;
        CurveCanvas.ReleasePointerCapture(e.Pointer);
    }
    private void CurveCaptureLost(object sender, PointerRoutedEventArgs e)
    {
        if (curvePointer != e.Pointer.PointerId) return;
        draggedPoint = -1;
        curvePointer = null;
    }

    private void SelectedPointChanged(NumberBox sender, NumberBoxValueChangedEventArgs args)
    {
        if (updatingPoint || loading || selectedPoint < 1 || !double.IsFinite(args.NewValue)) return;
        try
        {
            var knots = ParseKnots();
            if (selectedPoint >= knots.Count - 1) return;
            var previous = knots[selectedPoint - 1];
            var next = knots[selectedPoint + 1];
            var gap = Math.Min(0.0001, (next.X - previous.X) / 4);
            var current = knots[selectedPoint];
            knots[selectedPoint] = sender == SelectedInput
                ? new Point(Math.Clamp(args.NewValue / 100, previous.X + gap, next.X - gap), current.Y)
                : new Point(current.X, Math.Clamp(args.NewValue / 100, previous.Y, next.Y));
            WriteKnots(knots);
        }
        catch (Exception error) when (error is FormatException or OverflowException) { }
    }

    private void RemovePoint_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var knots = ParseKnots();
            if (selectedPoint < 1 || selectedPoint >= knots.Count - 1) return;
            knots.RemoveAt(selectedPoint);
            selectedPoint = -1;
            WriteKnots(knots);
        }
        catch (Exception error) when (error is FormatException or OverflowException) { }
    }

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
