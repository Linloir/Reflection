# Input modes and optional virtual HID providers

API Mode remains the default, including configurations written before the mode
setting existed. Windows uses Synthetic Pointer / Windows Ink; macOS uses Quartz
tablet events and needs Accessibility permission.

Driver Mode sends actual digitizer HID reports through a separate provider:

| Platform | Provider | Installation |
| --- | --- | --- |
| Windows 10 1809+ | Reflection KMDF source driver + Windows Virtual HID Framework | Signed INF/SYS/CAT, administrator approval |
| macOS 12+ | Reflection helper using IOKit's virtual HID device provider | Per-user signed helper app with an Apple-approved virtual HID entitlement |

The macOS provider is a user-space virtual HID helper, not a kernel extension or a
DriverKit system extension. The two platforms share the report descriptor and
10-byte report ABI in `server/shared/tablet_report.h`. Reports contain pen/eraser
contact, range, the primary barrel button, X/Y, 16-bit normalized pressure, and
signed tilt. Rotation, hover distance and the second barrel button are not part
of this ABI. Neither provider implements WinTab.

## Controls

Both native CLIs provide the same commands. The repository wrapper forwards them:

```sh
python reflection server input status
python reflection server driver status
python reflection server driver install
python reflection server input mode driver
python reflection server input mode api
python reflection server driver uninstall
```

Standalone installations use `reflection-host` in place of `python reflection server`.
On macOS, `driver probe` additionally checks actual virtual-device creation.
`driver status` on macOS deliberately reports `probe-required` for an installed
helper; file presence is not proof that Apple's entitlement checks will pass.

The desktop UI has an Input mode section (under Pen on macOS) with a mode picker
and explicit install/uninstall buttons. These controls call the native CLI.
macOS requires saving pending form edits before a lifecycle command.
Windows shows device status and bundled-package status separately. Without a
usable installed driver or a verified package, Driver Mode is disabled; the
installation button requires a verified package and the uninstall button requires
an installed device. Use **Refresh driver status** after changing the package.

- Installing a provider leaves the selected mode unchanged.
- Selecting Driver Mode installs the bundled provider if needed, verifies
  availability, then saves the mode. Installation failure leaves the old mode.
- Switching modes applies to a running host without restarting the listener.
  The old backend releases its pen/buttons; a new stroke must begin before
  contact can continue. Other configuration fields retain their restart semantics.
- Uninstall selects API Mode and waits for the current host to release its driver
  before removal. If removal fails, API Mode remains selected. If a host cannot
  switch, removal does not proceed. macOS checks Accessibility before switching
  a live driver session to Quartz.
- A provider removed outside the host causes other running configurations to
  return to API when they detect its absence. A macOS host still needs
  Accessibility permission for that fallback.
- `input status` includes the configured mode, provider status, and live host
  status. The live `inputMode` reports the backend that actually switched.
- A dry-run host only records input, even if its configured mode is `driver`.

The persistent key is `input.mode=api|driver` on Windows and
`"inputMode": "api"|"driver"` in the macOS JSON configuration. Mapping profiles do
not change input mode. Prefer `input mode` over manually editing these fields:
it checks installation, waits for the live backend and reports switching errors.

## Windows driver build and packaging

The normal host/UI build does not require the WDK and stays usable in API Mode.
**The repository does not include a production-signed Windows driver.** A normal
UI build therefore cannot install a driver or enable Driver Mode on a machine
without an existing usable Reflection driver. Building the kernel source and
generating a catalog do not satisfy the signing prerequisite.
To build the optional provider, install a matching Windows SDK/WDK with Visual
Studio WDK integration and run from Developer PowerShell:

```powershell
./server/windows/scripts/build-driver.ps1 -Architecture x64
# ARM64 is also defined in the driver project.
```

The script builds `ReflectionTablet.vcxproj`, checks the INF with InfVerif, and
generates the architecture-appropriate catalog with Inf2Cat. Its complete
**unsigned** output is `server/windows/build/driver/<architecture>/unsigned/`.
All three files stay in ignored build output. It does not install the package.

Alternatively, an extracted official `Microsoft.Windows.WDK.x64` NuGet package
can build x64 with Visual Studio C++ Build Tools and the matching Windows SDK,
without Visual Studio WDK integration:

```powershell
./server/windows/scripts/build-driver.ps1 -Architecture x64 -WdkRoot PATH/TO/WDK/c
```

`-SdkRoot`, `-SdkVersion` and `-VCToolsRoot` override tool discovery. The extracted
WDK must contain libraries for the target architecture. `-TargetOS` overrides
the Inf2Cat OS list, which defaults to Windows 10 1809 through Windows 11 24H2.
Production distribution needs the Microsoft driver-signing submission process;
use the returned signed files without editing the INF or replacing the SYS.

Stage the signed package as:

```text
reflection-host.exe
driver/
  ReflectionTablet.inf
  ReflectionTablet.sys
  ReflectionTablet.cat
```

```powershell
./server/windows/scripts/verify-driver.ps1 -DriverPackage PATH/TO/SIGNED/PACKAGE
./server/windows/scripts/build-ui.ps1 -DriverPackage PATH/TO/SIGNED/PACKAGE -PublishDirectory server/windows/build/ui-signed
reflection-host driver verify PATH/TO/PACKAGE
```

The publishing scripts check the PE architecture and run `signtool verify /kp /c`
for both the INF and SYS against the supplied catalog, before publishing and
after copying. `-SignTool` can select an SDK SignTool executable. Unsigned,
untrusted, mismatched or modified packages stop publication.

At runtime, `driver status` includes a `package` object with `state`, `detail`
and `directory`. States include `missing`, `incomplete`, `invalid`,
`wrong-architecture`, `untrusted`, `unreadable` and `verified`.
`packageAvailable` means the local architecture, catalog membership and driver
signature checks passed, rather than merely that three files exist. Verification
uses the local Windows trust cache so status does not need a network connection.
Windows still applies installation and kernel-loading policy; `verified` does
not guarantee successful installation or runtime operation. `driver verify`
performs the same preflight on an arbitrary directory without UAC, installation
or configuration changes, and exits nonzero if it fails.

`build-ui.ps1` without `-DriverPackage` explicitly produces an API Mode build.
Publishing/installing into a directory that already contains `driver/` is
rejected: choose a fresh directory to avoid mixing or retaining stale packages.
`python reflection install server` copies an adjacent driver package with the
native executable after verification on Windows, and also refuses to merge an
existing provider bundle. The installer creates the `ROOT\ReflectionTablet` device,
uses the Windows driver store, and removes only devices with that exact hardware
ID. It reports pending-reboot/installation failures. It never enables test
signing, disables Secure Boot or changes Windows signature enforcement.

The kernel device permits interactive users to submit input and allows one
owning host at a time. A file cleanup callback releases pen state when the host
exits or crashes. Privileged install/remove operations run in a separate UAC
process; pairing configuration is never passed to that process.

## macOS helper build and packaging

`build-app.sh` includes `ReflectionDriver.app` beside the bundled host. The
repository's server build also builds this helper bundle. For a standalone Swift
build, run `sh server/macos/scripts/build-driver.sh` after `swift build`.

Local builds are ad hoc signed **without** the restricted entitlement. API Mode
works as before; Driver Mode fails the device-creation probe. Adding an entitlement
to an ad hoc signature is not an approved distribution solution.

For an authorized build:

```sh
export REFLECTION_DRIVER_SIGN_IDENTITY='Developer ID Application: ...'
export REFLECTION_DRIVER_PROFILE='/absolute/path/to/approved.provisionprofile'
sh server/macos/scripts/build-driver.sh
```

The profile must authorize `io.github.linloir.reflection.driver` and
`com.apple.developer.hid.virtual.device`. The helper's signing entitlements are
in `server/macos/driver/ReflectionDriver.entitlements`. Distribution of the
containing application also requires appropriate signing and notarization.

Installation probes device creation before copying the helper bundle into
`~/Library/Application Support/Reflection/Driver/`. The host starts it on private
pipes, validates its ABI handshake and waits for report acknowledgements. No
network listener, root daemon or kernel extension is installed. Closing the
host's input pipe releases the helper/device. A per-user lock prevents two hosts
from creating competing virtual tablets.

## Compatibility and validation boundary

HID reports normalize mapped desktop coordinates to the virtual desktop. The OS
then decides how the virtual tablet is associated with displays. This is not
equivalent to Synthetic Pointer or Quartz desktop positioning: multi-monitor
mapping, rotation, pressure quantization, eraser and application behavior need
real-machine testing. Use API Mode when precise application-controlled desktop
mapping is needed. A 16-bit pressure report does not guarantee 65,536 distinguishable
pressure values in Windows Ink or any application.

The vendor/product identifiers in the source are development identifiers. Obtain
assigned identifiers before public hardware-style driver distribution.

Automated checks cover legacy defaults, configuration validation, the shared
report vector and releases, missing/incomplete/untrusted/mismatched package
failures, UI action availability, live dry-run switching and
idempotent uninstall when no provider is present. `tools/check_input_modes.py`
does not install a driver or inject system input. Kernel loading, signed helper
entitlement acceptance, application compatibility and multi-monitor mapping
still require dedicated Windows/macOS machines and signed release artifacts.

Primary references:

- Microsoft: https://learn.microsoft.com/windows-hardware/drivers/hid/virtual-hid-framework--vhf-
- Microsoft: https://learn.microsoft.com/windows-hardware/design/component-guidelines/required-hid-top-level-collections
- Apple virtual HID entitlement checks: https://github.com/apple-oss-distributions/IOHIDFamily/blob/main/IOHIDFamily/IOHIDResourceUserClient.cpp
