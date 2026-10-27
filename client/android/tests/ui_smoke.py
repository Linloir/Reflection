#!/usr/bin/env python3
"""Exercise the real Android UI on a disposable emulator, including five-point pen calibration."""
import argparse
import atexit
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "io.github.linloir.reflection"
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--fresh-state", action="store_true", help="Clear ONLY this app's state on the selected emulator")
parser.add_argument("--compact-first-run", action="store_true", help="Also exercise onboarding at 666 x 320 dp")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This smoke test is restricted to a disposable emulator.")
args.output.mkdir(parents=True, exist_ok=True)


def adb(*command, binary=False):
    result = subprocess.run(["adb", "-s", args.serial, *command], check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/reflection-smoke.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/reflection-smoke.xml"))


def node(label):
    for item in tree().iter("node"):
        if item.get("text") == label or item.get("content-desc") == label:
            return item
    raise AssertionError("UI element missing: " + label)


def bounds(item):
    return list(map(int, re.findall(r"\d+", item.get("bounds"))))


def click(label):
    item = node(label)
    assert item.get("enabled") == "true", label + " is disabled"
    x1, y1, x2, y2 = bounds(item)
    assert x2 > x1 and y2 > y1, label + " has empty bounds"
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.15)


def click_category(full, compact):
    labels = {item.get("text") for item in tree().iter("node")}
    click(full if full in labels else compact)


def screenshot(name):
    tree()  # Wait for accessibility/UI traversal to settle before capturing a frame.
    (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def reset_display():
    adb("shell", "wm", "size", "reset")
    adb("shell", "wm", "density", "reset")


atexit.register(reset_display)
if args.compact_first_run:
    adb("shell", "wm", "size", "1000x480")
    adb("shell", "wm", "density", "240")
if args.fresh_state:
    adb("shell", "pm", "clear", PACKAGE)
adb("shell", "am", "start", "-n", PACKAGE + "/.ui.MainActivity")
time.sleep(1)
try:
    click("Got it")
except AssertionError:
    pass
screenshot("01-welcome")
click("下一步  →")
assert node("下一步  →").get("enabled") == "false"
screenshot("02-calibration")
area = node("依次按住五个校准点")
x1, y1, x2, y2 = bounds(area)
for x, y in ((.16, .2), (.84, .2), (.84, .8), (.16, .8), (.5, .5)):
    px, py = str(round(x1 + x * (x2 - x1 - 1))), str(round(y1 + y * (y2 - y1 - 1)))
    adb("shell", "input", "stylus", "swipe", px, py, px, py, "1800")
assert node("下一步  →").get("enabled") == "true"
node("5 个点已完成，坐标修正与抖动噪声已保存。")
screenshot("03-calibrated")
click("下一步  →")
click("跳过")
click("跳过")
screenshot("04-host")
click("下一步  →")
screenshot("05-connection")
click("跳过")
screenshot("06-workspace")
click("设置")
node("下方  ›")
screenshot("07-settings")
click("下方  ›")
node("左侧  ›")
click("← 取消")
click("设置")
node("下方  ›")  # Cancel must discard the draft.
click("下方  ›")
click("保存 ✓")
screenshot("08-left-toolbar")
if args.compact_first_run:
    click("更多工具")
    node("本地笔迹")
    node("触摸板")
    node("连接状态")
    screenshot("08b-paged-toolbar")
    click("更多工具")
click("设置")
node("左侧  ›")  # Save must persist.
click_category("电子纸", "墨水")
click("关闭  □")
click("保存 ✓")
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-n", PACKAGE + "/.ui.MainActivity")
time.sleep(.5)
click("设置")
node("左侧  ›")
click_category("电子纸", "墨水")
node("开启  ■")
screenshot("09-eink-settings")
click("← 取消")

# Compact landscape exposes navigation/toolbar clipping that tablet-only checks miss.
adb("shell", "wm", "size", "1000x600")
adb("shell", "wm", "density", "240")
time.sleep(.5)
screenshot("10-compact-workspace")
click("设置")
node("保存 ✓")
click("墨水")
node("设置 / 电子纸")
click("↓")
screenshot("11-compact-settings")
click("← 取消")
reset_display()

# Keep injected workspace strokes local even if a development host is reachable.
adb("shell", "am", "broadcast", "-n", PACKAGE + "/.control.ControlReceiver", "--es", "command", "disconnect")
click("设置")
click("电子纸")
click("关闭  □")  # Fading is initially off; enabling it must also retain preview.
click("保存 ✓")
area = bounds(node("Reflection pen and touch workspace"))
blank = adb("exec-out", "screencap", "-p", binary=True)
x1, y1, x2, y2 = area
start_x, end_x = round(x1 + (x2 - x1) * .3), round(x1 + (x2 - x1) * .7)
start_y, end_y = round(y1 + (y2 - y1) * .4), round(y1 + (y2 - y1) * .65)
adb("shell", "input", "stylus", "swipe", str(start_x), str(start_y), str(end_x), str(end_y), "500")
visible = adb("exec-out", "screencap", "-p", binary=True)
time.sleep(3)
cleared = adb("exec-out", "screencap", "-p", binary=True)
assert visible != blank, "Enabled local ink must appear immediately after a stroke"
assert cleared == blank, "Fading ink must restore the original canvas after its timeout"
(args.output / "12-ink-visible.png").write_bytes(visible)
(args.output / "13-ink-cleared.png").write_bytes(cleared)

errors = adb("logcat", "-d", "-s", "AndroidRuntime:E")
assert "FATAL EXCEPTION" not in errors, errors
results = {
    "emulator": args.serial,
    "checks": [
        "first launch and six-page flow", "next disabled before calibration",
        "five real injected stylus holds complete and save", "skip unavailable tilt",
        "settings cancel discards draft", "settings save survives process restart",
        "local preview switch reachable in settings", "left toolbar",
        "compact landscape settings category navigation and pagination", "no AndroidRuntime crash",
        "local ink appears and fading restores the exact original canvas",
    ],
    "hardwareVerified": False,
    "compactFirstRun": args.compact_first_run,
}
if args.compact_first_run:
    results["checks"].append("six-page onboarding at 320 dp height and paginated toolbar access")
(args.output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
print("Android emulator UI smoke checks passed; artifacts: " + str(args.output))
