using System.Diagnostics;
using System.Text.Json;

namespace Reflection.Desktop;

// The UI is a CLI client. Configuration validation and input processing stay native.
internal sealed class HostClient : IDisposable
{
    private readonly string executable = Path.Combine(AppContext.BaseDirectory, "reflection-host.exe");
    private readonly string config;
    private Process? ownedHost;
    private readonly CancellationTokenSource lifetime = new();
    private int disposed;
    internal HostClient(string? configurationPath = null)
    {
        if (configurationPath is null)
        {
            var arguments = Environment.GetCommandLineArgs();
            for (var i = 1; i < arguments.Length; ++i)
            {
                if (arguments[i] != "--config") continue;
                if (++i == arguments.Length) throw new ArgumentException("--config requires a path.");
                configurationPath = arguments[i];
            }
        }
        config = Path.GetFullPath(configurationPath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Reflection", "server.conf"));
    }
    public event Action<string>? Log;
    public bool OwnsRunningHost
    {
        get
        {
            try { return ownedHost is { HasExited: false }; }
            catch (InvalidOperationException) { return false; }
        }
    }

    private Process Create(params string[] arguments)
    {
        if (!File.Exists(executable))
            throw new FileNotFoundException("Place reflection-host.exe beside Reflection.Desktop.exe.");
        var info = new ProcessStartInfo(executable)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };
        info.ArgumentList.Add("--config");
        info.ArgumentList.Add(config);
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        return new Process { StartInfo = info };
    }

    public async Task<JsonElement> RunAsync(params string[] arguments)
    {
        lifetime.Token.ThrowIfCancellationRequested();
        using var process = Create(arguments);
        process.Start();
        var output = process.StandardOutput.ReadToEndAsync();
        var errors = process.StandardError.ReadToEndAsync();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
        var driverOperation = arguments.Length > 1 && (arguments[0] == "driver" || arguments[0] == "input" ||
            (arguments[0] == "config" && arguments.Contains("driver")));
        if (!driverOperation) deadline.CancelAfter(TimeSpan.FromSeconds(10));
        try { await process.WaitForExitAsync(deadline.Token); }
        catch (OperationCanceledException)
        {
            if (!process.HasExited) process.Kill();
            lifetime.Token.ThrowIfCancellationRequested();
            throw new TimeoutException("The host command did not respond within 10 seconds.");
        }
        var text = await output;
        var error = await errors;
        lifetime.Token.ThrowIfCancellationRequested();
        if (process.ExitCode != 0)
        {
            if (arguments.Length > 0 && arguments[0] == "doctor" && text.TrimStart().StartsWith('{'))
            {
                using var diagnosis = JsonDocument.Parse(text);
                if (diagnosis.RootElement.TryGetProperty("available", out _))
                    return diagnosis.RootElement.Clone();
            }
            try
            {
                using var failure = JsonDocument.Parse(error);
                error = failure.RootElement.GetProperty("error").GetString() ?? error;
            }
            catch (JsonException) { }
            throw new InvalidOperationException(error.Length > 0 ? error.Trim() : "Host command failed.");
        }
        using var json = JsonDocument.Parse(text);
        return json.RootElement.Clone();
    }

    public async Task InitializeAsync()
    {
        if (!File.Exists(config)) await RunAsync("init");
    }

    public async Task<Dictionary<string, string>> ReadConfigAsync(bool secrets = false)
    {
        var json = await RunAsync(secrets ? ["config", "show", "--secrets"] : ["config", "show"]);
        return json.EnumerateObject().ToDictionary(p => p.Name, p => p.Value.GetString() ?? "");
    }

    public async Task<(Dictionary<string, string> Values, string Revision)> SnapshotAsync()
    {
        var json = await RunAsync("config", "snapshot");
        return (json.GetProperty("values").EnumerateObject()
            .ToDictionary(p => p.Name, p => p.Value.GetString() ?? ""),
            json.GetProperty("revision").GetString()!);
    }

    public Task<JsonElement> ApplyAsync(Dictionary<string, string> values, string revision)
    {
        var arguments = new List<string> { "config", "apply", "--if-current", revision };
        foreach (var (key, value) in values) { arguments.Add(key); arguments.Add(value); }
        return RunAsync(arguments.ToArray());
    }

    public async Task StartAsync(bool dryRun)
    {
        if ((await RunAsync("status")).GetProperty("running").GetBoolean())
            throw new InvalidOperationException("A host already runs with this configuration.");
        lifetime.Token.ThrowIfCancellationRequested();
        ownedHost?.Dispose();
        ownedHost = Create(dryRun ? ["serve", "--dry-run"] : ["serve"]);
        ownedHost.OutputDataReceived += OnOutput;
        ownedHost.ErrorDataReceived += OnOutput;
        ownedHost.Start();
        ownedHost.BeginOutputReadLine();
        ownedHost.BeginErrorReadLine();
        for (var i = 0; i < 30; ++i)
        {
            await Task.Delay(100, lifetime.Token);
            if (ownedHost.HasExited) throw new InvalidOperationException("Host startup failed. See the session log.");
            var status = await RunAsync("status");
            if (status.TryGetProperty("listening", out var listening) && listening.GetBoolean()) return;
        }
        await StopAsync();
        throw new TimeoutException("Host did not begin listening within 3 seconds.");
    }

    private void OnOutput(object sender, DataReceivedEventArgs args)
    {
        if (Volatile.Read(ref disposed) == 0 && args.Data is { Length: > 0 } line) Log?.Invoke(line);
    }

    public async Task StopAsync()
    {
        await RunAsync("stop");
        for (var i = 0; i < 40; ++i)
        {
            if (!(await RunAsync("status")).GetProperty("running").GetBoolean()) return;
            await Task.Delay(100, lifetime.Token);
        }
        throw new TimeoutException("The host is still stopping. Check status before restarting.");
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0) return;
        lifetime.Cancel();
        if (OwnsRunningHost)
        {
            // The stop event may not exist during startup. Retry before releasing the child handle.
            try
            {
                var deadline = Stopwatch.StartNew();
                while (OwnsRunningHost && deadline.Elapsed < TimeSpan.FromSeconds(3))
                {
                    using var stop = Create("stop");
                    stop.Start();
                    if (!stop.WaitForExit(500)) stop.Kill();
                    if (ownedHost!.WaitForExit(100)) break;
                }
            }
            catch (Exception error) { Debug.WriteLine(error.Message); }
            // This process is our still-owned child; a failed startup must never escape window shutdown.
            try
            {
                if (OwnsRunningHost) { ownedHost!.Kill(); ownedHost.WaitForExit(1000); }
            }
            catch (Exception error) { Debug.WriteLine(error.Message); }
        }
        if (ownedHost is not null)
        {
            ownedHost.OutputDataReceived -= OnOutput;
            ownedHost.ErrorDataReceived -= OnOutput;
            ownedHost.Dispose();
        }
    }
}
