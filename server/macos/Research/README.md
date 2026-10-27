# Isolated public HID registration probe

This directory is outside the application targets and CI test suites.
`HIDRegistrationProbe.swift` tests public CoreHID registration only. It never
activates a virtual device, sends input reports, installs a driver or changes
system settings. An optional binary descriptor is compared with a small
vendor-defined control descriptor.

Build and run the control on macOS 15 or later with an SDK containing CoreHID:

```sh
swiftc server/macos/Research/HIDRegistrationProbe.swift -o /tmp/reflection-hid-probe
/tmp/reflection-hid-probe
```

Pass a binary HID descriptor as the first argument to test a candidate:

```sh
/tmp/reflection-hid-probe /path/to/descriptor.bin
```

The recorded candidate came from Apple's September 21, 2026 Accessory Design
Guidelines, section 15.2.1. Its 324 bytes describe an iPadOS accessory trackpad;
the document is not a macOS gesture-compatibility promise. The source PDF and
its descriptor are not bundled here.

`HIDRegistrationProbe-results.json` records two observations from October 27,
2026. The ordinary ad-hoc executable returned nil for both descriptors. Adding
the documented `com.apple.developer.hid.virtual.device` entitlement to a
separate ad-hoc copy passed `codesign --verify --strict`, but AMFI refused to
launch it because that entitlement is restricted. No system security bypass
or provisioning change was attempted.

The optional entitlement experiment can be reproduced on a separate temporary
copy; on the tested secured system it is expected to be killed before entry:

```sh
cp /tmp/reflection-hid-probe /tmp/reflection-hid-probe-entitled
python3 - <<'PY'
import plistlib
from pathlib import Path
Path("/tmp/reflection-hid-probe.entitlements").write_bytes(
    plistlib.dumps({"com.apple.developer.hid.virtual.device": True})
)
PY
codesign --force --sign - --entitlements /tmp/reflection-hid-probe.entitlements /tmp/reflection-hid-probe-entitled
/tmp/reflection-hid-probe-entitled
```

The October 27, 2026 local prerequisite check found zero valid code-signing
identities and zero provisioning profiles in the standard Xcode/MobileDevice
profile directories. This describes the local installation at probe time,
not the developer account's Developer ID availability. It recorded counts
only, without exposing account or profile data.

An existing Developer ID can sign a follow-up publisher once its App ID has the
HID Virtual Device capability and the bundle embeds the matching provisioning
profile. Keep that authorization separate from the current ad-hoc application
build until native gesture behavior is verified.

The next prerequisite is an appropriately provisioned publisher with effective
virtual-HID authorization. Registration success still would not establish
native gestures: the descriptor's matched event service, feature requests,
native AppKit gesture events and system gesture preferences would need separate
validation. See [the feasibility report](../../../docs/native-touchpad-feasibility.md).
