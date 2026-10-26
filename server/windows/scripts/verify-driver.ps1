param(
    [Parameter(Mandatory = $true)][string]$DriverPackage,
    [ValidateSet("x64", "ARM64")][string]$Architecture = "x64",
    [string]$SignTool = ""
)
$ErrorActionPreference = "Stop"
$package = (Resolve-Path -LiteralPath $DriverPackage).Path
foreach ($name in @("ReflectionTablet.inf", "ReflectionTablet.sys", "ReflectionTablet.cat")) {
    if (-not (Test-Path -LiteralPath (Join-Path $package $name) -PathType Leaf)) {
        throw "Driver package is missing $name"
    }
}
if (-not $SignTool) {
    $sdk = "${env:ProgramFiles(x86)}/Windows Kits/10/bin"
    $SignTool = Get-ChildItem -LiteralPath $sdk -Directory |
        Where-Object { $_.Name -match '^10\.' } | Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName "x64/signtool.exe" } |
        Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $SignTool) { throw "Install the Windows SDK signing tools or pass -SignTool." }

$reader = [IO.BinaryReader]::new([IO.File]::OpenRead((Join-Path $package "ReflectionTablet.sys")))
try {
    if ($reader.ReadUInt16() -ne 0x5A4D) { throw "Driver is not a PE image." }
    $reader.BaseStream.Position = 0x3C
    $offset = $reader.ReadUInt32()
    if ($offset -lt 64 -or $offset -gt ($reader.BaseStream.Length - 6)) { throw "Invalid driver PE header." }
    $reader.BaseStream.Position = $offset
    if ($reader.ReadUInt32() -ne 0x00004550) { throw "Invalid driver PE signature." }
    $machine = $reader.ReadUInt16()
    $expected = if ($Architecture -eq "ARM64") { 0xAA64 } else { 0x8664 }
    if ($machine -ne $expected) { throw "Driver architecture does not match $Architecture." }
} finally { $reader.Dispose() }

$catalog = Join-Path $package "ReflectionTablet.cat"
foreach ($name in @("ReflectionTablet.inf", "ReflectionTablet.sys")) {
    & $SignTool verify /kp /v /c $catalog (Join-Path $package $name)
    if ($LASTEXITCODE -ne 0) {
        throw "Driver signing verification failed for $name. Use the complete Microsoft-signed release package, not the unsigned build output."
    }
}
Write-Output "Verified $Architecture driver package: $package"
