# Native touchpad feasibility

Verified against Microsoft and Apple public documentation on 2026-10-27.
The optional Windows native contact backend is implemented; its actual native
gesture behavior has not yet been validated on Windows hardware.

## Current boundary

Reflection supports Windows 10 1809+ and macOS 12+. Its compatibility protocol
sends interpreted mouse, scroll, zoom and swipe operations. A separate negotiated
protocol now sends physical dimensions and raw contact IDs, normalized positions,
confidence and down/update/up/cancel state. The Windows native backend lets the
system recognize gestures from these contacts; it does not send precomputed
rotation or magnification events. macOS retains the compatibility backend.

## Windows

Microsoft now documents a public Windows 11 touchpad injection path:
`CreateSyntheticPointerDevice2(const SYNTHETIC_DEVICE_CREATION_PARAMS*)`.
The linked documentation was updated on 2026-07-24. The required configuration is:

- `pointerType = PT_TOUCHPAD`, with one to five simultaneous contacts.
- `feedbackMode = POINTER_FEEDBACK_NONE` and `hMonitor = NULL`.
- `options` must contain `SDCO_PHYSICAL_SIZE`; device width and height are
  nonzero HIMETRIC units (1/100 mm).
- With physical sizing, `InjectSyntheticPointerInput` takes device-relative
  `ptHimetricLocation` coordinates rather than display pixel coordinates.
- Omitting `SDCO_TOUCHPAD_GESTURE_ONLY` allows Windows to interpret the input
  like physical touchpad input, including mouse motion and clicks.

`InjectTouchpadAction` does not accept pinch or rotation deltas. Its public
enumeration contains three-, four- and five-finger tap/press/release actions,
plus inertia-stop/end actions. Native pinch and rotation therefore require
contact streams for the system recognizer, rather than changing the current
shortcut to a direct "magnify" function.

This is an implemented optional Windows 11 backend, not a replacement for the
Windows 10 baseline. The documentation states Windows 11 without a concrete
minimum build; resolve exports dynamically and retain the compatibility backend
when unavailable. `CreateSyntheticPointerDevice` (without the `2`) explicitly
supports only `PT_TOUCH` and `PT_PEN`; a synthetic touchscreen is not a substitute
for a native touchpad.

The host dynamically resolves the public export and creates the actual device
before acknowledging native capability. It uses only `SDCO_PHYSICAL_SIZE`,
so Windows can also recognize taps and cursor motion. The client selects one
backend per gesture and never duplicates native contacts as compatibility
commands. Contact-state validation, replay protection and cancellation are
separate from OS injection. Disconnect and pen proximity release contacts;
aborted contact clears confidence and destroys the injection device.

No Windows runtime is available in the current validation environment.
Cross-compilation can validate C++ and linkage, but cannot establish recognition,
system gesture preferences, contact cancellation or behavior in target apps.
The backend is guarded by runtime API availability and successful creation.
The `touchpad.native` configuration can force the compatibility backend.
Dry-run never creates the native device or acknowledges its capability.
These safeguards do not substitute for real native gesture verification.

## macOS

The public Quartz event constructors support mouse, keyboard and scrolling.
AppKit receives magnification and rotation through `NSEvent`, but those fields
are read-only. `otherEvent(with:...)` is not a general gesture factory: the
documented accepted event types exclude magnify and rotate. No documented
public constructor/posting path for arbitrary cross-application magnify/rotate
events was found for Reflection's macOS 12 baseline. Changing undocumented
event field numbers, serialized event data or private selectors is not an
acceptable implementation.

CoreHID and `HIDVirtualDevice` are available from macOS 15, not macOS 26.
They publicly support virtual devices with HID report descriptors. Apple
publishes a complete five-contact Touch Pad descriptor in the September 21,
2026 Accessory Design Guidelines, chapter 15, pages 107-115. That chapter
explicitly covers iPadOS 14.5 and later; it is a useful public test candidate,
not a macOS native-gesture compatibility guarantee. CoreHID cannot cover
Reflection's macOS 12-14 baseline.

The Apple DTS HID Entitlement Configuration Guide, published in August 2026
and revised in September, corrects older documentation: a CoreHID publisher
uses `com.apple.developer.hid.virtual.device`. The similarly named
`com.apple.developer.driverkit.family.hid.virtual.device` is defunct and does
not enable publishing. A DEXT additionally needs its DriverKit family
entitlements, signing and activation; DTS explicitly says it uses the same
publishing architecture and is not a shortcut around CoreHID requirements.

### Descriptor and registration follow-up, October 27, 2026

The official accessory example uses Digitizer usage page `0x0d`, Touch Pad
usage `0x05`, five Finger collections, stable contact indices, Tip Switch,
Confidence, physical dimensions and a Surface Switch feature report. It
reports actual contacts rather than precomputed magnification or rotation.
The published descriptor is 324 bytes; the example input report is 24 bytes.
The PDF's iPadOS applicability and descriptor opening were also visually
checked, rather than inferred from search snippets.

Apple's open-source `IOHIDEventDriver.cpp`, tree
`777ccd9698845aadf711e32d843c8c9b777431d9`, recognizes standard Touch Pad
collections and enables digitizer collection dispatch in
`setDigitizerProperties`. Therefore the absence of a generic Touch Pad entry
in `AppleMultitouchDriver.kext` alone does not prove that macOS cannot process
the descriptor. Neither source establishes the later connection to AppKit
magnify/rotate events, Mission Control or system trackpad preferences.

An isolated registration probe was compiled against the installed public
CoreHID SDK and run on macOS 27.0.1, build 26A434. It compares a minimal
vendor-defined control descriptor with an optional candidate, without
activating the device or sending reports:

```sh
swiftc server/macos/Research/HIDRegistrationProbe.swift -o /tmp/reflection-hid-probe
/tmp/reflection-hid-probe /path/to/descriptor.bin
```

With the official accessory descriptor, the current ad-hoc executable,
which has no virtual-HID entitlement, produced:

```json
{"activated":false,"candidateBytes":324,"candidateRegistered":false,"controlRegistered":false,"inputReportsSent":0,"os":"Version 27.0.1 (Build 26A434)"}
```

The control also fails, so this result does not diagnose descriptor rejection
or disprove native gesture support. The public initializer exposes no reason
for nil; its system log records `IOServiceOpen:0xe00002c2`
(`kIOReturnBadArgument`), not a direct privilege error.

A second experiment copied the executable to a separate temporary file and
ad-hoc signed it with the documented
`com.apple.developer.hid.virtual.device=true` entitlement. Signing and
`codesign --verify --strict` succeeded, but launching that copy was killed
before the probe ran. AMFI recorded error `-424`: "The file is adhoc signed
but contains restricted entitlements". The kernel independently logged
restricted-entitlement signature validation failure. Thus adding the
entitlement to an ad-hoc executable is not sufficient on this secured system.

Apple Technical Note TN3125 documents that macOS provisioning profiles are
required for restricted entitlements, and that a command-line tool needing
one must be packaged in a bundle able to embed the profile. A limited local
prerequisite check on October 27, 2026 found zero valid code-signing identities
and zero profiles in the standard Xcode/MobileDevice provisioning-profile
directories. This records what was installed locally at probe time, not whether
the developer account has a Developer ID certificate. Counts only were
recorded. No account, provisioning or system security setting was changed,
and no driver was installed. The probe and its
[structured results](../server/macos/Research/HIDRegistrationProbe-results.json)
are separate from the application and CI checks.

An existing Developer ID can be used for the follow-up publisher. Enable the
HID Virtual Device capability for its App ID, generate the matching Developer ID
provisioning profile, and embed it in the signed publisher bundle. The effective
profile and signature must authorize the virtual-device entitlement; the current
ad-hoc application build does not include this experimental entitlement.

The next actionable prerequisite is that signed/provisioned CoreHID publisher.
Then test
the public descriptor, inspect its actual matched event service and feature
requests, and observe native gesture events in an ordinary test application.
Successful enumeration or raw HID report delivery alone must not enable a
Reflection native-gesture capability.

## Smallest follow-up

1. On a Windows 11 validation machine, check the new exports, then validate
   synthetic contact generation and cleanup with pinch, rotation, scrolling,
   multi-finger navigation and changed Windows touchpad settings. Keep Windows
   10 and unavailable-API fallback behavior.
2. Obtain effective CoreHID publishing authorization, then validate Apple's
   public accessory descriptor on macOS through matched services and actual
   native gesture events. If it does not enter the recognizer, request Apple
   guidance about the supported trackpad service contract before creating a
   DEXT; a DEXT does not remove the same publishing prerequisite.

Describe the Windows feature as an optional native contact backend with
compatibility fallback and outstanding runtime verification. Describe macOS
as compatibility input with mouse/scroll events and shortcut-based gestures.

## Primary sources

- [CreateSyntheticPointerDevice2](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createsyntheticpointerdevice2)
- [Precision Touchpad Programming Guide](https://learn.microsoft.com/en-us/windows/win32/input-precisiontouchpad/precision-touchpad-guide)
- [SYNTHETIC_DEVICE_CREATION_PARAMS](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-synthetic_device_creation_params)
- [SYNTHETIC_DEVICE_CREATION_OPTIONS](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ne-winuser-synthetic_device_creation_options)
- [InjectTouchpadAction](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-injecttouchpadaction)
- [TOUCHPAD_ACTION](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ne-winuser-touchpad_action)
- [CreateSyntheticPointerDevice](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-createsyntheticpointerdevice)
- [NSEvent otherEvent](https://developer.apple.com/documentation/appkit/nsevent/otherevent(with:location:modifierflags:timestamp:windownumber:context:subtype:data1:data2:))
- [CoreHID HIDVirtualDevice](https://developer.apple.com/documentation/corehid/hidvirtualdevice)
- [Creating virtual devices](https://developer.apple.com/documentation/corehid/creatingvirtualdevices)
- [HID Entitlement Configuration Guide, Apple DTS](https://developer.apple.com/forums/thread/843327)
- [TN3125: Inside Code Signing: Provisioning Profiles](https://developer.apple.com/documentation/technotes/tn3125-inside-code-signing-provisioning-profiles)
- [CoreHID virtual-device entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.hid.virtual.device)
- [Accessory Design Guidelines, chapter 15](https://developer.apple.com/accessories/Accessory-Design-Guidelines.pdf)
- [Apple IOHIDEventDriver source](https://github.com/apple-oss-distributions/IOHIDFamily/blob/777ccd9698845aadf711e32d843c8c9b777431d9/IOHIDFamily/IOHIDEventDriver.cpp)
- [Requesting DriverKit entitlements](https://developer.apple.com/documentation/driverkit/requesting-entitlements-for-driverkit-development)
- [IOUserHIDEventService](https://developer.apple.com/documentation/hiddriverkit/iouserhideventservice)

The installed public SDK headers `NSEvent.h`, `CGEvent.h` and `CGEventTypes.h`
were also checked for available event constructors and fields.
