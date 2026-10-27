[CmdletBinding()]
param(
    [string]$SourceDirectory = "",
    [string]$BuildDirectory = "",
    [string]$OutputDirectory = "",
    [ValidateSet("Auto", "x64", "ARM64")]
    [string]$Architecture = "Auto",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [ValidateRange(1, 16)]
    [int]$BuildJobs = 2,
    [switch]$IncludeWinUI,
    [switch]$ProbeNativeDevice
)

$ErrorActionPreference = "Stop"
$runId = "ReflectionValidation-" + (Get-Date -Format "yyyyMMddTHHmmss") + "-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) $runId
if (-not $SourceDirectory) { $SourceDirectory = Join-Path $PSScriptRoot "../../.." }
$SourceDirectory = [IO.Path]::GetFullPath($SourceDirectory)
if (-not $BuildDirectory) { $BuildDirectory = Join-Path $temporaryRoot "build" }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $temporaryRoot "results" }
$BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$steps = New-Object "System.Collections.Generic.List[object]"
$report = [ordered]@{
    schema = 1
    runId = $runId
    startedAt = [DateTime]::UtcNow.ToString("o")
    sourceDirectory = $SourceDirectory
    buildDirectory = $BuildDirectory
    outputDirectory = $OutputDirectory
    status = "unavailable"
    configuration = $Configuration
    nativeContactsInjected = $false
    nativeGesturesVerified = $false
    deviceProbeRequested = [bool]$ProbeNativeDevice
    winUIRequested = [bool]$IncludeWinUI
    steps = $steps
}
$savedEnvironment = @{}
$exitCode = 2
$outputReady = $false

function Add-Result {
    param([string]$Name, [string]$Status, [string]$Reason, [bool]$Required = $true)
    $step = [ordered]@{ name = $Name; status = $Status; reason = $Reason; required = $Required }
    $steps.Add($step)
    return $step
}

function Quote-NativeArgument {
    param([string]$Value)
    $builder = New-Object Text.StringBuilder
    [void]$builder.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq [char]92) { $slashes++; continue }
        if ($character -eq [char]34) {
            [void]$builder.Append(('\' * ($slashes * 2 + 1)))
        } else {
            [void]$builder.Append(('\' * $slashes))
        }
        $slashes = 0
        [void]$builder.Append($character)
    }
    [void]$builder.Append(('\' * ($slashes * 2)))
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-Step {
    param(
        [string]$Name, [string]$Program, [string[]]$Arguments,
        [int]$TimeoutSeconds = 300, [bool]$Required = $true
    )
    $stdout = Join-Path $OutputDirectory ($Name + ".stdout.log")
    $stderr = Join-Path $OutputDirectory ($Name + ".stderr.log")
    $step = Add-Result $Name "fail" "" $Required
    $step.stdout = $stdout
    $step.stderr = $stderr
    $step.startedAt = [DateTime]::UtcNow.ToString("o")
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $process = $null
    try {
        $argumentString = (($Arguments | ForEach-Object { Quote-NativeArgument $_ }) -join " ")
        $start = @{
            FilePath = $Program; WorkingDirectory = $BuildDirectory; NoNewWindow = $true
            PassThru = $true; RedirectStandardOutput = $stdout; RedirectStandardError = $stderr
        }
        if (@($Arguments).Count -gt 0) { $start.ArgumentList = $argumentString }
        $process = Start-Process @start
        # Keep the process handle open so Windows PowerShell retains its exit code.
        $null = $process.Handle
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            # Only the process tree created by this step is terminated.
            & "$env:SystemRoot/System32/taskkill.exe" /PID $process.Id /T /F 2>&1 | Out-Null
            $process.WaitForExit()
            $step.reason = "Timed out after $TimeoutSeconds seconds"
        } else {
            $process.WaitForExit()
            $step.exitCode = $process.ExitCode
            $step.status = if ($process.ExitCode -eq 0) { "pass" } else { "fail" }
            if ($process.ExitCode -ne 0) { $step.reason = "Process exit code $($process.ExitCode); inspect the step logs" }
        }
    } catch {
        $step.reason = $_.Exception.Message
    } finally {
        $watch.Stop()
        $step.durationSeconds = [Math]::Round($watch.Elapsed.TotalSeconds, 3)
        if ($process) { $process.Dispose() }
    }
    Write-Host ($Name + ": " + $step.status)
    return $step
}

function Find-Tool {
    param([string]$Name)
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command -and $command.Source -notlike "*\Microsoft\WindowsApps\*") { return $command.Source }
    return $null
}

function Set-RunEnvironment {
    param([string]$Name, [string]$Value)
    $savedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Find-Binary {
    param([string]$Name)
    foreach ($candidate in @(
        (Join-Path $BuildDirectory "native/$Configuration/$Name.exe"),
        (Join-Path $BuildDirectory "native/$Name.exe")
    )) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return $null
}

try {
    if ($BuildDirectory -eq $SourceDirectory -or $OutputDirectory -eq $SourceDirectory -or $BuildDirectory -eq $OutputDirectory) {
        throw "Source, build and output directories must be distinct."
    }
    foreach ($directory in @($BuildDirectory, $OutputDirectory)) {
        if ((Test-Path -LiteralPath $directory) -and
            @(Get-ChildItem -LiteralPath $directory -Force | Select-Object -First 1).Count -gt 0) {
            throw "Refusing to reuse nonempty directory: $directory"
        }
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        if ($directory -eq $OutputDirectory) { $outputReady = $true }
    }
    $report.os = [ordered]@{ description = [Environment]::OSVersion.VersionString; platform = [Environment]::OSVersion.Platform.ToString() }
    $report.powerShell = $PSVersionTable.PSVersion.ToString()
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        $null = Add-Result "windows_environment" "unavailable" "This runner requires Windows; it does not cross-compile."
    } elseif (-not (Test-Path -LiteralPath (Join-Path $SourceDirectory "server/windows/CMakeLists.txt"))) {
        throw "SourceDirectory must point to the Reflection repository root."
    } else {
        $os = Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion" `
            -Name ProductName, DisplayVersion, CurrentBuildNumber, UBR -ErrorAction SilentlyContinue
        if ($os) {
            $report.os.product = $os.ProductName
            $report.os.displayVersion = $os.DisplayVersion
            $report.os.build = "$($os.CurrentBuildNumber).$($os.UBR)"
        }
        try {
            $report.os.architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
            $report.processArchitecture = [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture.ToString()
        } catch {
            $report.os.architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
            $report.processArchitecture = $env:PROCESSOR_ARCHITECTURE
        }
        if ($Architecture -eq "Auto") {
            $Architecture = if ($report.os.architecture -match "ARM64") { "ARM64" } else { "x64" }
        }
        $report.architecture = $Architecture
        $scratch = Join-Path $BuildDirectory "scratch"
        New-Item -ItemType Directory -Path $scratch -Force | Out-Null
        Set-RunEnvironment "TEMP" $scratch
        Set-RunEnvironment "TMP" $scratch
        Set-RunEnvironment "REFLECTION_TEST_ISOLATED" "1"
        Set-RunEnvironment "PYTHONDONTWRITEBYTECODE" "1"
        Set-RunEnvironment "DOTNET_CLI_HOME" (Join-Path $BuildDirectory "dotnet-home")
        Set-RunEnvironment "NUGET_PACKAGES" (Join-Path $BuildDirectory "nuget-packages")
        Set-RunEnvironment "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" "1"
        Set-RunEnvironment "DOTNET_CLI_TELEMETRY_OPTOUT" "1"
        $report.sourceHashes = [ordered]@{}
        $snapshot = Join-Path $BuildDirectory "source"
        $report.sourceSnapshotDirectory = $snapshot
        $sourceFiles = @()
        foreach ($part in @("src", "include", "ui", "tests", "scripts")) {
            $sourceFiles += Get-ChildItem -LiteralPath (Join-Path $SourceDirectory "server/windows/$part") -File -Recurse |
                Where-Object {
                    $_.Extension -in @(".cpp", ".hpp", ".h", ".cs", ".csproj", ".xaml", ".manifest", ".ps1", ".props", ".targets", ".resx", ".ico", ".png") -and
                    $_.FullName -notmatch '[\\/](build|bin|obj|\.vs)[\\/]'
                }
        }
        $sourceFiles += Get-Item -LiteralPath (Join-Path $SourceDirectory "server/windows/CMakeLists.txt")
        $sourceFiles += Get-ChildItem -LiteralPath (Join-Path $SourceDirectory "tools") -Filter "*.py" -File
        $sourceFiles += Get-Item -LiteralPath (Join-Path $SourceDirectory "protocol/vectors.json")
        foreach ($file in $sourceFiles) {
            $relative = $file.FullName.Substring($SourceDirectory.Length).TrimStart('\', '/')
            $destination = Join-Path $snapshot $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $destination
            $report.sourceHashes[$relative] = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        }

        $cmake = Find-Tool "cmake.exe"
        $ctest = Find-Tool "ctest.exe"
        if (-not $ctest -and $cmake) {
            $candidate = Join-Path (Split-Path -Parent $cmake) "ctest.exe"
            if (Test-Path -LiteralPath $candidate) { $ctest = $candidate }
        }
        $dotnet = Find-Tool "dotnet.exe"
        $python = Find-Tool "python.exe"
        $pythonPrefix = @()
        if (-not $python) { $python = Find-Tool "py.exe"; $pythonPrefix = @("-3") }
        $report.tools = [ordered]@{ cmake = $cmake; ctest = $ctest; dotnet = $dotnet; python = $python }
        foreach ($entry in @(@("cmake_version", $cmake), @("ctest_version", $ctest), @("dotnet_version", $dotnet))) {
            if ($entry[1]) { $null = Invoke-Step $entry[0] $entry[1] @("--version") 30 $false }
        }
        if ($python) { $null = Invoke-Step "python_version" $python ($pythonPrefix + @("--version")) 30 $false }
        $nativeSource = Join-Path $snapshot "server/windows"
        $nativeBuild = Join-Path $BuildDirectory "native"
        $built = $false
        if ($cmake) {
            $configured = Invoke-Step "native_configure" $cmake @("-S", $nativeSource, "-B", $nativeBuild, "-A", $Architecture, "-DBUILD_TESTING=ON") 300
            if ($configured.status -eq "pass") {
                $build = Invoke-Step "native_build" $cmake @("--build", $nativeBuild, "--config", $Configuration, "--parallel", "$BuildJobs") 1800
                $built = $build.status -eq "pass"
            } else { $null = Add-Result "native_build" "skipped" "Native configure did not pass" }
        } else {
            $null = Add-Result "native_configure" "unavailable" "CMake is not installed or not on PATH"
            $null = Add-Result "native_build" "skipped" "CMake is unavailable"
        }
        $hostExecutable = if ($built) { Find-Binary "reflection-host" } else { $null }
        $probeExecutable = if ($built) { Find-Binary "reflection_native_touchpad_tests" } else { $null }
        if ($built -and -not $hostExecutable) { $null = Add-Result "native_artifacts" "fail" "Native build produced no reflection-host.exe" }
        if ($built -and $ctest) {
            $null = Invoke-Step "native_ctest" $ctest @("--test-dir", $nativeBuild, "-C", $Configuration, "--output-on-failure") 600
        } else {
            $null = Add-Result "native_ctest" $(if ($built) { "unavailable" } else { "skipped" }) "Requires a successful native build and CTest"
        }
        if ($hostExecutable) {
            $report.hostSha256 = (Get-FileHash -LiteralPath $hostExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
            $null = Invoke-Step "host_doctor" $hostExecutable @("doctor") 30
        } else { $null = Add-Result "host_doctor" "skipped" "Native host is unavailable" }
        if (-not $ProbeNativeDevice) {
            $null = Add-Result "native_device_probe" "skipped" "Not requested; use -ProbeNativeDevice to create and release a device without contacts" $false
        } elseif ($probeExecutable) {
            $probe = Invoke-Step "native_device_probe" $probeExecutable @("--probe-device") 30
            if ($probe.exitCode -eq 3) { $probe.status = "unavailable"; $probe.reason = "Public native touchpad API is not present" }
            if (Test-Path -LiteralPath $probe.stdout) {
                try { $report.nativeDeviceProbe = Get-Content -LiteralPath $probe.stdout -Raw | ConvertFrom-Json }
                catch { $probe.status = "fail"; $probe.reason = "Device probe returned invalid JSON" }
            }
        } else { $null = Add-Result "native_device_probe" "skipped" "Native probe executable is unavailable" }
        foreach ($check in @("check_host", "check_controls", "check_mapping", "check_stability")) {
            if ($hostExecutable -and $python) {
                $arguments = $pythonPrefix + @((Join-Path $snapshot "tools/$check.py"), "--server", $hostExecutable, "--platform", "windows")
                $null = Invoke-Step $check $python $arguments 180
            } else {
                $null = Add-Result $check $(if ($hostExecutable) { "unavailable" } else { "skipped" }) "Requires the native host and Python 3"
            }
        }
        if (-not $IncludeWinUI) {
            $null = Add-Result "winui_publish" "skipped" "Not requested; use -IncludeWinUI" $false
            $null = Add-Result "desktop_lifecycle" "skipped" "Not requested; use -IncludeWinUI" $false
        } elseif (-not $dotnet -or -not $hostExecutable) {
            $null = Add-Result "winui_publish" $(if ($hostExecutable) { "unavailable" } else { "skipped" }) "Requires the native host and .NET 10 SDK"
            $null = Add-Result "desktop_lifecycle" "skipped" "WinUI prerequisites are unavailable"
        } else {
            $runtime = if ($Architecture -eq "ARM64") { "win-arm64" } else { "win-x64" }
            $uiOutput = Join-Path $BuildDirectory "ui-publish"
            $uiIntermediate = (Join-Path $BuildDirectory "ui-obj") + [IO.Path]::DirectorySeparatorChar
            $uiBase = (Join-Path $BuildDirectory "ui-bin") + [IO.Path]::DirectorySeparatorChar
            $published = Invoke-Step "winui_publish" $dotnet @(
                "publish", (Join-Path $nativeSource "ui/Reflection.Desktop.csproj"),
                "-c", $Configuration, "-r", $runtime, "--self-contained", "true",
                "-p:Platform=$Architecture", "-p:ReflectionHostPath=$hostExecutable",
                "-p:BaseIntermediateOutputPath=$uiIntermediate", "-p:MSBuildProjectExtensionsPath=$uiIntermediate",
                "-p:BaseOutputPath=$uiBase", "-o", $uiOutput
            ) 1800
            if ($published.status -eq "pass") {
                $missing = @("App.xbf", "MainWindow.xbf", "Reflection.Desktop.pri") |
                    Where-Object { -not (Test-Path -LiteralPath (Join-Path $uiOutput $_)) }
                if ($missing) { $published.status = "fail"; $published.reason = "Missing published resources: " + ($missing -join ", ") }
            }
            $desktopOutput = Join-Path $BuildDirectory "desktop-tests"
            $desktopIntermediate = (Join-Path $BuildDirectory "desktop-obj") + [IO.Path]::DirectorySeparatorChar
            $desktop = Invoke-Step "desktop_build" $dotnet @(
                "build", (Join-Path $nativeSource "tests/desktop/Reflection.Desktop.Tests.csproj"),
                "-c", $Configuration, "-r", $runtime, "-o", $desktopOutput,
                "-p:BaseIntermediateOutputPath=$desktopIntermediate", "-p:MSBuildProjectExtensionsPath=$desktopIntermediate"
            ) 900
            if ($desktop.status -eq "pass") {
                $null = Invoke-Step "desktop_lifecycle" (Join-Path $desktopOutput "Reflection.Desktop.Tests.exe") @() 120
            } else { $null = Add-Result "desktop_lifecycle" "skipped" "Desktop test build did not pass" }
        }
    }
} catch {
    $null = Add-Result "runner" "fail" $_.Exception.Message
} finally {
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
    if (@($steps | Where-Object { $_.required -and $_.status -eq "fail" }).Count) {
        $report.status = "fail"; $exitCode = 1
    } elseif (@($steps | Where-Object { $_.required -and $_.status -in @("unavailable", "skipped") }).Count) {
        $report.status = "unavailable"; $exitCode = 2
    } else { $report.status = "pass"; $exitCode = 0 }
    $report.finishedAt = [DateTime]::UtcNow.ToString("o")
    if (-not $outputReady) {
        $OutputDirectory = Join-Path $temporaryRoot "failure-report"
        $report.outputDirectory = $OutputDirectory
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    }
    $reportPath = Join-Path $OutputDirectory "validation.json"
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host "Result: $($report.status)"
    Write-Host "Report: $reportPath"
}
exit $exitCode
