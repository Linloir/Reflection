#!/usr/bin/env python3
"""Check USB selection on a disposable emulator with a debug app or root ADB."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

PACKAGE = "io.github.linloir.reflection"
HOST_A = "a1" * 32
HOST_B = "b2" * 32
HOST_C = "c3" * 32


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--fresh-state", action="store_true", required=True,
        help="Clear Reflection data on the selected disposable emulator",
    )
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This check is restricted to a disposable Android emulator.")

    def adb(*command):
        return subprocess.run(
            ["adb", "-s", args.serial, *command], check=True,
            capture_output=True, text=True, timeout=30,
        ).stdout

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("The selected device does not identify itself as an emulator.")
    root_shell = adb("shell", "id", "-u").strip() == "0"
    args.output.mkdir(parents=True, exist_ok=True)
    results = {
        "emulator": args.serial, "hardwareVerified": False,
        "inputInjected": False, "checks": [], "passed": False,
    }

    def control(command, *extras, expected=0):
        output = adb(
            "shell", "am", "broadcast", "--include-stopped-packages",
            "-n", PACKAGE + "/.control.ControlReceiver",
            "--es", "command", command, *extras,
        )
        matches = re.findall(r"Broadcast completed: result=(-?\d+)", output)
        if not matches or int(matches[-1]) != expected:
            raise AssertionError("Unexpected receiver result for " + command)
        return output

    def preferences():
        if root_shell:
            xml = adb("exec-out", "cat", f"/data/user/0/{PACKAGE}/shared_prefs/reflection.xml")
        else:
            xml = adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/reflection.xml")
        if not xml.lstrip().startswith("<?xml"):
            raise AssertionError("Use a debuggable APK or enable root ADB on this disposable emulator.")
        root = ET.fromstring(xml)
        return {node.get("name"): node.text for node in root.findall("string")}

    def expect_selected(token, label):
        # Stop discovery/reconnect immediately after an explicit selection.
        control("disconnect")
        stored = preferences()
        assert stored.get("preferredHost") == token, label
        assert stored.get("token") == token, label + ": legacy credential differs"
        results["checks"].append(label)

    def provision(token, select=None, expected=0):
        extras = ["--es", "token", token]
        if select is not None:
            extras += ["--ez", "select", str(select).lower()]
        output = control("provision-usb", *extras, expected=expected)
        if expected == 0:
            resumed = '"automaticConnection":true' in output.replace('\\"', '"')
            assert resumed == (select is True), "USB lease must preserve manual disconnect; explicit selection resumes"
        return output

    def identity():
        output = control("pairing-identity").replace('\\"', '"')
        match = re.search(r'"id"\s*:\s*"([0-9a-f]{32})"', output)
        if not match:
            raise AssertionError("Receiver returned no tablet identity")
        return match.group(1)

    try:
        assert adb("shell", "pm", "clear", PACKAGE).strip() == "Success"
        control("disconnect")
        original_identity = identity()
        provision(HOST_A)
        expect_selected(HOST_A, "first authorized USB host is selected")
        provision(HOST_B)
        expect_selected(HOST_A, "new host background trust preserves existing choice")
        provision(HOST_B, select=True)
        expect_selected(HOST_B, "explicit selection can choose a newly trusted host")
        provision(HOST_A)
        expect_selected(HOST_B, "old host background lease cannot steal selection")
        provision(HOST_A, select=True)
        expect_selected(HOST_A, "explicit selection can return to a known host")
        provision(HOST_B, select=False)
        expect_selected(HOST_A, "explicitly neutral lease preserves host choice")
        provision(HOST_B.upper())
        expect_selected(HOST_A, "credential casing cannot turn a lease into a new host")
        provision(HOST_B, select=True)
        expect_selected(HOST_B, "explicit selection switches between known hosts")
        provision(HOST_A)
        expect_selected(HOST_B, "later old-host refresh still preserves selection")
        provision("invalid", select=True, expected=1)
        expect_selected(HOST_B, "invalid selection leaves stored choice unchanged")
        adb("shell", "am", "force-stop", PACKAGE)
        control("disconnect")
        provision(HOST_A)
        expect_selected(HOST_B, "process restart does not let an old lease steal choice")
        stored_hosts = json.loads(preferences()["trustedHosts"])
        assert {host["token"] for host in stored_hosts} == {HOST_A, HOST_B}
        results["checks"].append("both host credentials persist across restart")
        assert identity() == original_identity
        results["checks"].append("tablet identity survives host switches and restart")
        control(
            "configure", "--es", "host", "errorbox.invalid",
            "--ei", "port", "24829", "--es", "token", HOST_C,
        )
        provision(HOST_C, select=True)
        expect_selected(HOST_C, "USB can explicitly select a legacy LAN host")
        stored_hosts = json.loads(preferences()["trustedHosts"])
        selected = next(host for host in stored_hosts if host["token"] == HOST_C)
        assert selected["address"] == "errorbox.invalid" and selected["port"] == 24829
        assert {host["token"] for host in stored_hosts} == {HOST_A, HOST_B, HOST_C}
        results["checks"].append("USB selection preserves the LAN endpoint and other trusted hosts")
        results["passed"] = True
    except Exception as error:
        results["error"] = str(error)
        raise
    finally:
        try:
            control("disconnect")
            adb("shell", "am", "force-stop", PACKAGE)
        finally:
            (args.output / "results.json").write_text(
                json.dumps(results, indent=2) + "\n", encoding="utf-8",
            )
    print(f"USB selection: {len(results['checks'])} real Android checks passed")


if __name__ == "__main__":
    main()
