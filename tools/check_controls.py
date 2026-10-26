#!/usr/bin/env python3
"""Check profile persistence, atomic settings, and host lifecycle without injecting input."""

import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--platform", choices=("macos", "windows"),
                        default="windows" if os.name == "nt" else "macos")
    args = parser.parse_args()
    binary = str(Path(args.server).resolve())
    windows = args.platform == "windows"
    checks = []
    with tempfile.TemporaryDirectory(prefix="reflection-controls-") as directory:
        config = Path(directory) / ("server.conf" if windows else "server.json")

        def call(*values, fails=False):
            result = subprocess.run([binary, "--config", str(config), *values],
                                    capture_output=True, text=True, timeout=10)
            if fails:
                assert result.returncode != 0, "unexpected success: " + values[0]
                return
            if result.returncode:
                raise RuntimeError(result.stderr.strip())
            return json.loads(result.stdout)

        call(*(["init"] if windows else ["config", "init"]))
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        call("config", "set", "port", str(port))
        before = config.read_bytes()
        gamma = "mapping.pressure_gamma" if windows else "mapping.pressureGamma"
        curve_key = "mapping.pressure_curve" if windows else "mapping.pressureCurve"
        curve_value = ("0,0;0.5,0.75;1,1" if windows else
                       '[{"input":0,"output":0},{"input":0.5,"output":0.75},{"input":1,"output":1}]')
        if windows:
            snapshot = call("config", "snapshot")
            call("config", "set", gamma, "1.1")
            changed = config.read_bytes()
            call("config", "apply", "--if-current", snapshot["revision"], gamma, "0.8", fails=True)
            assert config.read_bytes() == changed
            current = call("config", "snapshot")
            result = call("config", "apply", "--if-current", current["revision"], gamma, "1")
            assert result["saved"] and result["revision"] != current["revision"]
            before = config.read_bytes()
            checks.append("conditional edits reject stale UI revisions without overwriting CLI changes")
        call("config", "apply", gamma, "1.8", "port", "0", fails=True)
        assert config.read_bytes() == before
        checks.append("invalid multi-field edit leaves configuration unchanged")
        call("config", "apply", gamma, "1.8", curve_key, curve_value)
        saved = config.read_bytes()
        call("profiles", "save", "Soft pencil")
        call("profiles", "save", "Soft pencil", fails=True)
        call("profiles", "save", "../escape", fails=True)
        assert call("profiles", "list")["profiles"] == ["Soft pencil"]
        profile = call("profiles", "show", "Soft pencil")
        profile_text = json.dumps(profile)
        assert "token" not in profile_text and '"port"' not in profile_text and '"bind"' not in profile_text
        checks.append("named mapping profiles exclude listener settings and reject unsafe names")
        call("config", "set", gamma, "0.6")
        call("profiles", "load", "Soft pencil")
        assert config.read_bytes() == saved
        checks.append("profile activation restores mapping and preserves pairing configuration")
        call("profiles", "delete", "Soft pencil")
        assert call("profiles", "list")["profiles"] == []
        if not windows:
            alias = Path(directory) / "alias.json"
            alias.symlink_to(config)
            edited = subprocess.run([binary, "--config", str(alias), "config", "set", gamma, "1.2"],
                                    capture_output=True, text=True, timeout=10)
            assert edited.returncode == 0 and alias.is_symlink()
            assert call("config", "show")["mapping"]["pressureGamma"] == 1.2
            checks.append("configuration aliases preserve symlinks and lifecycle identity")

        # Read/write all host output to a temporary file so pipe capacity cannot block shutdown.
        with (Path(directory) / "host.log").open("w+") as log:
            for iteration in range(2):
                server = subprocess.Popen([binary, "--config", str(config), "start", "--dry-run"],
                                          stdout=log, stderr=log)
                try:
                    deadline = time.monotonic() + 10
                    while time.monotonic() < deadline:
                        status = call("status")
                        if status.get("processAlive", status.get("running", False)):
                            # A live lifecycle channel may precede asynchronous TCP readiness.
                            try:
                                with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                                    break
                            except OSError:
                                pass
                        if server.poll() is not None:
                            log.seek(0)
                            raise RuntimeError("host exited: " + log.read())
                        time.sleep(0.05)
                    else:
                        raise AssertionError("host did not start")
                    duplicate = subprocess.run([binary, "--config", str(config), "start", "--dry-run"],
                                               capture_output=True, text=True, timeout=5)
                    assert duplicate.returncode != 0
                    call("stop")
                    assert server.wait(timeout=5) == 0
                    status = call("status")
                    assert not status.get("processAlive", status.get("running", False))
                finally:
                    if server.poll() is None:
                        server.terminate()
                        try:
                            server.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            server.kill()
                            server.wait()
            checks.append("duplicate host rejected and graceful stop verified")
            checks.append("host restarts after stale status and socket state")
    print(json.dumps({"result": "passed", "checks": checks}, indent=2))


if __name__ == "__main__":
    main()
