#!/usr/bin/env python3
"""Drive SUSTech Mobile on a running emulator or device.

Every step goes through `uiautomator dump`, so assertions read the real view
hierarchy instead of blind coordinates. Screenshots land in
`tools/screenshots/` and are the evidence attached to a run.

Scenarios
    shell       launcher -> Today, Services catalog, planned entries
    pms-smoke   print service: queue + delete, stations, scans, usage
    pms-upload  pick a file, upload it, see it in the queue
    tis-live    courses & grades against the real TIS (needs injected session)
    theme       the bottom bar is visible in both device themes (white-on-white guard)

Prerequisites: an emulator is booted (`adb devices`), the mock API is running
(`python3 tools/mock_pms.py`), and for `pms-*` the app has been pointed at it
(`python3 tools/inject_session.py --base-url http://10.0.2.2:8080`).
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
# Dumps live outside /sdcard: a dump file shows up in the file picker's
# "Recent" list and pollutes the very screen the harness is driving.
DUMP_PATH = "/data/local/tmp/sustech_ui.xml"
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
        and then the dump describes the wrong app — go home and force-stop
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

    def clear_data(self) -> None:
        self.shell("pm", "clear", PKG)
        time.sleep(1)

    def tree(self) -> ET.Element:
        for _ in range(5):
            self.shell("uiautomator", "dump", DUMP_PATH)
            xml = self.shell("cat", DUMP_PATH)
            if xml.strip().startswith("<"):
                return ET.fromstring(xml)
            time.sleep(1)
        raise RuntimeError("uiautomator produced no hierarchy")

    def find(self, *, rid=None, text=None, desc=None, exact: bool = False, timeout: int = 15) -> tuple[int, int]:
        """Centre of the first matching node.

        Text matching is case-insensitive (widget casing follows the device
        locale), and among several text matches a clickable node wins: an
        AlertDialog title contains the button's word and comes first in the
        hierarchy, so a naive first match taps the title and nothing happens.
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
                if exact:
                    # Tab labels and titles overlap by substring ("Courses" vs
                    # the "Courses & grades" toolbar title) — exact matching is
                    # the only way to hit the tab.
                    hit = hit or any(node_text == candidate.lower() for candidate in texts)
                else:
                    hit = hit or any(candidate.lower() in node_text for candidate in texts)
                hit = hit or any(candidate.lower() in node_desc for candidate in descs)
                if not hit:
                    continue
                bounds = re.findall(r"\d+", node.get("bounds", ""))
                if len(bounds) != 4:
                    continue
                x1, y1, x2, y2 = map(int, bounds)
                matches.append((node.get("clickable") == "true", (x1 + x2) // 2, (y1 + y2) // 2))
            for clickable, x, y in matches:
                if clickable:
                    return x, y
            if matches:
                return matches[0][1], matches[0][2]
            time.sleep(1)
        raise RuntimeError(f"no node for {rid or text or desc!r}")

    def tap(self, *, rid=None, text=None, desc=None, exact: bool = False, settle: float = 1.5) -> None:
        x, y = self.find(rid=rid, text=text, desc=desc, exact=exact)
        self.shell("input", "tap", str(x), str(y))
        time.sleep(settle)

    def type_into(self, rid: str, value: str) -> None:
        self.tap(rid=rid)
        self.shell("input", "text", value.replace(" ", "%s"))
        time.sleep(0.5)

    def count(self, rid_suffix: str) -> int:
        """How many rows of a list are on screen (rows carry stable ids)."""
        return sum(
            1 for node in self.tree().iter("node")
            if node.get("resource-id", "").endswith(rid_suffix)
        )

    def wait_count(self, rid_suffix: str, minimum: int = 1, timeout: int = 40) -> int:
        """Rows can take seconds to arrive (and TIS grades are a big payload) —
        poll instead of sampling once right after a tab tap."""
        deadline = time.time() + timeout
        count = 0
        while time.time() < deadline:
            count = self.count(rid_suffix)
            if count >= minimum:
                return count
            time.sleep(2)
        return count

    def text_present(self, needle: str) -> bool:
        return any(needle.lower() in text.lower() for text in self.texts())

    def type_text(self, value: str) -> None:
        """Type into whatever is focused (a picker search box, say)."""
        self.shell("input", "text", value.replace(" ", "%s"))
        time.sleep(1.5)

    def swipe_down(self, rid: str, settle: int = 4) -> None:
        """Drag downwards inside a container — the gesture pull-to-refresh needs."""
        x, y = self.find(rid=rid)
        self.shell("input", "swipe", str(x), str(max(40, y - 250)), str(x), str(y + 250), "400")
        time.sleep(settle)

    def texts(self) -> list[str]:
        return [node.get("text") or "" for node in self.tree().iter("node") if node.get("text")]

    def row_texts(self, rid_suffix: str) -> list[str]:
        """Text of the list rows themselves — toasts and banners excluded."""
        return [
            node.get("text") or ""
            for node in self.tree().iter("node")
            if node.get("resource-id", "").endswith(rid_suffix)
        ]

    def visible(self, fragment: str) -> bool:
        return any(
            node.get("resource-id", "").endswith(fragment) for node in self.tree().iter("node")
        )

    def dismiss_dialogs(self, attempts: int = 3) -> None:
        """Back out of a dialog a previous run may have left open."""
        for _ in range(attempts):
            if not self.visible("alertTitle"):
                return
            self.shell("input", "keyevent", "4")
            time.sleep(1)

    def nav_ink_fraction(self, name: str) -> float:
        """Share of non-background pixels in the bottom bar.

        This is the one check uiautomator cannot make: an item can be present in
        the hierarchy and still be invisible (white icon on a white bar), which
        is exactly the bug this guards against. Unselected items are included,
        so it fails when only the selected one paints.
        """
        from collections import Counter

        from PIL import Image

        path = self.screenshot(name)
        image = Image.open(path).convert("RGB")
        width, height = image.size
        bar = image.crop((0, int(height * 0.90), width, int(height * 0.985)))
        try:
            pixels = list(bar.get_flattened_data())  # Pillow >= 11
        except AttributeError:
            pixels = list(bar.getdata())
        background = Counter(pixels).most_common(1)[0][0]
        ink = sum(
            1
            for r, g, b in pixels
            if abs(r - background[0]) + abs(g - background[1]) + abs(b - background[2]) > 30
        )
        return ink / len(pixels)

    def screenshot(self, name: str) -> str:
        os.makedirs(SHOTS, exist_ok=True)
        path = os.path.join(SHOTS, f"{name}.png")
        with open(path, "wb") as handle:
            subprocess.run(self.base + ["exec-out", "screencap", "-p"], stdout=handle, check=True)
        print(f"    screenshot: {path}")
        return path

    def back(self) -> None:
        self.shell("input", "keyevent", "4")
        time.sleep(1.5)


def sign_in(ui: Ui, user: str, password: str) -> None:
    """Fill and submit the sign-in screen — it must be showing."""
    ui.type_into("input_username", user)
    ui.type_into("input_password", password)
    ui.tap(rid="btn_login", settle=8)


def ensure_signed_in(ui: Ui, user: str, password: str) -> None:
    """Bring the app up signed in.

    The app signs in by itself from the stored school account
    (`tools/inject_session.py --creds`), so the login screen should not appear
    at all. A cold start draws nothing for a moment, so this waits for the shell
    *or* the sign-in screen before deciding anything — racing a slow start used
    to look like "no bottom bar".
    """
    ui.dismiss_dialogs()
    ui.start_app()

    deadline = time.time() + 90
    while time.time() < deadline:
        if ui.visible("bottom_nav") or ui.visible("nav_services"):
            return
        if ui.visible("input_username"):
            break
        time.sleep(2)

    if ui.visible("input_username"):
        # The fields are on screen while the app signs in silently; give it the
        # time to finish before concluding that a human is needed.
        if wait_for_signed_in(ui, seconds=90):
            return
        if not user or not password:
            screen = " | ".join(t for t in ui.texts() if len(t) > 12)[:300]
            raise RuntimeError(
                "the app is parked on the sign-in screen: no account is configured on the "
                "device, or the stored one was refused. "
                f"Run: python3 tools/inject_session.py --creds  (screen: {screen})",
            )
        sign_in(ui, user, password)
        to_shell(ui)
        return

    raise RuntimeError("the app came up with neither the shell nor a sign-in screen")


def to_shell(ui: Ui, attempts: int = 3) -> None:
    """Back out of a service screen until the bottom bar is reachable.

    A service opens in its own activity, so a scenario that just navigated
    inside one has no bottom bar to tap. The sign-in screen is *waited for*
    rather than backed out of: BACK there closes the app, which then looks like
    a missing bottom bar three steps later.
    """
    for _ in range(attempts):
        if ui.visible("nav_services"):
            return
        if ui.visible("input_username"):
            wait_for_signed_in(ui)
            if ui.visible("nav_services"):
                return
        ui.back()


def wait_for_signed_in(ui: Ui, seconds: int = 90) -> bool:
    """Wait out the app's own silent sign-in. True once the shell is up."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        if ui.visible("bottom_nav"):
            return True
        if not ui.visible("input_username"):
            time.sleep(1)
            continue
        time.sleep(2)
    return ui.visible("bottom_nav")


def open_service(ui: Ui, title: str) -> None:
    """Services tab -> tap the service card."""
    to_shell(ui)
    ui.tap(rid="nav_services", settle=2)
    ui.tap(text=title, exact=True, settle=3)


def tab(ui: Ui, title: str) -> None:
    ui.tap(text=title, exact=True, settle=3)


def scenario_refresh(ui: Ui, user: str, password: str) -> int:
    """Pull-to-refresh must reload, on the dashboard and on a list screen."""
    failures = []
    ensure_signed_in(ui, user, password)
    ui.tap(rid="nav_today", settle=3)
    if not ui.visible("today_swipe"):
        failures.append("the dashboard is not inside a pull-to-refresh container")
    else:
        ui.swipe_down("today_swipe")
        ui.screenshot("60-refresh-today")
        texts = ui.texts()
        if any("Loading" in text for text in texts):
            failures.append("the dashboard stayed on its loading state after the pull")
        if not any(text.startswith("Week") for text in texts):
            failures.append(f"the week number vanished after the pull: {texts}")

    open_service(ui, "Courses & grades")
    tab(ui, "Courses")
    ui.swipe_down("swipe")
    ui.screenshot("61-refresh-list")
    rows = ui.wait_count("course_name", minimum=1, timeout=30)
    if rows < 1:
        failures.append("the course list came back empty after the pull")
    return report(failures)


def report(failures: list[str]) -> int:
    print()
    if failures:
        print("FAILED:")
        for item in failures:
            print(f"  - {item}")
        return 1
    print("all steps passed")
    return 0


def scenario_shell(ui: Ui, user: str, password: str) -> int:
    """Launcher, Today card, and the service catalog."""
    failures = []
    ensure_signed_in(ui, user, password)
    to_shell(ui)
    ui.tap(rid="nav_today", settle=3)
    ui.screenshot("30-today")
    if not ui.visible("today_week"):
        failures.append("Today did not render")

    ui.tap(rid="nav_services", settle=2)
    ui.screenshot("31-services")
    texts = " | ".join(ui.texts())
    for expected in ("Printing", "Courses & grades", "Not implemented yet"):
        if expected not in texts:
            failures.append(f"catalog is missing {expected!r}")

    ui.tap(rid="nav_account", settle=3)
    ui.screenshot("32-account")
    if not ui.visible("account_tis_session"):
        failures.append("Account tab lost the per-service session rows")

    return report(failures)


def scenario_pms_smoke(ui: Ui, user: str, password: str) -> int:
    """Print service end to end: queue + delete, stations, scans, usage."""
    failures = []
    ensure_signed_in(ui, user, password)
    open_service(ui, "Printing")

    ui.screenshot("33-print-queue")
    before = ui.wait_count("job_name", timeout=20)
    print(f"    queued documents: {before}")
    if before < 1:
        failures.append("print queue is empty — the mock should have two jobs")

    ui.tap(rid="job_delete", settle=2)
    ui.screenshot("34-delete-dialog")
    ui.tap(rid="android:id/button1", settle=4)
    ui.wait_count("job_name", timeout=3)
    after = ui.count("job_name")
    print(f"    queued documents after delete: {after}")
    if after >= before:
        failures.append("delete did not remove the row")
    ui.screenshot("35-print-queue-after-delete")

    tab(ui, "Stations")
    ui.screenshot("36-stations")
    if ui.wait_count("station_name", timeout=20) < 1:
        failures.append("no stations listed")

    tab(ui, "Scans")
    ui.screenshot("37-scans")
    if ui.wait_count("scan_name", timeout=20) < 1:
        failures.append("no scans listed")

    tab(ui, "Usage")
    ui.screenshot("38-usage")
    if ui.wait_count("usage_when", timeout=20) < 1:
        failures.append("no usage rows")

    return report(failures)


def pick_file(ui: Ui, filename: str) -> bool:
    """Select [filename] in the system picker, trying the ways a user would.

    The picker opens on "Recent", which does not index adb-pushed files, so:
    walk the roots to Downloads; if that misses, use its search box. Returns
    false only when both routes fail.
    """
    if ui.text_present(filename):
        ui.tap(text=filename, settle=3)
        return True

    try:
        ui.tap(desc="Show roots", settle=2)
        ui.tap(text="Downloads", exact=True, settle=3)
    except RuntimeError:
        pass
    if ui.text_present(filename):
        ui.tap(text=filename, settle=3)
        return True

    try:
        ui.shell("input", "keyevent", "4")  # leave any drawer/half state
        time.sleep(1)
        ui.tap(desc="Search", settle=1)
    except RuntimeError:
        return False
    ui.type_text(filename)
    time.sleep(2)
    if ui.text_present(filename):
        ui.tap(text=filename, settle=3)
        return True
    return False


def scenario_pms_upload(ui: Ui, filename: str, user: str, password: str, tag: str = "", expect_queued: bool = True) -> int:
    """Pick a pushed file, upload it, confirm it lands in the queue."""
    failures = []
    ensure_signed_in(ui, user, password)
    open_service(ui, "Printing")

    ui.tap(rid="fab_upload", settle=2)
    ui.screenshot("40-upload-empty" + tag)
    if not ui.visible("btn_pick"):
        failures.append("upload screen did not open")
        return report(failures)

    ui.tap(rid="btn_pick", settle=4)
    ui.screenshot("41-picker-recent" + tag)
    if not pick_file(ui, filename):
        failures.append(f"{filename} could not be selected in the file picker")
        ui.screenshot("42b-picker-missing" + tag)
        return report(failures)
    ui.screenshot("42-picker-selected" + tag)

    ui.screenshot("43-upload-ready" + tag)
    ui.tap(rid="btn_upload", settle=8)
    ui.screenshot("44-upload-done" + tag)

    # An accepted upload must bring the screen back to the queue; a screen that
    # stays open means the app decided the upload failed. Which of the two is
    # correct depends on whether the server actually queued the job.
    still_open = ui.visible("btn_upload")
    if still_open and expect_queued:
        failures.append("the upload screen is still open — an accepted upload was treated as a failure")
    if not still_open and not expect_queued:
        failures.append("the app left the upload screen although the job never reached the queue")

    if still_open:
        ui.screenshot("45-upload-stayed-open" + tag)
    else:
        to_shell(ui)
        open_service(ui, "Printing")
        rows = ui.wait_count("job_name", minimum=1, timeout=40)
        ui.screenshot("45-queue-after-upload" + tag)
        listed = ui.row_texts("job_name")
        print(f"    queue rows: {rows} -> {listed}")
        if expect_queued and filename not in " ".join(listed):
            failures.append(f"{filename} is not in the print queue rows: {listed}")
        if not expect_queued and filename in " ".join(listed):
            failures.append(f"{filename} reached the queue although the server never queued it")

    return report(failures)


def scenario_theme(ui: Ui, user: str, password: str) -> int:
    """The bottom bar must be visible in both device themes, untouched.

    Regression for the bug where the bar rendered white-on-white in dark mode
    until an item was selected.
    """
    failures = []
    for mode in ("no", "yes"):
        ui.shell("cmd", "uimode", "night", mode, check=False)
        time.sleep(4)
        ensure_signed_in(ui, user, password)
        to_shell(ui)
        ui.screenshot(f"80-shell-night-{mode}")
        ink = ui.nav_ink_fraction(f"81-navbar-night-{mode}")
        print(f"    night={mode}: bottom-bar ink {ink:.4f}")
        if ink < 0.02:
            failures.append(
                f"bottom bar looks empty in night={mode} (ink {ink:.4f}) — icons or labels are not painting",
            )
        for item in ("nav_today", "nav_services", "nav_account"):
            if not ui.visible(item):
                failures.append(f"{item} missing in night={mode}")
    ui.shell("cmd", "uimode", "night", "no", check=False)
    return report(failures)


def scenario_tis_live(ui: Ui) -> int:
    """Courses & grades against the real service (injected session)."""
    failures = []
    ui.dismiss_dialogs()
    ui.start_app()
    open_service(ui, "Courses & grades")

    tab(ui, "This week")
    ui.screenshot("50-tis-week")
    header = [t for t in ui.texts() if t.startswith("Week ")]
    print(f"    week header: {header[:1]}")
    if not header:
        failures.append("no week header — TIS session missing or refused")
    classes = ui.wait_count("class_name", timeout=30)
    print(f"    classes listed: {classes}")
    if classes < 1:
        failures.append("no classes listed for the current week")

    tab(ui, "Courses")
    ui.screenshot("51-tis-courses")
    courses = ui.wait_count("course_name", timeout=30)
    print(f"    courses listed: {courses}")
    if courses < 1:
        failures.append("no courses listed")

    tab(ui, "Grades")
    ui.screenshot("52-tis-grades")
    grades = ui.wait_count("grade_name", timeout=30)
    print(f"    grades listed: {grades}")
    if grades < 1:
        failures.append("no grades listed")

    tab(ui, "Exams")
    ui.screenshot("53-tis-exams")

    return report(failures)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", default="shell")
    parser.add_argument("--serial")
    parser.add_argument("--user", default="", help="only used when the app has no account stored")
    parser.add_argument("--password", default="", help="only used when the app has no account stored")
    parser.add_argument("--file", default="smoke-upload.pdf")
    args = parser.parse_args()

    ui = Ui(args.serial)
    ui.wait_boot()
    print("device ready:", ui.adb("devices").strip().splitlines()[-1])

    scenarios = {
        "shell": lambda: scenario_shell(ui, args.user, args.password),
        "pms-smoke": lambda: scenario_pms_smoke(ui, args.user, args.password),
        "pms-upload": lambda: scenario_pms_upload(ui, args.file, args.user, args.password),
        "pms-upload-html": lambda: scenario_pms_upload(
            ui, args.file, args.user, args.password, tag="-html"
        ),
        "refresh": lambda: scenario_refresh(ui, args.user, args.password),
        "pms-upload-redirect-http": lambda: scenario_pms_upload(
            ui, args.file, args.user, args.password, tag="-redir"
        ),
        "pms-upload-dropped": lambda: scenario_pms_upload(
            ui, args.file, args.user, args.password, tag="-dropped", expect_queued=False
        ),
        "tis-live": lambda: scenario_tis_live(ui),
        "theme": lambda: scenario_theme(ui, args.user, args.password),
    }
    handler = scenarios.get(args.scenario)
    if handler is None:
        print(f"unknown scenario: {args.scenario}", file=sys.stderr)
        print("known:", ", ".join(scenarios))
        return 2
    return handler()


if __name__ == "__main__":
    sys.exit(main())
