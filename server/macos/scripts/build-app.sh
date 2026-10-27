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
chmod 755 "$destination/Contents/MacOS/Reflection" "$destination/Contents/MacOS/reflection-host"

platform_tools=${REFLECTION_PLATFORM_TOOLS:-"${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}/platform-tools"}
if [ ! -x "$platform_tools/adb" ] || [ ! -f "$platform_tools/NOTICE.txt" ]; then
    printf '%s\n' "Install Android SDK Platform-Tools on the build machine or set REFLECTION_PLATFORM_TOOLS. The app bundle must include adb and its notices." >&2
    exit 1
fi
mkdir -p "$destination/Contents/Resources/platform-tools"
cp "$platform_tools/adb" "$platform_tools/NOTICE.txt" "$destination/Contents/Resources/platform-tools/"
if [ -f "$platform_tools/source.properties" ]; then
    cp "$platform_tools/source.properties" "$destination/Contents/Resources/platform-tools/"
fi
chmod 755 "$destination/Contents/Resources/platform-tools/adb"

# Local builds are signed ad hoc. Distribution requires a Developer ID signature.
codesign --force --sign - "$destination/Contents/Resources/platform-tools/adb"
codesign --force --sign - --identifier io.github.linloir.reflection.host \
    "$destination/Contents/MacOS/reflection-host"
codesign --force --sign - "$destination"
codesign --verify --deep --strict "$destination"

printf 'Built %s\n' "$destination"
