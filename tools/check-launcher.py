#!/usr/bin/env python3
"""Run non-destructive navigation checks against an installed debug launcher.

This opens Home and YouTube but does not edit the dock, pair devices, or change
system settings. Requires native adb and the launcher installed as default Home.
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=os.environ.get("TV_SERIAL"), required=not os.environ.get("TV_SERIAL"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = ["adb", "-s", args.serial]
    checks = []

    def run(*command):
        return subprocess.run(adb + list(command), capture_output=True, check=True).stdout

    def key(*codes):
        run("shell", "input", "keyevent", *codes)
        time.sleep(0.6)

    def capture(name):
        for attempt in range(3):
            run("shell", "rm", "-f", "/data/local/tmp/tvhome-check.xml")
            result = run("shell", "uiautomator", "dump", "/data/local/tmp/tvhome-check.xml")
            if b"dumped to" in result:
                data = run("exec-out", "cat", "/data/local/tmp/tvhome-check.xml")
                break
            time.sleep(1)
        else:
            raise RuntimeError("UI capture failed after three attempts; no stale dump was used")
        (args.output / f"{name}.xml").write_bytes(data)
        (args.output / f"{name}.png").write_bytes(run("exec-out", "screencap", "-p"))
        return ET.fromstring(data)

    def node(tree, resource, focused=False):
        return next((item for item in tree.iter("node")
                     if item.get("resource-id") == f"com.gathin.tvhome:id/{resource}"
                     and (not focused or item.get("focused") == "true")), None)

    def check(condition, message):
        if not condition:
            raise AssertionError(message)
        checks.append(message)
        print("PASS:", message, flush=True)

    def matches_hero(tree):
        card, hero = node(tree, "card", True), node(tree, "heroName")
        return card is not None and hero is not None and card.get("content-desc") == hero.get("text")

    home = run("shell", "cmd", "package", "resolve-activity", "--brief", "--user", "0",
               "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME").decode()
    check("com.gathin.tvhome/.MainActivity" in home, "Custom launcher is the default Home")
    key("KEYCODE_HOME")
    key("KEYCODE_BACK")
    key("KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_LEFT")
    start = capture("01-dock")
    check(matches_hero(start), "Dock title matches the focused app")
    key("KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_LEFT")
    check(matches_hero(capture("02-fast-navigation")), "Fast Right-Left navigation keeps title synchronized")
    key("KEYCODE_DPAD_DOWN")
    capture("03-grid")
    key("KEYCODE_BACK")
    check(matches_hero(capture("04-back-to-dock")), "Back returns from the app grid to the dock")
    key("KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_LEFT")
    key("KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_RIGHT")
    selected = capture("05-youtube-tile")
    card = node(selected, "card", True)
    check(card is not None and card.get("content-desc") == "YouTube", "Original dock order retains YouTube in slot three")
    key("KEYCODE_DPAD_CENTER")
    time.sleep(2)
    activity = run("shell", "dumpsys", "activity", "activities").decode()
    check(any("topResumedActivity=" in line and "com.google.android.youtube.tv/" in line
              for line in activity.splitlines()), "YouTube opens from its dock tile")
    key("KEYCODE_HOME")
    returned = capture("06-return-home")
    card = node(returned, "card", True)
    check(matches_hero(returned) and card.get("content-desc") == "YouTube",
          "Home restores the selected app and its title")
    (args.output / "results.json").write_text(json.dumps({"serial": args.serial, "checks": checks}, indent=2) + "\n")


if __name__ == "__main__":
    main()
