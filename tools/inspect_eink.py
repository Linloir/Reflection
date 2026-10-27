#!/usr/bin/env python3
"""Read-only APK/framework capture for researching an attached BOOX device."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=shutil.which("adb") or "adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", help="Note application package; auto-selects an exact known name otherwise")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1]
                        / "client/android/build/eink-inspection")
    args = parser.parse_args()

    def command(*parts):
        result = subprocess.run([args.adb, *parts], check=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, timeout=60)
        return result.stdout.strip()

    serial = args.serial
    if not serial:
        devices = [line.split()[0] for line in command("devices").splitlines()[1:]
                   if len(line.split()) > 1 and line.split()[1] == "device"]
        if len(devices) != 1:
            parser.error("Connect exactly one authorized device, or select --serial.")
        serial = devices[0]
    prefix = ["-s", serial]
    if command(*prefix, "get-state") != "device":
        parser.error("Selected device is not authorized and online.")
    packages = [line.removeprefix("package:") for line in
                command(*prefix, "shell", "pm", "list", "packages").splitlines()]
    package = args.package
    if package is None:
        package = next((name for name in ("com.onyx.android.note", "com.onyx.note")
                        if name in packages), None)
    if package is None or package not in packages:
        candidates = [name for name in packages if "note" in name.lower() or "scribble" in name.lower()]
        parser.error("Choose --package from the installed note apps: " + ", ".join(candidates))
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"capturedAt": datetime.now(timezone.utc).isoformat(), "serial": serial,
              "package": package, "properties": {}, "files": [], "errors": []}
    for prop in ("ro.product.manufacturer", "ro.product.brand", "ro.product.model",
                 "ro.product.device", "ro.build.display.id", "ro.build.version.release",
                 "ro.build.version.sdk", "ro.board.platform"):
        report["properties"][prop] = command(*prefix, "shell", "getprop", prop)
    paths = [line.removeprefix("package:") for line in
             command(*prefix, "shell", "pm", "path", package).splitlines()
             if line.startswith("package:")]
    paths.append("/system/framework/framework.jar")
    for index, remote in enumerate(paths):
        name = f"{index:02d}-{Path(remote).name}"
        local = args.output / name
        try:
            command(*prefix, "pull", remote, str(local))
            digest = hashlib.sha256(local.read_bytes()).hexdigest()
            report["files"].append({"remote": remote, "local": name, "bytes": local.stat().st_size,
                                    "sha256": digest})
        except subprocess.CalledProcessError as error:
            report["errors"].append({"remote": remote, "error": error.stderr.strip()})
    destination = args.output / "manifest.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(destination)
    return 0 if report["files"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
