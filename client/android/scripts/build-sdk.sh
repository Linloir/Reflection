#!/usr/bin/env bash
# Build a debug APK using only a JDK and the official Android SDK tools.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
output_dir="$project_dir/build/sdk"
debug_keystore="${REFLECTION_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
if [[ ! -d "$android_sdk" ]]; then
    echo "Set ANDROID_SDK_ROOT to an installed Android SDK." >&2; exit 1
fi
build_tools="$(python3 - "$android_sdk/build-tools" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]); items=[x for x in p.iterdir() if (x/'aapt2').exists()]
if not items: raise SystemExit('Install Android SDK Build-Tools 35 or later')
print(sorted(items,key=lambda x:tuple(int(v) for v in x.name.split('.') if v.isdigit()))[-1])
PY
)"
android_jar="$(python3 - "$android_sdk/platforms" <<'PY'
import pathlib,re,sys
items=list(pathlib.Path(sys.argv[1]).glob('*/android.jar'))
if not items: raise SystemExit('Install Android SDK Platform 35 or later')
print(sorted(items,key=lambda p:tuple(int(x) for x in re.findall(r'\d+',p.parent.name)))[-1])
PY
)"
mkdir -p "$output_dir/generated" "$output_dir/classes" "$output_dir/dex"
find "$output_dir/classes" -type f -name '*.class' -delete
python3 - "$project_dir/app/src/main/AndroidManifest.xml" "$output_dir/AndroidManifest.xml" <<'PYMANIFEST'
import pathlib,sys
text=pathlib.Path(sys.argv[1]).read_text().replace('<manifest ', '<manifest package="io.github.linloir.reflection" ', 1)
pathlib.Path(sys.argv[2]).write_text(text)
PYMANIFEST
"$build_tools/aapt2" compile --dir "$project_dir/app/src/main/res" -o "$output_dir/resources.zip"
"$build_tools/aapt2" link -o "$output_dir/resources.apk" -I "$android_jar" \
    --manifest "$output_dir/AndroidManifest.xml" --java "$output_dir/generated" \
    --min-sdk-version 21 --target-sdk-version 35 --version-code 1 --version-name 0.1.0 "$output_dir/resources.zip"
python3 - "$project_dir" "$output_dir" <<'PY'
import pathlib,sys
project=pathlib.Path(sys.argv[1]); out=pathlib.Path(sys.argv[2])
sources=list((project/'app/src/main/java').rglob('*.java'))+list((out/'generated').rglob('*.java'))
(out/'sources.txt').write_text('\n'.join('"'+str(p)+'"' for p in sources)+'\n')
PY
javac --release 8 -encoding UTF-8 -classpath "$android_jar" -d "$output_dir/classes" "@$output_dir/sources.txt"
jar cf "$output_dir/classes.jar" -C "$output_dir/classes" .
"$build_tools/d8" --min-api 21 --lib "$android_jar" --output "$output_dir/dex" "$output_dir/classes.jar"
python3 - "$output_dir" <<'PY'
import pathlib,shutil,sys,zipfile
out=pathlib.Path(sys.argv[1]); shutil.copyfile(out/'resources.apk',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as archive:
 for dex in (out/'dex').glob('*.dex'): archive.write(dex,dex.name)
PY
mkdir -p "$(dirname "$debug_keystore")"
# Preserve the original debug certificate when upgrading from an older SDK build.
if [[ ! -f "$debug_keystore" && -f "$output_dir/debug.keystore" ]]; then
    (umask 077; cp -n "$output_dir/debug.keystore" "$debug_keystore")
fi
if [[ ! -f "$debug_keystore" ]]; then
    (umask 077; keytool -genkeypair -keystore "$debug_keystore" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000)
fi
"$build_tools/zipalign" -f 4 "$output_dir/unsigned.apk" "$output_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$debug_keystore" --ks-pass pass:android \
    --key-pass pass:android --out "$output_dir/reflection-debug.apk" "$output_dir/aligned.apk"
"$build_tools/apksigner" verify "$output_dir/reflection-debug.apk"
printf '%s\n' "$output_dir/reflection-debug.apk"
