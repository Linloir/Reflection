param(
    [ValidateSet("x64", "ARM64")]
    [string]$Architecture = "x64",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [string]$InstallDirectory = "",
    [string]$DriverPackage = "",
    [string]$PublishDirectory = "",
    [string]$SignTool = ""
)
$ErrorActionPreference = "Stop"
$moduleDirectory = Split-Path -Parent $PSScriptRoot
$nativeBuild = Join-Path $moduleDirectory "build/native-$Architecture"
if (-not $PublishDirectory) { $PublishDirectory = Join-Path $moduleDirectory "build/ui" }
$PublishDirectory = [IO.Path]::GetFullPath($PublishDirectory)
$runtime = if ($Architecture -eq "ARM64") { "win-arm64" } else { "win-x64" }

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

if ($DriverPackage) {
    $DriverPackage = (Resolve-Path -LiteralPath $DriverPackage).Path
    & (Join-Path $PSScriptRoot "verify-driver.ps1") -DriverPackage $DriverPackage -Architecture $Architecture -SignTool $SignTool
}
# Refuse to merge package generations or retain a stale driver in an API-only build.
foreach ($destination in @($PublishDirectory, $InstallDirectory) | Where-Object { $_ }) {
    if (Test-Path -LiteralPath (Join-Path $destination "driver")) {
        throw "Destination already contains a driver package: $destination. Choose a fresh publish/install directory."
    }
}

Invoke-Checked "cmake" @("-S", $moduleDirectory, "-B", $nativeBuild, "-A", $Architecture)
Invoke-Checked "cmake" @("--build", $nativeBuild, "--config", $Configuration)
$hostExecutable = Join-Path $nativeBuild "$Configuration/reflection-host.exe"
$project = Join-Path $moduleDirectory "ui/Reflection.Desktop.csproj"
Invoke-Checked "dotnet" @(
    "publish", $project, "-c", $Configuration, "-r", $runtime, "--self-contained", "true",
    "-p:Platform=$Architecture", "-p:ReflectionHostPath=$hostExecutable", "-o", $publishDirectory
)
if ($DriverPackage) {
    $driverDestination = Join-Path $publishDirectory "driver"
    New-Item -ItemType Directory -Force $driverDestination | Out-Null
    foreach ($name in @("ReflectionTablet.inf", "ReflectionTablet.sys", "ReflectionTablet.cat")) {
        Copy-Item -LiteralPath (Join-Path $DriverPackage $name) -Destination $driverDestination -Force
    }
    & (Join-Path $PSScriptRoot "verify-driver.ps1") -DriverPackage $driverDestination -Architecture $Architecture -SignTool $SignTool
} else {
    Write-Output "API Mode build: no driver package bundled. Driver installation is unavailable unless a usable driver is already installed."
}

# XAML compilation alone is not enough: the unpackaged app resolves these at startup.
foreach ($resource in @("App.xbf", "MainWindow.xbf", "Reflection.Desktop.pri")) {
    if (-not (Test-Path -LiteralPath (Join-Path $publishDirectory $resource))) {
        throw "Published application is missing $resource; do not distribute this directory."
    }
}

if ($InstallDirectory) {
    New-Item -ItemType Directory -Force $InstallDirectory | Out-Null
    Copy-Item -Path (Join-Path $publishDirectory "*") -Destination $InstallDirectory -Recurse -Force
    if ($DriverPackage) {
        & (Join-Path $PSScriptRoot "verify-driver.ps1") -DriverPackage (Join-Path $InstallDirectory "driver") -Architecture $Architecture -SignTool $SignTool
    }
    Write-Output "Installed Reflection Desktop to $InstallDirectory"
}
Write-Output "Reflection Desktop: $(Join-Path $publishDirectory 'Reflection.Desktop.exe')"
