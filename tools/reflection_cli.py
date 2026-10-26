"""Portable build, installation, pairing, and protocol diagnostic commands."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time

from reflection_protocol import Client, DEFAULT_PORT, ProtocolError, decode_key

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.linloir.reflection"
RECEIVER = PACKAGE + "/.control.ControlReceiver"


def run(args, capture=False, check=True):
    result = subprocess.run([str(x) for x in args], cwd=ROOT,
                            capture_output=capture, text=True)
    if check and result.returncode:
        message = result.stderr.strip() if capture else "command failed"
        raise RuntimeError(message or "command failed with exit " + str(result.returncode))
    return result


def native_binary():
    override = os.environ.get("REFLECTION_SERVER")
    if override:
        path = Path(override)
        if not path.is_file():
            raise RuntimeError("REFLECTION_SERVER does not point to an executable")
        return path
    if platform.system() == "Darwin":
        for configuration in ("release", "debug"):
            result = run(["swift", "build", "--package-path", "server/macos",
                          "-c", configuration, "--show-bin-path"], capture=True)
            path = Path(result.stdout.strip()) / "reflection"
            if path.is_file():
                return path
    elif platform.system() == "Windows":
        for relative in ("server/windows/build/Release/reflection-host.exe",
                         "server/windows/build/reflection-host.exe"):
            path = ROOT / relative
            if path.is_file():
                return path
    else:
        raise RuntimeError("A host backend is currently available for macOS and Windows")
    raise RuntimeError("Native server is not built; run: python3 reflection build server")


def adb_prefix(serial=None):
    if shutil.which("adb") is None:
        raise RuntimeError("Install Android SDK Platform-Tools and put adb on PATH")
    if serial:
        return ["adb", "-s", serial]
    result = run(["adb", "devices"], capture=True)
    devices = [line.split()[0] for line in result.stdout.splitlines()[1:]
               if len(line.split()) >= 2 and line.split()[1] == "device"]
    if len(devices) != 1:
        raise RuntimeError("Connect and authorize exactly one Android device, or supply --serial")
    return ["adb", "-s", devices[0]]


def android_component(package, component):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", package):
        raise ValueError("Invalid Android application ID")
    return package + "/" + ("." + component if package == PACKAGE else PACKAGE + "." + component)


def broadcast(adb, command, extras=None, package=PACKAGE):
    # adb shell reconstructs a command line. Quote each argument for Android's sh
    # independently; no user input is interpolated into an unquoted shell command.
    import shlex
    args = ["am", "broadcast", "-n", android_component(package, "control.ControlReceiver"), "--es", "command", command]
    for key, value in (extras or {}).items():
        kind = "--ez" if isinstance(value, bool) else "--ei" if isinstance(value, int) else "--es"
        args += [kind, key, str(value).lower() if isinstance(value, bool) else str(value)]
    result = run(adb + ["shell", " ".join(shlex.quote(x) for x in args)], capture=True)
    match = re.search(r"Broadcast completed: result=(-?\d+)(?:, data=\"(.*)\")?", result.stdout, re.S)
    if match is None:
        raise RuntimeError("Android did not return a control result; install the APK first")
    payload = match.group(2)
    if payload is None:
        raise RuntimeError("Android control receiver did not respond; install and open the APK first")
    try:
        value = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise RuntimeError("Android returned an invalid control response") from exc
    if not isinstance(value, (dict, list)):
        raise RuntimeError("Android returned an invalid control response")
    if int(match.group(1)) != 0:
        raise RuntimeError(value.get("error", "Android command failed"))
    return value


def json_print(value):
    print(json.dumps(value, ensure_ascii=False, indent=2))


def config_args(args):
    return ["--config", args.config] if getattr(args, "config", None) else []


def native_pairing(args):
    command = ["config", "show", "--secrets"] if platform.system() == "Windows" else ["pair"]
    result = run([native_binary()] + config_args(args) + command, capture=True)
    value = json.loads(result.stdout)
    decode_key(value["token"])
    return value


def build(target):
    if target in ("server", "all"):
        if platform.system() == "Darwin":
            run(["swift", "build", "--package-path", "server/macos", "-c", "release"])
            run(["sh", "server/macos/scripts/build-driver.sh"])
        elif platform.system() == "Windows":
            run(["cmake", "-S", "server/windows", "-B", "server/windows/build"])
            run(["cmake", "--build", "server/windows/build", "--config", "Release"])
        else:
            raise RuntimeError("Build the host server on macOS or Windows")
    if target in ("ui", "all"):
        if platform.system() == "Darwin":
            run(["bash", "server/macos/scripts/build-app.sh"])
        elif platform.system() == "Windows":
            run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                 "-File", "server/windows/scripts/build-ui.ps1"])
        else:
            raise RuntimeError("Build the desktop UI on macOS or Windows")
    if target in ("android", "all"):
        if platform.system() == "Windows":
            gradle = shutil.which("gradle")
            if not gradle:
                raise RuntimeError("Install Gradle 8.x (8.11.1 or newer) and JDK 17, or build in Android Studio")
            run([gradle, "-p", "client/android", "assembleDebug"])
        else:
            run(["bash", "client/android/scripts/build-sdk.sh"])


def install_android(args):
    if args.apk:
        apk = Path(args.apk).resolve()
    else:
        choices = [
            ROOT / "client/android/build/sdk/reflection-debug.apk",
            ROOT / "client/android/app/build/outputs/apk/debug/app-debug.apk",
        ]
        existing = [p for p in choices if p.is_file()]
        if not existing:
            raise RuntimeError("APK not found; run: python3 reflection build android")
        apk = max(existing, key=lambda p: p.stat().st_mtime)
    if not apk.is_file():
        raise RuntimeError("APK does not exist")
    run(adb_prefix(args.serial) + ["install", "-r", apk])


def install_server(args):
    source = native_binary()
    if args.destination:
        directory = Path(args.destination).expanduser().resolve()
    elif platform.system() == "Windows":
        directory = Path(os.environ["LOCALAPPDATA"]) / "Reflection" / "bin"
    else:
        directory = Path.home() / ".local" / "bin"
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / ("reflection-host.exe" if platform.system() == "Windows" else "reflection-host")
    if source.resolve() != target.resolve():
        driver_name = "driver" if platform.system() == "Windows" else "ReflectionDriver.app"
        driver_source = source.parent / driver_name
        driver_target = directory / driver_name
        if driver_target.exists():
            raise RuntimeError("Destination already contains a driver package; choose a fresh --destination "
                               "to avoid retaining or merging an older package")
        if platform.system() == "Windows" and driver_source.is_dir():
            result = run([source, "driver", "verify", driver_source], capture=True, check=False)
            if result.returncode:
                raise RuntimeError("Driver package verification failed: " + (result.stdout or result.stderr).strip())
        shutil.copy2(source, target)
        if driver_source.is_dir():
            shutil.copytree(driver_source, driver_target)
    json_print({
        "installed": str(target),
        "pathEntry": str(directory),
        "note": "Add pathEntry to PATH. The native CLI runs independently of this checkout.",
    })


def native_ui():
    if platform.system() == "Darwin":
        choices = [Path.home() / "Applications/Reflection.app",
                   ROOT / "server/macos/build/Reflection.app"]
    elif platform.system() == "Windows":
        choices = [Path(os.environ["LOCALAPPDATA"]) / "Reflection/app/Reflection.Desktop.exe",
                   ROOT / "server/windows/build/ui/Reflection.Desktop.exe"]
    else:
        raise RuntimeError("The desktop UI is available for macOS and Windows")
    for path in choices:
        if path.exists():
            return path
    raise RuntimeError("Desktop UI not found; run: python3 reflection build ui")


def install_ui(args):
    if platform.system() == "Darwin":
        source = ROOT / "server/macos/build/Reflection.app"
        parent = Path(args.destination).expanduser().resolve() if args.destination else Path.home() / "Applications"
        target = parent / "Reflection.app"
    elif platform.system() == "Windows":
        source = ROOT / "server/windows/build/ui"
        target = (Path(args.destination).expanduser().resolve() if args.destination
                  else Path(os.environ["LOCALAPPDATA"]) / "Reflection/app")
    else:
        raise RuntimeError("The desktop UI is available for macOS and Windows")
    if not source.is_dir():
        raise RuntimeError("Desktop UI is not built; run: python3 reflection build ui")
    if source.resolve() != target.resolve():
        shutil.copytree(source, target, dirs_exist_ok=True)
    json_print({"installed": str(target), "note": "Launch the app or run: reflection ui"})


def launch_ui(args):
    path = native_ui()
    if platform.system() == "Darwin":
        run(["open", "-a", path] + (["--args", "--config", args.config] if args.config else []))
    else:
        subprocess.Popen([str(path)] + config_args(args), cwd=path.parent)


def pair_android(args):
    adb = adb_prefix(args.serial)
    pairing = native_pairing(args)
    port = args.port or int(pairing.get("port", DEFAULT_PORT))
    values = {"token": pairing["token"], "host": args.host, "port": port,
              "mode": args.mode}
    if args.bluetooth:
        values["bluetooth"] = args.bluetooth.upper()
    package = getattr(args, "package", PACKAGE)
    json_print(broadcast(adb, "configure", values, **({"package": package} if package != PACKAGE else {})))
    if args.connect:
        run(adb + ["shell", "am", "start", "-n", android_component(package, "ui.MainActivity")], capture=True)
        json_print(broadcast(adb, "connect", **({"package": package} if package != PACKAGE else {})))


def probe(args):
    if args.count < 1 or args.count > 10000 or args.interval < 0 or args.interval > 1:
        raise RuntimeError("count must be 1..10000 and interval must be 0..1 seconds")
    key = decode_key(Path(args.token_file).read_text()) if args.token_file else decode_key(native_pairing(args)["token"])
    measurements = []
    with Client(args.host, args.port, key) as client:
        for index in range(args.count):
            measurements.append(client.ping())
            if index + 1 < args.count:
                time.sleep(args.interval)
    ordered = sorted(measurements)
    json_print({
        "authenticated": True,
        "samplesInjected": 0,
        "count": len(measurements),
        "roundTripMilliseconds": {
            "min": min(measurements),
            "median": statistics.median(measurements),
            "p95": ordered[max(0, (95 * len(ordered) + 99) // 100 - 1)],
            "max": max(measurements),
        },
        "note": "Transport RTT only; not pen-to-pixel latency.",
    })


def doctor(args):
    result = {
        "platform": platform.system(),
        "python": platform.python_version(),
        "tools": {name: shutil.which(name) is not None
                  for name in ("adb", "swift", "java", "javac", "cmake", "gradle")},
    }
    try:
        result["server"] = json.loads(run([native_binary()] + config_args(args) + ["doctor"],
                                         capture=True).stdout)
    except (RuntimeError, FileNotFoundError, json.JSONDecodeError) as exc:
        result["serverError"] = str(exc)
    if shutil.which("adb"):
        output = run(["adb", "devices"], capture=True).stdout.splitlines()[1:]
        result["androidDevices"] = sum(line.endswith("\tdevice") for line in output)
    json_print(result)


def parser():
    p = argparse.ArgumentParser(description="Reflection build, installation, pairing, and diagnostics")
    commands = p.add_subparsers(dest="command", required=True)
    b = commands.add_parser("build", help="Build native artifacts")
    b.add_argument("target", choices=("android", "server", "ui", "all"))
    i = commands.add_parser("install", help="Install the Android APK or a user-local host executable")
    i.add_argument("target", choices=("android", "server", "ui"))
    i.add_argument("--apk")
    i.add_argument("--serial")
    i.add_argument("--destination", help="Host executable directory, macOS Applications directory, or Windows UI directory")
    ui = commands.add_parser("ui", help="Launch the native desktop settings app")
    ui.add_argument("--config")
    s = commands.add_parser("server", help="Forward arguments to the native host CLI", add_help=False)
    s.add_argument("arguments", nargs=argparse.REMAINDER)
    d = commands.add_parser("doctor", help="Report toolchain, server, and Android availability as JSON")
    d.add_argument("--config")
    u = commands.add_parser("usb", help="Create or remove ADB reverse forwarding")
    u.add_argument("action", choices=("connect", "disconnect"), default="connect", nargs="?")
    u.add_argument("--port", type=int, default=DEFAULT_PORT)
    u.add_argument("--serial")
    a = commands.add_parser("android", help="Control Android without its UI")
    a.add_argument("action", choices=("status", "connect", "disconnect", "devices", "paired", "clear",
                                     "preview-on", "preview-off", "selftest", "launch",
                                     "stabilization", "stabilization-on", "stabilization-off",
                                     "calibration-start", "calibration-finish",
                                     "calibration-cancel", "calibration-reset", "tilt", "mapping"))
    a.add_argument("control_action", nargs="?",
                   help="For tilt: auto|standard|off|status|reset. For mapping: status|next|previous|all|select")
    a.add_argument("display_id", nargs="?", help="Display ID for android mapping select")
    a.add_argument("--serial")
    a.add_argument("--package", default=PACKAGE, help="Android application ID (for an optional side-by-side preview)")
    pair = commands.add_parser("pair-android", help="Copy native server pairing settings over authorized ADB")
    pair.add_argument("--config")
    pair.add_argument("--serial")
    pair.add_argument("--package", default=PACKAGE, help="Android application ID (for an optional side-by-side preview)")
    pair.add_argument("--mode", choices=("tcp", "bluetooth"), default="tcp")
    pair.add_argument("--host", default="127.0.0.1")
    pair.add_argument("--port", type=int)
    pair.add_argument("--bluetooth", help="An already paired host Bluetooth address")
    pair.add_argument("--connect", action="store_true")
    probe_parser = commands.add_parser("probe", help="Authenticate and measure transport RTT; injects no input")
    probe_parser.add_argument("--config")
    probe_parser.add_argument("--host", default="127.0.0.1")
    probe_parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    probe_parser.add_argument("--token-file")
    probe_parser.add_argument("--count", type=int, default=20)
    probe_parser.add_argument("--interval", type=float, default=0.1)
    commands.add_parser("test", help="Run protocol and the available platform core checks")
    return p


def main(argv=None):
    # Preserve native option syntax, including "server --config ...".
    values = list(sys.argv[1:] if argv is None else argv)
    try:
        if values and values[0] == "server":
            return run([native_binary()] + values[1:], check=False).returncode
        args = parser().parse_args(values)
        if args.command == "build":
            build(args.target)
        elif args.command == "install":
            {"android": install_android, "server": install_server, "ui": install_ui}[args.target](args)
        elif args.command == "ui":
            launch_ui(args)
        elif args.command == "doctor":
            doctor(args)
        elif args.command == "pair-android":
            pair_android(args)
        elif args.command == "probe":
            probe(args)
        elif args.command == "usb":
            if not 1 <= args.port <= 65535:
                raise RuntimeError("port must be 1..65535")
            command = ["reverse"] + (["--remove"] if args.action == "disconnect" else [])
            command += ["tcp:" + str(args.port)]
            if args.action == "connect":
                command += ["tcp:" + str(args.port)]
            run(adb_prefix(args.serial) + command)
        elif args.command == "android":
            android_component(args.package, "control.ControlReceiver")
            def send(command, extras=None):
                arguments = [adb, command] + ([extras] if extras is not None else [])
                return broadcast(*arguments, **({"package": args.package} if args.package != PACKAGE else {}))
            if args.control_action is not None and args.action not in ("tilt", "mapping"):
                raise ValueError("An additional action is only accepted by android tilt or mapping")
            if args.display_id is not None and (args.action != "mapping" or args.control_action != "select"):
                raise ValueError("A display ID is only accepted by android mapping select")
            adb = adb_prefix(args.serial)
            if args.action == "mapping":
                action = args.control_action or "status"
                if action not in ("status", "next", "previous", "all", "select"):
                    raise ValueError("Mapping action must be status, next, previous, all, or select")
                if action == "select" and not args.display_id:
                    raise ValueError("android mapping select requires a display ID")
                extras = {"action": action}
                if args.display_id is not None:
                    extras["display"] = args.display_id
                json_print(send("mapping", extras))
            elif args.action == "tilt":
                if args.control_action not in (None, "auto", "standard", "off", "status", "reset"):
                    raise ValueError("Tilt mode must be auto, standard, off, status, or reset")
                if args.control_action == "reset":
                    json_print(send("tilt-reset"))
                else:
                    json_print(send("tilt",
                        {} if args.control_action in (None, "status") else {"mode": args.control_action}))
            elif args.action == "launch":
                run(adb + ["shell", "am", "start", "-n", android_component(args.package, "ui.MainActivity")])
            elif args.action.startswith("preview-"):
                json_print(send("preview", {"enabled": args.action == "preview-on"}))
            elif args.action in ("stabilization-on", "stabilization-off"):
                json_print(send("stabilization",
                                     {"enabled": args.action == "stabilization-on"}))
            else:
                json_print(send(args.action))
        elif args.command == "test":
            run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"])
            if platform.system() != "Windows":
                run(["bash", "client/android/scripts/test-core.sh"])
            else:
                print("Android shell checks are not run by this Windows entry point; see client/android/README.md.")
            if platform.system() == "Darwin":
                run(["swift", "run", "--package-path", "server/macos", "reflection-core-checks"])
            elif platform.system() == "Windows":
                run(["ctest", "--test-dir", "server/windows/build",
                     "--build-config", "Release", "--output-on-failure"])
            if shutil.which("clang++") and platform.system() != "Windows":
                out = ROOT / ".local" / "checks"
                out.mkdir(parents=True, exist_ok=True)
                run(["bash", "server/windows/scripts/test-portable.sh"])
        return 0
    except KeyboardInterrupt:
        return 130
    except (OSError, RuntimeError, ValueError, ProtocolError, EOFError) as exc:
        print("reflection: " + str(exc), file=sys.stderr)
        return 1
