param(
    [ValidateSet("x64", "ARM64")][string]$Architecture = "x64",
    [string]$MSBuild = "msbuild",
    [string]$WdkRoot = "",
    [string]$SdkRoot = "${env:ProgramFiles(x86)}/Windows Kits/10",
    [string]$VCToolsRoot = "",
    [string]$SdkVersion = "",
    [string]$TargetOS = ""
)
$ErrorActionPreference = "Stop"
$moduleDirectory = Split-Path -Parent $PSScriptRoot
$sourceDirectory = Join-Path $moduleDirectory "driver"
$outputDirectory = [IO.Path]::GetFullPath((Join-Path $moduleDirectory "build/driver/$Architecture"))
$packageDirectory = Join-Path $outputDirectory "unsigned"
$objectDirectory = Join-Path $moduleDirectory "build/driver-obj/$Architecture"
New-Item -ItemType Directory -Force $packageDirectory, $objectDirectory | Out-Null

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

# An extracted Microsoft.Windows.WDK NuGet package can build without installing
# Visual Studio WDK integration. Pass its c/ directory as WdkRoot.
if ($WdkRoot) {
    $WdkRoot = (Resolve-Path -LiteralPath $WdkRoot).Path
    if (-not $SdkVersion) {
        $SdkVersion = Get-ChildItem -LiteralPath (Join-Path $WdkRoot "Include") -Directory |
            Where-Object { $_.Name -match '^10\.' } | Sort-Object { [version]$_.Name } -Descending |
            Select-Object -First 1 -ExpandProperty Name
    }
    if (-not $VCToolsRoot) {
        $vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
        $installation = & $vswhere -latest -products "*" -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
        if (-not $installation) { throw "Install Visual Studio C++ Build Tools or pass -VCToolsRoot." }
        $VCToolsRoot = Get-ChildItem -LiteralPath (Join-Path $installation "VC/Tools/MSVC") -Directory |
            Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    $target = $Architecture.ToLowerInvariant()
    $compiler = Join-Path $VCToolsRoot "bin/Hostx64/$target/cl.exe"
    $linker = Join-Path $VCToolsRoot "bin/Hostx64/$target/link.exe"
    $includes = @(
        "$VCToolsRoot/include", "$WdkRoot/Include/$SdkVersion/km",
        "$WdkRoot/Include/$SdkVersion/shared", "$WdkRoot/Include/wdf/kmdf/1.27",
        "$SdkRoot/Include/$SdkVersion/shared", "$SdkRoot/Include/$SdkVersion/ucrt"
    )
    $kernelLibraries = "$WdkRoot/Lib/$SdkVersion/km/$target"
    $frameworkLibraries = "$WdkRoot/Lib/wdf/kmdf/$target/1.27"
    foreach ($required in @($compiler, $linker, $kernelLibraries, $frameworkLibraries) + $includes) {
        if (-not (Test-Path -LiteralPath $required)) { throw "Required build tool or include path is missing: $required" }
    }
    $defines = if ($Architecture -eq "ARM64") { @("/D_ARM64_", "/DARM64") } else { @("/D_AMD64_", "/DAMD64") }
    $objectFile = Join-Path $objectDirectory "driver.obj"
    Invoke-Checked $compiler (@(
        "/nologo", "/c", "/W4", "/WX", "/wd4324", "/wd5103", "/kernel", "/std:c11", "/O2",
        "/D_WIN64", "/D_WIN32_WINNT=0x0A00", "/DKMDF_VERSION_MAJOR=1", "/DKMDF_VERSION_MINOR=27",
        "/Fo$objectFile", (Join-Path $sourceDirectory "driver.c")
    ) + $defines + @($includes | ForEach-Object { "/I$_" }))
    Invoke-Checked $linker @(
        "/nologo", "/driver", "/subsystem:native", "/entry:FxDriverEntry", "/nodefaultlib",
        "/dynamicbase", "/nxcompat", "/out:$outputDirectory/ReflectionTablet.sys", $objectFile,
        "/libpath:$kernelLibraries", "/libpath:$frameworkLibraries",
        "ntoskrnl.lib", "hal.lib", "BufferOverflowK.lib", "wdfdriverentry.lib", "wdfldr.lib", "vhfkm.lib"
    )
    Copy-Item -LiteralPath (Join-Path $sourceDirectory "ReflectionTablet.inf") -Destination $outputDirectory -Force
} else {
    $WdkRoot = $SdkRoot
    Invoke-Checked $MSBuild @(
        (Join-Path $sourceDirectory "ReflectionTablet.vcxproj"), "/p:Configuration=Release",
        "/p:Platform=$Architecture", "/p:RunInf2Cat=false", "/m"
    )
    if (-not $SdkVersion) {
        $SdkVersion = Get-ChildItem -LiteralPath (Join-Path $WdkRoot "bin") -Directory |
            Where-Object { $_.Name -match '^10\.' } | Sort-Object { [version]$_.Name } -Descending |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "x86/Inf2Cat.exe") } |
            Select-Object -First 1 -ExpandProperty Name
    }
}

$infverif = @(
    "$WdkRoot/tools/$SdkVersion/x64/infverif.exe", "$WdkRoot/bin/$SdkVersion/x64/infverif.exe",
    "$WdkRoot/tools/x64/infverif.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$inf2cat = Join-Path $WdkRoot "bin/$SdkVersion/x86/Inf2Cat.exe"
if (-not $infverif -or -not (Test-Path -LiteralPath $inf2cat)) {
    throw "InfVerif/Inf2Cat are missing. Install a matching WDK or pass -WdkRoot and -SdkVersion."
}
foreach ($name in @("ReflectionTablet.inf", "ReflectionTablet.sys")) {
    Copy-Item -LiteralPath (Join-Path $outputDirectory $name) -Destination $packageDirectory -Force
}
# Invalidate any previous catalog before a check that might fail.
$catalog = Join-Path $packageDirectory "ReflectionTablet.cat"
if (Test-Path -LiteralPath $catalog) { Remove-Item -LiteralPath $catalog -Force }
Invoke-Checked $infverif @("/w", (Join-Path $packageDirectory "ReflectionTablet.inf"))
if (-not $TargetOS) {
    $suffix = $Architecture.ToUpperInvariant()
    $TargetOS = (@("RS5", "19H1", "VB", "CO", "NI", "GE") | ForEach-Object { "10_${_}_$suffix" }) -join ","
}
Invoke-Checked $inf2cat @("/driver:$packageDirectory", "/os:$TargetOS", "/uselocaltime")
if (-not (Test-Path -LiteralPath $catalog)) { throw "Inf2Cat did not produce ReflectionTablet.cat." }
Write-Output "Unsigned INF/SYS/CAT package: $packageDirectory"
Write-Output "This package cannot enable Driver Mode on a production Windows installation."
Write-Output "Submit it through the Microsoft driver signing process, then pass the returned signed package to build-ui.ps1 -DriverPackage."
