param(
    [ValidateSet("x64", "ARM64")]
    [string]$Architecture = "x64",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [string]$InstallDirectory = ""
)
$ErrorActionPreference = "Stop"
$moduleDirectory = Split-Path -Parent $PSScriptRoot
$nativeBuild = Join-Path $moduleDirectory "build/native-$Architecture"
$publishDirectory = Join-Path $moduleDirectory "build/ui"
$runtime = if ($Architecture -eq "ARM64") { "win-arm64" } else { "win-x64" }

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

Invoke-Checked "cmake" @("-S", $moduleDirectory, "-B", $nativeBuild, "-A", $Architecture)
Invoke-Checked "cmake" @("--build", $nativeBuild, "--config", $Configuration)
$hostExecutable = Join-Path $nativeBuild "$Configuration/reflection-host.exe"
$project = Join-Path $moduleDirectory "ui/Reflection.Desktop.csproj"
Invoke-Checked "dotnet" @(
    "publish", $project, "-c", $Configuration, "-r", $runtime, "--self-contained", "true",
    "-p:Platform=$Architecture", "-p:ReflectionHostPath=$hostExecutable", "-o", $publishDirectory
)

if ($InstallDirectory) {
    New-Item -ItemType Directory -Force $InstallDirectory | Out-Null
    Copy-Item -Path (Join-Path $publishDirectory "*") -Destination $InstallDirectory -Recurse -Force
    Write-Output "Installed Reflection Desktop to $InstallDirectory"
}
Write-Output "Reflection Desktop: $(Join-Path $publishDirectory 'Reflection.Desktop.exe')"
