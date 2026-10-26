import argparse
import json
import pathlib
import shlex
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools"))
import reflection_cli as cli


class ControlTests(unittest.TestCase):
    def test_undelivered_broadcast_is_not_a_success(self):
        response = subprocess.CompletedProcess([], 0, "Broadcast completed: result=0\n", "")
        with patch.object(cli, "run", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "did not respond"):
                cli.broadcast(["adb"], "connect")

    def test_control_response_and_remote_shell_quoting(self):
        response = subprocess.CompletedProcess(
            [], 0, 'Broadcast completed: result=0, data="{\\"configured\\":true}"\n'.replace('\\"', '"'), "")
        hostile_host = "example'; echo should-not-run; '"
        with patch.object(cli, "run", return_value=response) as run:
            result = cli.broadcast(["adb", "-s", "test"], "configure", {"host": hostile_host})
        self.assertEqual(result, {"configured": True})
        argv = run.call_args.args[0]
        self.assertEqual(argv[:4], ["adb", "-s", "test", "shell"])
        remote = shlex.split(argv[4])
        self.assertEqual(remote[-3:], ["--es", "host", hostile_host])

    def test_receiver_error_is_reported(self):
        response = subprocess.CompletedProcess(
            [], 0, 'Broadcast completed: result=1, data="{"error":"Host is required"}"\n', "")
        with patch.object(cli, "run", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "Host is required"):
                cli.broadcast(["adb"], "configure")

    def test_preview_package_keeps_original_java_component_name(self):
        preview = cli.PACKAGE + ".preview"
        self.assertEqual(cli.android_component(preview, "ui.MainActivity"),
                         preview + "/" + cli.PACKAGE + ".ui.MainActivity")
        response = subprocess.CompletedProcess([], 0, 'Broadcast completed: result=0, data="{}"\n', "")
        with patch.object(cli, "run", return_value=response) as run:
            cli.broadcast(["adb"], "mapping", {"action": "status"}, package=preview)
            self.assertIn(preview + "/" + cli.PACKAGE + ".control.ControlReceiver",
                          shlex.split(run.call_args.args[0][-1]))
        with self.assertRaises(ValueError):
            cli.android_component("bad;command", "ui.MainActivity")

    def test_stabilization_controls_and_calibration_lifecycle(self):
        with patch.object(cli, "adb_prefix", return_value=["adb", "-s", "test"]), \
             patch.object(cli, "broadcast", return_value={}) as broadcast, \
             patch.object(cli, "json_print"):
            for action, enabled in (("stabilization-on", True), ("stabilization-off", False)):
                self.assertEqual(cli.main(["android", action]), 0)
                broadcast.assert_called_with(["adb", "-s", "test"], "stabilization", {"enabled": enabled})
            for action in ("stabilization", "calibration-start", "calibration-finish",
                           "calibration-cancel", "calibration-reset"):
                self.assertEqual(cli.main(["android", action]), 0)
                broadcast.assert_called_with(["adb", "-s", "test"], action)

    def test_pairing_normalizes_bluetooth_and_windows_port_without_echoing_key(self):
        args = argparse.Namespace(serial=None, port=None, host="127.0.0.1",
                                  mode="bluetooth", bluetooth="aa:bb:cc:dd:ee:ff", connect=False)
        token = "12" * 32
        with patch.object(cli, "adb_prefix", return_value=["adb"]), \
             patch.object(cli, "native_pairing", return_value={"token": token, "port": "24819"}), \
             patch.object(cli, "broadcast", return_value={"configured": True}) as broadcast, \
             patch.object(cli, "json_print") as output:
            cli.pair_android(args)
        extras = broadcast.call_args.args[2]
        self.assertEqual(extras["bluetooth"], "AA:BB:CC:DD:EE:FF")
        self.assertEqual(extras["port"], 24819)
        self.assertNotIn(token, repr(output.call_args))

    def test_tilt_modes_and_detection_reset_have_distinct_controls(self):
        with patch.object(cli, "adb_prefix", return_value=["adb", "-s", "test"]), \
             patch.object(cli, "broadcast", return_value={}) as broadcast, \
             patch.object(cli, "json_print"):
            for mode in ("auto", "standard", "off"):
                self.assertEqual(cli.main(["android", "tilt", mode]), 0)
                broadcast.assert_called_with(["adb", "-s", "test"], "tilt", {"mode": mode})
            for suffix in ([], ["status"]):
                self.assertEqual(cli.main(["android", "tilt"] + suffix), 0)
                broadcast.assert_called_with(["adb", "-s", "test"], "tilt", {})
            self.assertEqual(cli.main(["android", "tilt", "reset"]), 0)
            broadcast.assert_called_with(["adb", "-s", "test"], "tilt-reset")
            broadcast.reset_mock()
            with patch("sys.stderr"):
                self.assertEqual(cli.main(["android", "calibration-reset", "auto"]), 1)
            broadcast.assert_not_called()

    def test_screen_controls_preserve_display_ids_and_reject_invalid_arguments(self):
        with patch.object(cli, "adb_prefix", return_value=["adb", "-s", "test"]), \
             patch.object(cli, "broadcast", return_value={}) as broadcast, \
             patch.object(cli, "json_print"):
            for action in ("status", "next", "previous", "all"):
                self.assertEqual(cli.main(["android", "mapping", action]), 0)
                broadcast.assert_called_with(["adb", "-s", "test"], "mapping", {"action": action})
            display = r"\\.\DISPLAY2"
            self.assertEqual(cli.main(["android", "mapping", "select", display]), 0)
            broadcast.assert_called_with(["adb", "-s", "test"], "mapping", {"action": "select", "display": display})
            for arguments in (["mapping", "select"], ["mapping", "unknown"], ["mapping", "all", "extra"],
                              ["tilt", "auto", "extra"], ["status", "next"]):
                broadcast.reset_mock()
                with patch("sys.stderr"):
                    self.assertEqual(cli.main(["android"] + arguments), 1)
                broadcast.assert_not_called()


if __name__ == "__main__":
    unittest.main()
