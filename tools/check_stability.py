#!/usr/bin/env python3
"""Exercise host smoothing, calibration and pressure persistence in dry-run mode."""

import argparse
import json
import math
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time

from check_host import command
from reflection_protocol import Client, PenSample, encode_frame


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--platform", choices=("windows", "macos"),
                        default="windows" if os.name == "nt" else "macos")
    args = parser.parse_args()
    binary = str(Path(args.server).resolve())
    windows = args.platform == "windows"
    checks = []
    with tempfile.TemporaryDirectory(prefix="reflection-stability-") as directory:
        root = Path(directory)
        config = root / ("server.conf" if windows else "server.json")
        call = lambda *values: command(binary, config, *values)
        call(*(["init"] if windows else ["config", "init"]))
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        call("config", "set", "port", str(port))
        curve_key = "mapping.pressure_interpolation" if windows else "mapping.pressureInterpolation"
        noise_key = "stabilization.noise"
        call("config", "set", curve_key, "smooth" if windows else '"smooth"')
        call("config", "set", "stabilization.strength", "0.65")
        call("profiles", "save", "stability-test")
        call("config", "set", curve_key, "linear" if windows else '"linear"')
        call("profiles", "load", "stability-test")
        snapshot = call("config", "snapshot") if windows else call("config", "show")
        values = snapshot.get("values", snapshot)
        assert (values[curve_key] if windows else values["mapping"]["pressureInterpolation"]) == "smooth"
        checks.append("smooth pressure mode survives configuration and profile persistence")
        pairing = call(*(["config", "show", "--secrets"] if windows else ["pair"]))
        key = bytes.fromhex(pairing["token"])
        with (root / "host.log").open("w+") as log:
            process = subprocess.Popen([binary, "--config", str(config), "start", "--dry-run"], stdout=log, stderr=log)
            try:
                def connect():
                    deadline = time.monotonic() + 10
                    while True:
                        try:
                            return Client("127.0.0.1", port, key)
                        except (OSError, EOFError):
                            if process.poll() is not None or time.monotonic() > deadline:
                                log.seek(0)
                                raise RuntimeError("Host failed: " + log.read())
                            time.sleep(.05)

                def status():
                    return call("status")["stabilization"]

                def wait_status(predicate):
                    deadline = time.monotonic() + 3
                    while True:
                        state = status()
                        if predicate(state):
                            return state
                        if time.monotonic() >= deadline:
                            raise AssertionError("Host did not publish expected state: " + json.dumps(state))
                        time.sleep(.05)

                with connect() as legacy:
                    legacy.send_sample(PenSample(1, 1, .4, .4, 0, phase=0))
                    legacy.ping()
                    wait_status(lambda state: state["legacy_client"])
                checks.append("legacy clients avoid double smoothing")

                with connect() as client:
                    client.stream.sendall(encode_frame(0x20, b'{"platform":"test","hostStabilization":1}', client.session_key))
                    sequence = 0
                    timestamp = 1_000_000_000

                    def sample(x, y, phase, pressure=.5, dt=10_000_000):
                        nonlocal sequence, timestamp
                        sequence += 1
                        timestamp += dt
                        client.send_sample(PenSample(sequence, timestamp, x, y, pressure, phase=phase))

                    sample(.8, .8, 1)
                    sample(.801, .8, 2)
                    sample(.05, .05, 2)
                    sample(.04, .04, 2)
                    sample(.04, .04, 3, 0)
                    client.ping()
                    state = wait_status(lambda state: state["guard_rejections"] >= 1 and not state["legacy_client"])
                    assert not state["legacy_client"] and state["guard_rejections"] >= 1
                    assert state["suppressed_strokes"] >= 1
                    checks.append("contact jump is intercepted without disconnecting authenticated transport")
                    sample(.5, .5, 1)
                    sample(.501, .5, 2)
                    sample(.501, .5, 3, 0)
                    client.ping()
                    sample(.5, .5, 1)
                    client.ping()
                    wait_status(lambda state: state["contact_down"])
                    call("calibration", "cancel", "--json")
                    call("calibration", "finish", "--json")
                    assert status()["contact_down"], "inactive calibration commands interrupted an ordinary stroke"
                    sample(.501, .5, 2)
                    sample(.501, .5, 3, 0)
                    client.ping()
                    wait_status(lambda state: not state["contact_down"])
                    checks.append("inactive calibration commands preserve normal contact and release")
                    before = config.read_bytes()
                    calibration = call("calibration", "start", "--json")
                    assert calibration["active"]
                    empty = call("calibration", "finish", "--json")
                    assert empty["result"] != "success" and empty["reason"]
                    assert config.read_bytes() == before
                    call("calibration", "cancel", "--json")
                    assert config.read_bytes() == before
                    checks.append("insufficient calibration and cancel preserve saved settings")
                    call("calibration", "start", "--json")
                    sample(.5, .5, 1)
                    for i in range(180):
                        # Small realistic drift and deterministic noise, with an isolated outlier.
                        x = .5 + i * .0000008 + .00035 * math.sin(i * 2.31)
                        y = .5 + .0003 * math.cos(i * 1.71)
                        if i == 78:
                            x += .008
                        sample(x, y, 2)
                        if i % 40 == 0:
                            sample(x, y, 2, dt=0)  # Android timestamps can share a millisecond.
                        time.sleep(.008)
                    sample(.5001, .5, 3, 0)
                    client.ping()
                    calibration = call("calibration", "status", "--json")
                    assert calibration["samples"] >= 120 and calibration["progress"] >= .99, calibration
                    result = call("calibration", "finish", "--json")
                    assert result["result"] == "success" and not result["active"], result
                    assert .00001 <= result["noise"] <= .02
                    checks.append("calibration tolerates drift, isolated outliers and duplicate timestamps")
                    saved = call("config", "snapshot") if windows else call("config", "show")
                    saved = saved.get("values", saved)
                    noise = float(saved[noise_key] if windows else saved["stabilization"]["noise"])
                    assert abs(noise - result["noise"]) < 1e-7
                    assert (saved[curve_key] if windows else saved["mapping"]["pressureInterpolation"]) == "smooth"
                    checks.append("calibration persists only noise and preserves unrelated pressure settings")
                    sample(.5, .5, 1)
                    sample(.501, .501, 2)
                    sample(.501, .501, 3, 0)
                    client.ping()
                    call("calibration", "start", "--json")
                # Disconnect must never leave calibration capturing forever.
                time.sleep(.15)
                assert not call("calibration", "status", "--json")["active"]
                checks.append("disconnect ends calibration and releases capture")
            except Exception:
                log.flush()
                log.seek(0)
                print(log.read())
                raise
            finally:
                if process.poll() is None:
                    try:
                        call("stop")
                        process.wait(timeout=5)
                    except (RuntimeError, subprocess.TimeoutExpired):
                        process.kill()
                        process.wait(timeout=5)
    print(json.dumps({"result": "passed", "checks": checks}, indent=2))


if __name__ == "__main__":
    main()
