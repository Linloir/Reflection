#!/bin/sh
set -eu

package_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
destination=${1:-"$package_root/build/Reflection.app"}

case "$destination" in
    *.app) ;;
    *) printf '%s\n' "Output path must end with .app" >&2; exit 2 ;;
esac

swift build --package-path "$package_root" -c release --product reflection-app
swift build --package-path "$package_root" -c release --product reflection
bin_path=$(swift build --package-path "$package_root" -c release --show-bin-path)

mkdir -p "$destination/Contents/MacOS"
cp "$bin_path/reflection-app" "$destination/Contents/MacOS/Reflection"
cp "$bin_path/reflection" "$destination/Contents/MacOS/reflection-host"
cp "$package_root/Sources/ReflectionApp/Info.plist" "$destination/Contents/Info.plist"
sh "$package_root/scripts/build-driver.sh" "$destination/Contents/MacOS/ReflectionDriver.app"
chmod 755 "$destination/Contents/MacOS/Reflection" "$destination/Contents/MacOS/reflection-host"

# Local builds are signed ad hoc. Distribution requires a Developer ID signature.
codesign --force --sign - --identifier io.github.linloir.reflection.host \
    "$destination/Contents/MacOS/reflection-host"
codesign --force --sign - "$destination"
codesign --verify --deep --strict "$destination"

printf 'Built %s\n' "$destination"
