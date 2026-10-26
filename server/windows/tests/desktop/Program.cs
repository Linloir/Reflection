using System.Diagnostics;
using System.Text.Json;
using Reflection.Desktop;

internal static class Program
{
    private static string Root => Environment.GetEnvironmentVariable("REFLECTION_DESKTOP_TEST_ROOT")!;
    private static string FileInRoot(string name) => Path.Combine(Root, name);
    private static int checks;

    private static void Require(bool condition, string name)
    {
        ++checks;
        if (!condition) throw new InvalidOperationException(name);
    }

    private static bool FakeHostAlive()
    {
        if (!File.Exists(FileInRoot("host.pid"))) return false;
        try { return !Process.GetProcessById(int.Parse(File.ReadAllText(FileInRoot("host.pid")))).HasExited; }
        catch (ArgumentException) { return false; }
    }

    private static async Task FakeHostAsync(string[] args)
    {
        if (args.Length < 3 || args[0] != "--config") throw new InvalidOperationException("configuration prefix missing");
        File.WriteAllText(FileInRoot("received.config"), args[1]);
        args = args[2..];
        switch (args[0])
        {
            case "status":
                File.WriteAllText(FileInRoot("status.started"), "");
                if (Environment.GetEnvironmentVariable("REFLECTION_DESKTOP_TEST_MODE") == "before-start")
                    await Task.Delay(1000);
                var alive = FakeHostAlive();
                Console.WriteLine(JsonSerializer.Serialize(new { running = alive, listening = alive }));
                break;
            case "serve":
                File.WriteAllText(FileInRoot("serve.started"), "");
                // Reproduce the real interval before the named stop event becomes available.
                await Task.Delay(800);
                File.WriteAllText(FileInRoot("host.pid"), Environment.ProcessId.ToString());
                Console.WriteLine("{\"event\":\"listening\"}");
                await Task.Delay(Timeout.Infinite);
                break;
            case "stop":
                var stopped = FakeHostAlive();
                if (stopped)
                {
                    using var process = Process.GetProcessById(int.Parse(File.ReadAllText(FileInRoot("host.pid"))));
                    process.Kill();
                    process.WaitForExit(2000);
                }
                Console.WriteLine(JsonSerializer.Serialize(new { stopRequested = stopped }));
                break;
            default:
                throw new InvalidOperationException("unexpected fake CLI command");
        }
    }

    private static async Task WaitForFileAsync(string name)
    {
        for (var i = 0; i < 200; ++i)
        {
            if (File.Exists(FileInRoot(name))) return;
            await Task.Delay(20);
        }
        throw new TimeoutException($"Fake host never created {name}");
    }

    private static async Task ExpectCanceledAsync(Task task)
    {
        try { await task; throw new InvalidOperationException("expected canceled operation"); }
        catch (OperationCanceledException) { ++checks; }
    }

    private static void NewScenario(string parent, string mode)
    {
        var path = Path.Combine(parent, mode);
        Directory.CreateDirectory(path);
        Environment.SetEnvironmentVariable("REFLECTION_DESKTOP_TEST_ROOT", path);
        Environment.SetEnvironmentVariable("REFLECTION_DESKTOP_TEST_MODE", mode);
    }

    public static async Task<int> Main(string[] args)
    {
        if (args.Length > 0) { await FakeHostAsync(args); return 0; }
        var temporary = Path.Combine(Path.GetTempPath(), $"reflection-desktop-{Guid.NewGuid():N}");
        Directory.CreateDirectory(temporary);
        try
        {
            CheckPressureCurves();
            var appHost = Path.Combine(AppContext.BaseDirectory,
                "Reflection.Desktop.Tests" + (OperatingSystem.IsWindows() ? ".exe" : ""));
            var fakeExecutable = Path.Combine(AppContext.BaseDirectory, "reflection-host.exe");
            File.Copy(appHost, fakeExecutable, true);
            if (!OperatingSystem.IsWindows())
                File.SetUnixFileMode(fakeExecutable, File.GetUnixFileMode(appHost));

            NewScenario(temporary, "before-start");
            using (var client = new HostClient())
            {
                var start = client.StartAsync(false);
                await WaitForFileAsync("status.started");
                client.Dispose();
                await ExpectCanceledAsync(start);
                Require(!File.Exists(FileInRoot("serve.started")), "disposing during initial status must prevent startup");
                await ExpectCanceledAsync(client.RunAsync("status"));
            }

            NewScenario(temporary, "during-start");
            using (var client = new HostClient())
            {
                var start = client.StartAsync(false);
                await WaitForFileAsync("serve.started");
                client.Dispose();
                await ExpectCanceledAsync(start);
                Require(!FakeHostAlive(), "disposing before stop availability must not leave a host alive");
            }

            NewScenario(temporary, "normal");
            var customConfiguration = Path.Combine(temporary, "custom configuration", "server.conf");
            using (var client = new HostClient(customConfiguration))
            {
                await client.StartAsync(true);
                Require(FakeHostAlive(), "a normal host reaches listening state");
                Require(File.ReadAllText(FileInRoot("received.config")) == Path.GetFullPath(customConfiguration),
                    "custom configuration path with spaces reaches the CLI unchanged");
                await client.StopAsync();
                Require(!FakeHostAlive(), "explicit stop releases the running child");
            }
            Console.WriteLine($"{checks} desktop lifecycle and pressure checks passed");
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
        finally
        {
            if (Environment.GetEnvironmentVariable("REFLECTION_DESKTOP_TEST_ROOT") is not null && FakeHostAlive())
            {
                using var child = Process.GetProcessById(int.Parse(File.ReadAllText(FileInRoot("host.pid"))));
                child.Kill();
                child.WaitForExit(2000);
            }
            Directory.Delete(temporary, true);
        }
    }

    private static void CheckPressureCurves()
    {
        var points = PressureCurve.Parse("0,0;0.25,0.1;0.75,0.9;1,1");
        foreach (var point in points)
            Require(Math.Abs(PressureCurve.Evaluate(points, point.X, true) - point.Y) < 1e-12, "smooth curve passes through control points");
        Require(Math.Abs(PressureCurve.Evaluate(points, .125, false) - .05) < 1e-12, "linear mode retains straight segments");
        Require(PressureCurve.Evaluate(points, .125, true) < .04, "smooth mode differs from linear at a curved segment");
        var random = new Random(20261026);
        for (var trial = 0; trial < 40; ++trial)
        {
            var xs = Enumerable.Range(0, 8).Select(_ => random.NextDouble()).Order().ToArray();
            var ys = Enumerable.Range(0, 8).Select(_ => random.NextDouble()).Order().ToArray();
            var curve = new List<CurveKnot> { new(0, 0) };
            curve.AddRange(xs.Select((x, i) => new CurveKnot(x, ys[i])));
            curve.Add(new(1, 1));
            var previous = 0.0;
            var valid = true;
            for (var i = 0; i <= 1000; ++i)
            {
                var y = PressureCurve.Evaluate(curve, i / 1000.0, true);
                valid &= double.IsFinite(y) && y >= previous - 1e-12 && y >= 0 && y <= 1;
                previous = y;
            }
            Require(valid, "unevenly spaced monotone curves remain monotone and bounded");
        }
        var plateau = PressureCurve.Parse("0,0;0.2,0.4;0.8,0.4;1,1");
        Require(PressureCurve.Evaluate(plateau, .5, true) == .4, "flat pressure interval stays flat");
        foreach (var invalid in new[] { "0,0;0.5,NaN;1,1", "0,0;0.5,0.7;0.5,0.8;1,1", "0,0;0.2,0.8;0.7,0.3;1,1" })
        {
            var rejected = false;
            try { PressureCurve.Parse(invalid); }
            catch (FormatException) { rejected = true; }
            Require(rejected, "invalid or decreasing curve is rejected");
        }
    }
}
