#!/bin/sh
set -eu
package_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
swift build --package-path "$package_root" -c release --product reflection-driver
bin_path=$(swift build --package-path "$package_root" -c release --show-bin-path)
destination=${1:-"$bin_path/ReflectionDriver.app"}
case "$destination" in *.app) ;; *) echo "Driver destination must end in .app" >&2; exit 2 ;; esac
mkdir -p "$destination/Contents/MacOS"
cp "$bin_path/reflection-driver" "$destination/Contents/MacOS/reflection-driver"
cp "$package_root/driver/Info.plist" "$destination/Contents/Info.plist"
if [ -n "${REFLECTION_DRIVER_SIGN_IDENTITY:-}" ]; then
    : "${REFLECTION_DRIVER_PROFILE:?Provide an Apple provisioning profile authorizing the virtual HID entitlement}"
    cp "$REFLECTION_DRIVER_PROFILE" "$destination/Contents/embedded.provisionprofile"
    codesign --force --options runtime --timestamp --sign "$REFLECTION_DRIVER_SIGN_IDENTITY" \
        --entitlements "$package_root/driver/ReflectionDriver.entitlements" "$destination"
else
    # Ad hoc builds deliberately omit the restricted entitlement. The runtime
    # probe fails explicitly; adding an unapproved entitlement is not a workaround.
    rm -f "$destination/Contents/embedded.provisionprofile"
    codesign --force --sign - "$destination"
fi
codesign --verify --deep --strict "$destination"
printf 'Built %s\n' "$destination"
