#!/usr/bin/env python3
"""Check mode migration, failures and live switching without installing a driver or injecting input."""
import argparse
import json
import os
from pathlib import Path
import socket
import shutil
import struct
import subprocess
import tempfile
import time


def check_windows_packages(binary, directory):
    # Isolate the executable so status/installation see exactly these fixtures,
    # without touching a developer's build or the system driver store.
    isolated = Path(directory) / "isolated host"
    isolated.mkdir()
    executable = isolated / "reflection-host.exe"
    shutil.copy2(binary, executable)
    package = isolated / "driver"

    def verify(state):
        result = subprocess.run([str(executable), "driver", "verify", str(package)],
                                capture_output=True, text=True, timeout=15)
        value = json.loads(result.stdout)
        assert result.returncode == 1 and not value["available"], value
        assert value["state"] == state, value
        status = subprocess.run([str(executable), "driver", "status"],
                                capture_output=True, text=True, timeout=15, check=True)
        driver = json.loads(status.stdout)
        assert not driver["packageAvailable"] and driver["package"]["state"] == state, driver
        if not driver["available"]:
            config = isolated / "server.conf"
            if not config.exists():
                subprocess.run([str(executable), "--config", str(config), "init"],
                               capture_output=True, check=True, timeout=15)
            before = config.read_bytes()
            result = subprocess.run([str(executable), "--config", str(config), "input", "mode", "driver"],
                                    capture_output=True, text=True, timeout=15)
            assert result.returncode != 0 and config.read_bytes() == before
            assert value["detail"] in json.loads(result.stderr)["error"], result.stderr

    verify("missing")
    package.mkdir()
    (package / "ReflectionTablet.inf").write_text("unsigned fixture")
    verify("incomplete")
    (package / "ReflectionTablet.sys").write_bytes(b"not a driver")
    (package / "ReflectionTablet.cat").write_bytes(b"not a signed catalog")
    verify("invalid")
    # Both architectures must fail closed: one at the machine check, the other
    # at signature verification. No certificate is installed to create fixtures.
    observed = set()
    for machine in (0x8664, 0xAA64):
        image = bytearray(152)
        struct.pack_into("<H", image, 0, 0x5A4D)
        struct.pack_into("<I", image, 0x3C, 128)
        struct.pack_into("<IH", image, 128, 0x4550, machine)
        (package / "ReflectionTablet.sys").write_bytes(image)
        result = subprocess.run([str(executable), "driver", "verify", str(package)],
                                capture_output=True, text=True, timeout=15)
        state = json.loads(result.stdout)["state"]
        observed.add(state)
        verify(state)
    assert observed == {"wrong-architecture", "untrusted"}, observed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--platform", choices=("windows", "macos"),
                        default="windows" if os.name == "nt" else "macos")
    args = parser.parse_args()
    windows = args.platform == "windows"
    binary = str(Path(args.server).resolve())
    with tempfile.TemporaryDirectory(prefix="reflection-input-") as directory:
        if windows:
            check_windows_packages(binary, directory)
        config = Path(directory) / ("server.conf" if windows else "server.json")

        def call(*arguments, fails=False):
            process = subprocess.run([binary, "--config", str(config), *arguments],
                                     capture_output=True, text=True, timeout=15)
            if fails:
                assert process.returncode != 0, f"unexpected success: {arguments}"
                return
            assert process.returncode == 0, process.stderr or process.stdout
            return json.loads(process.stdout)

        def write_mode(mode):
            # Only the recording backend sees these writes. Public driver mode
            # commands intentionally require an installed/probed provider.
            if windows:
                text = "\n".join(line for line in config.read_text().splitlines()
                                 if not line.startswith("input.mode=")) + "\n"
                if mode is not None:
                    text += "input.mode=" + mode + "\n"
            else:
                value = json.loads(config.read_text())
                value.pop("inputMode", None)
                if mode is not None:
                    value["inputMode"] = mode
                text = json.dumps(value)
            temporary = config.with_suffix(".tmp")
            temporary.write_text(text)
            os.replace(temporary, config)

        def wait_mode(mode):
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                status = call("status")
                if status.get("inputMode") == mode:
                    return status
                time.sleep(0.1)
            raise AssertionError(f"input mode did not become {mode}: {status}")

        driver = call("driver", "status")
        if not driver["installed"]:
            assert call("driver", "uninstall")["mode"] == "api"
            assert not config.exists(), "uninstall should not create pairing credentials"
        call(*(["init"] if windows else ["config", "init"]))
        write_mode(None)
        assert call("input", "status")["mode"] == "api"
        before = config.read_bytes()
        call("input", "mode", "invalid", fails=True)
        assert config.read_bytes() == before
        # Never trigger UAC or a HID probe on a developer's installed driver.
        if not driver["installed"] and not driver["packageAvailable"]:
            call("input", "mode", "driver", fails=True)
            assert config.read_bytes() == before
            call("driver", "install", fails=True)
            assert config.read_bytes() == before
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        call("config", "set", "port", str(port))
        with (Path(directory) / "host.log").open("w+") as log:
            host = subprocess.Popen([binary, "--config", str(config), "start", "--dry-run"],
                                    stdout=log, stderr=log)
            try:
                wait_mode("api")
                pid = call("status")["pid"]
                write_mode("driver")
                assert wait_mode("driver")["pid"] == pid
                assert call("input", "mode", "api")["mode"] == "api"
                assert wait_mode("api")["pid"] == pid
                if not driver["installed"]:
                    write_mode("driver")
                    wait_mode("driver")
                    assert call("driver", "uninstall")["mode"] == "api"
                    assert wait_mode("api")["pid"] == pid
                    assert call("driver", "uninstall")["mode"] == "api"
                call("stop")
                host.wait(timeout=10)
                assert host.returncode == 0
            finally:
                if host.poll() is None:
                    host.terminate()
                    host.wait(timeout=10)
    print(json.dumps({"passed": [
        "legacy configuration defaults to API",
        "invalid/unavailable mode leaves configuration unchanged",
        "recording backend switches without restarting the host",
        "API switch and absent-driver uninstall are idempotent",
    ] + (["missing, incomplete, invalid, wrong-architecture and untrusted driver packages are rejected before installation"]
         if windows else [])}, indent=2))


if __name__ == "__main__":
    main()
