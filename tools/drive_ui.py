#!/usr/bin/env python3
"""Drive sustech-mobile on a running emulator or device.

Taps and assertions go through `uiautomator dump`, so every step checks the
real view hierarchy instead of blind coordinates. Screenshots land in
`tools/screenshots/` and are the evidence attached to a run.

Usage:

    python3 tools/drive_ui.py --scenario pms-smoke

Prerequisite: an emulator is booted (`adb devices` shows one), the mock API is
running (`python3 tools/mock_pms.py`), and the debug APK was built with
`-PpmsBaseUrl=http://10.0.2.2:8080`.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "edu.sustech.mobile"
SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots")


class Ui:
    def __init__(self, serial: str | None = None):
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def adb(self, *args: str, check: bool = True) -> str:
        result = subprocess.run(self.base + list(args), capture_output=True, text=True)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
        return result.stdout

    def shell(self, *args: str, check: bool = True) -> str:
        return self.adb("shell", *args, check=check)

    def wait_boot(self, timeout: int = 240) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if "1" in self.shell("getprop", "sys.boot_completed").strip():
                time.sleep(2)
                return
            time.sleep(3)
        raise RuntimeError("emulator did not finish booting")

    def start_app(self) -> None:
        """Bring the app up at its launcher activity, with nothing on top.

        A previous run can leave the system file picker (or a dialog) focused,
        and then the dump contains the wrong app — go home and force-stop
        first so the hierarchy under test is always ours.
        """
        self.shell("input", "keyevent", "3")
        time.sleep(1)
        self.stop_app()
        # The system picker runs in its own task and survives a HOME press.
        self.shell("am", "force-stop", "com.google.android.documentsui", check=False)
        time.sleep(1)
        self.shell("am", "start", "-n", f"{PKG}/.ui.LoginActivity")
        time.sleep(4)

    def stop_app(self) -> None:
        self.shell("am", "force-stop", PKG)

    def tree(self) -> ET.Element:
        for _ in range(5):
            self.shell("uiautomator", "dump", "/sdcard/ui.xml")
            xml = self.shell("cat", "/sdcard/ui.xml")
            if xml.strip().startswith("<"):
                return ET.fromstring(xml)
            time.sleep(1)
        raise RuntimeError("uiautomator produced no hierarchy")

    def find(self, *, rid=None, text=None, desc=None, timeout: int = 15) -> tuple[int, int]:
        """Centre of the first node matching a resource-id or any text candidate.

        Text matching is case-insensitive because widget casing follows the
        device locale ("Delete" on an English device, "删除" on a Chinese one).
        """
        rids = [rid] if isinstance(rid, str) else list(rid or [])
        texts = [text] if isinstance(text, str) else list(text or [])
        descs = [desc] if isinstance(desc, str) else list(desc or [])
        deadline = time.time() + timeout
        while time.time() < deadline:
            matches = []
            for node in self.tree().iter("node"):
                node_text = (node.get("text") or "").lower()
                node_id = node.get("resource-id", "")
                node_desc = (node.get("content-desc") or "").lower()
                hit = any(node_id.endswith(candidate) for candidate in rids)
                hit = hit or any(candidate.lower() in node_text for candidate in texts)
                hit = hit or any(candidate.lower() in node_desc for candidate in descs)
                if not hit:
                    continue
                bounds = re.findall(r"\d+", node.get("bounds", ""))
                if len(bounds) != 4:
                    continue
                x1, y1, x2, y2 = map(int, bounds)
                matches.append((node.get("clickable") == "true", (x1 + x2) // 2, (y1 + y2) // 2))
            # A dialog title also contains the button's word ("Delete queued
            # document" vs "DELETE") — always prefer the clickable node.
            for clickable, x, y in matches:
                if clickable:
                    return x, y
            if matches:
                return matches[0][1], matches[0][2]
            time.sleep(1)
        label = rid or text
        raise RuntimeError(f"no node for {label!r}")

    def tap(self, *, rid=None, text=None, desc=None, settle: float = 1.5) -> None:
        x, y = self.find(rid=rid, text=text, desc=desc)
        self.shell("input", "tap", str(x), str(y))
        time.sleep(settle)

    def type_into(self, rid: str, value: str) -> None:
        self.tap(rid=rid)
        self.shell("input", "text", value.replace(" ", "%s"))
        time.sleep(0.5)

    def screenshot(self, name: str) -> str:
        os.makedirs(SHOTS, exist_ok=True)
        path = os.path.join(SHOTS, f"{name}.png")
        with open(path, "wb") as handle:
            subprocess.run(self.base + ["exec-out", "screencap", "-p"], stdout=handle, check=True)
        print(f"    screenshot: {path}")
        return path

    def clear_data(self) -> None:
        self.shell("pm", "clear", PKG)
        time.sleep(1)

    def texts_endswith(self, suffix: str) -> list[str]:
        return [
            node.get("text") or ""
            for node in self.tree().iter("node")
            if (node.get("text") or "").endswith(suffix)
        ]

    def dismiss_dialogs(self, attempts: int = 3) -> None:
        """Back out of a dialog a previous run may have left open."""
        for _ in range(attempts):
            if not self.visible("alertTitle"):
                return
            self.shell("input", "keyevent", "4")
            time.sleep(1)

    def visible(self, fragment: str) -> bool:
        for node in self.tree().iter("node"):
            if node.get("resource-id", "").endswith(fragment):
                return True
        return False


def scenario_pms_smoke(ui: Ui, user: str, password: str, fresh: bool = True) -> int:
    """Sign in against the mock server, walk every tab, delete one job."""
    failures = []
    ui.dismiss_dialogs()
    ui.stop_app()
    if fresh:
        ui.clear_data()
    ui.start_app()
    ui.screenshot("01-login")

    if not ui.visible("input_username"):
        failures.append("login screen did not render")
        return report(failures)

    ui.type_into("input_username", user)
    ui.type_into("input_password", password)
    ui.screenshot("02-login-filled")
    ui.tap(rid="btn_login", settle=4)
    ui.screenshot("03-after-login")

    if not ui.visible("bottom_nav"):
        failures.append("did not reach the tabbed main screen")
        return report(failures)

    checks = [
        ("nav_stations", "list", "04-stations"),
        ("nav_jobs", "fab_upload", "05-jobs"),
        ("nav_scan", "list", "06-scans"),
        ("nav_usage", "btn_query", "07-usage"),
        ("nav_account", "account_name", "08-account"),
    ]
    for nav, marker, shot in checks:
        ui.tap(rid=nav, settle=3)
        ui.screenshot(shot)
        if not ui.visible(marker):
            failures.append(f"{nav}: {marker} not present")

    # Delete the first queued job and confirm the row actually disappears.
    ui.tap(rid="nav_jobs", settle=3)
    listed_before = ui.texts_endswith(".pdf")
    ui.tap(rid="job_delete", settle=2)
    ui.screenshot("09-delete-dialog")
    ui.tap(rid="android:id/button1", settle=4)
    ui.screenshot("10-jobs-after-delete")
    listed_after = ui.texts_endswith(".pdf")
    print(f"    queued documents: {listed_before} -> {listed_after}")
    if len(listed_after) >= len(listed_before):
        failures.append("delete did not remove the row")

    return report(failures)


def ensure_signed_in(ui: Ui, user: str, password: str) -> None:
    """Start the app and sign in, unless the stored session is still valid."""
    ui.dismiss_dialogs()
    ui.start_app()
    try:
        ui.find(rid="input_username", timeout=6)
    except RuntimeError:
        return
    ui.type_into("input_username", user)
    ui.type_into("input_password", password)
    ui.tap(rid="btn_login", settle=5)


def scenario_pms_upload(ui: Ui, filename: str, user: str, password: str) -> int:
    """Walk the cloud-print upload: pick a pushed file, upload, see it queued."""
    failures = []
    ensure_signed_in(ui, user, password)
    ui.tap(rid="nav_jobs", settle=2)
    ui.tap(rid="fab_upload", settle=2)
    ui.screenshot("11-upload-empty")

    if not ui.visible("btn_pick"):
        failures.append("upload screen did not open")
        return report(failures)

    ui.tap(rid="btn_pick", settle=4)
    ui.screenshot("12-picker-recent")
    # The picker opens on "Recent", which does not index adb-pushed files —
    # walk to Downloads the way a user would.
    try:
        ui.tap(desc="Show roots", settle=2)
    except RuntimeError:
        pass
    ui.screenshot("12a-picker-roots")
    ui.tap(text=["Downloads", "下载"], settle=3)
    ui.screenshot("12-file-picker")
    try:
        ui.tap(text=filename, settle=3)
    except RuntimeError:
        failures.append(f"{filename} not visible in the file picker")
        ui.screenshot("12b-picker-missing")
        return report(failures)
    ui.screenshot("13-upload-ready")

    ui.tap(rid="btn_upload", settle=6)
    ui.screenshot("14-upload-done")

    # UploadActivity finishes; the queue must now list the uploaded name.
    ui.tap(rid="nav_jobs", settle=3)
    ui.screenshot("15-jobs-after-upload")
    listed = ui.texts_endswith(".pdf")
    print(f"    queued documents after upload: {listed}")
    if filename not in listed:
        failures.append(f"{filename} did not appear in the print queue")

    return report(failures)


def scenario_pms_cas(ui: Ui) -> int:
    """Sign in through the WebView path and confirm it reaches the tabs."""
    failures = []
    ui.dismiss_dialogs()
    ui.stop_app()
    ui.clear_data()
    ui.start_app()
    ui.screenshot("20-cas-login-screen")

    ui.tap(rid="btn_cas", settle=1)
    # The mock page signs in instantly, so the WebView may already be gone —
    # catching it in the dump is evidence, not a requirement.
    saw_webview = ui.visible("cas_web")
    ui.screenshot("21-cas-webview")
    print(f"    WebView observed in hierarchy: {saw_webview}")

    # The page sets OSESSIONID, the app verifies it with Auth/Check and leaves.
    try:
        ui.find(rid="bottom_nav", timeout=30)
    except RuntimeError:
        failures.append("WebView sign-in did not reach the tabbed screen")
        ui.screenshot("21b-cas-stuck")
        return report(failures)
    print("    reached the tabbed screen through the WebView sign-in")

    ui.screenshot("22-cas-signed-in")
    ui.tap(rid="nav_account", settle=3)
    ui.screenshot("23-account-after-cas")
    return report(failures)


def report(failures: list[str]) -> int:
    print()
    if failures:
        print("FAILED:")
        for item in failures:
            print(f"  - {item}")
        return 1
    print("all smoke steps passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", default="pms-smoke")
    parser.add_argument("--file", default="smoke-upload.pdf")
    parser.add_argument("--serial")
    parser.add_argument("--user", default="12413021")
    parser.add_argument("--password", default="mockpassword")
    args = parser.parse_args()

    ui = Ui(args.serial)
    ui.wait_boot()
    print("device ready:", ui.adb("devices").strip().splitlines()[-1])

    if args.scenario == "pms-smoke":
        return scenario_pms_smoke(ui, args.user, args.password)
    if args.scenario == "pms-upload":
        return scenario_pms_upload(ui, args.file, args.user, args.password)
    if args.scenario == "pms-cas":
        return scenario_pms_cas(ui)
    print(f"unknown scenario: {args.scenario}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
