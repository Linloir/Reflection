using Microsoft.UI.Xaml;

namespace Reflection.Desktop;

public partial class App : Application
{
    private Window? window;
    public App()
    {
        UnhandledException += (_, args) => LogFailure(args.Exception);
        InitializeComponent();
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            window = new MainWindow();
            window.Activate();
        }
        catch (Exception error)
        {
            LogFailure(error);
            throw;
        }
    }

    private static void LogFailure(Exception error)
    {
        try
        {
            var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Reflection");
            Directory.CreateDirectory(directory);
            var details = string.Join("\n", error.Data.Keys.Cast<object>().Select(key => $"{key}: {error.Data[key]}"));
            File.AppendAllText(Path.Combine(directory, "desktop-error.log"), $"{DateTimeOffset.Now:O}\n{error}\nHRESULT: {error.HResult:X8}\n{details}\n");
        }
        catch { /* Preserve the original startup failure if logging is unavailable. */ }
    }
}
