using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Reflection.Desktop;

// Register shortcuts on the UI window; the input host and tablet remain independent.
internal sealed class ScreenShortcuts : IDisposable
{
    private readonly nint window;
    private readonly SubclassProc callback;
    private readonly List<int> registered = [];
    private bool disposed;
    public string? Error { get; }

    public ScreenShortcuts(Window owner, Action<string> command)
    {
        window = WinRT.Interop.WindowNative.GetWindowHandle(owner);
        callback = (hwnd, message, wParam, lParam, _, _) =>
        {
            if (message == 0x0312 && registered.Contains((int)wParam))
            {
                command((int)wParam == 0x501 ? "previous" : (int)wParam == 0x502 ? "next" : "all");
                return 0;
            }
            return DefSubclassProc(hwnd, message, wParam, lParam);
        };
        if (!SetWindowSubclass(window, callback, 0x5246, 0))
        {
            Error = "Screen shortcuts could not be registered. Use the screen buttons.";
            return;
        }
        foreach (var (id, key) in new[] { (0x501, 0x21u), (0x502, 0x22u), (0x503, 0x30u) })
        {
            if (RegisterHotKey(window, id, 0x4003, key)) registered.Add(id);
            else Error = "A screen shortcut is already in use. Available shortcuts and the screen buttons still work.";
        }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        foreach (var id in registered) UnregisterHotKey(window, id);
        registered.Clear();
        RemoveWindowSubclass(window, callback, 0x5246);
    }

    private delegate nint SubclassProc(nint hwnd, uint message, nuint wParam, nint lParam, nuint id, nuint data);
    [DllImport("comctl32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(nint hwnd, SubclassProc callback, nuint id, nuint data);
    [DllImport("comctl32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(nint hwnd, SubclassProc callback, nuint id);
    [DllImport("comctl32.dll")]
    private static extern nint DefSubclassProc(nint hwnd, uint message, nuint wParam, nint lParam);
    [DllImport("user32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RegisterHotKey(nint hwnd, int id, uint modifiers, uint key);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnregisterHotKey(nint hwnd, int id);
}

// A short-lived, click-through hint on the physical monitor. It never takes focus
// from the drawing app and cannot intercept the pen's next stroke.
internal sealed class ScreenHint : Window
{
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly nint handle;
    private bool dismissed;

    public ScreenHint(JsonElement display, string title, string detail)
    {
        Title = "Reflection screen indicator";
        Content = new Border
        {
            Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 22, 32, 48)),
            BorderBrush = new SolidColorBrush(Microsoft.UI.Colors.DodgerBlue),
            BorderThickness = new Thickness(2), Padding = new Thickness(20, 14, 20, 14),
            Child = new StackPanel
            {
                Spacing = 6,
                Children =
                {
                    new TextBlock { Text = title, FontSize = 20, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold },
                    new TextBlock { Text = detail, FontSize = 13, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White), TextTrimming = TextTrimming.CharacterEllipsis }
                }
            }
        };
        handle = WinRT.Interop.WindowNative.GetWindowHandle(this);
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.SetBorderAndTitleBar(false, false);
            presenter.IsResizable = false;
            presenter.IsMaximizable = false;
            presenter.IsMinimizable = false;
            presenter.IsAlwaysOnTop = true;
        }
        var style = GetWindowLongPtrW(handle, -20).ToInt64();
        SetWindowLongPtrW(handle, -20, new nint(style | 0x08000000L | 0x80L | 0x20L));
        var x = (int)display.GetProperty("x").GetDouble();
        var y = (int)display.GetProperty("y").GetDouble();
        var monitorWidth = (int)display.GetProperty("width").GetDouble();
        var monitorHeight = (int)display.GetProperty("height").GetDouble();
        // Move first so GetDpiForWindow describes the destination monitor.
        AppWindow.Move(new Windows.Graphics.PointInt32(x + monitorWidth / 2, y + monitorHeight / 2));
        var scale = Math.Max(1.0, GetDpiForWindow(handle) / 96.0);
        var width = Math.Min(monitorWidth, (int)(420 * scale));
        var height = Math.Min(monitorHeight, (int)(100 * scale));
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(x + (monitorWidth - width) / 2,
            y + Math.Min(Math.Max(0, (int)(40 * scale)), Math.Max(0, monitorHeight - height)), width, height));
        timer.Tick += (_, _) => Dismiss();
        Closed += (_, _) => { dismissed = true; timer.Stop(); };
    }

    public void ShowWithoutActivation()
    {
        AppWindow.Show(false);
        timer.Start();
    }

    public void Dismiss()
    {
        if (dismissed) return;
        dismissed = true;
        timer.Stop();
        Close();
    }

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern nint GetWindowLongPtrW(nint hwnd, int index);
    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static extern nint SetWindowLongPtrW(nint hwnd, int index, nint value);
    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(nint hwnd);
}
