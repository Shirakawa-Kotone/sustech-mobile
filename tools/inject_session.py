#!/usr/bin/env python3
"""Put a session into the installed app so a screen can be driven without
typing credentials into a WebView.

Two jobs:

1. Point the app at the mock print server (`--base-url http://10.0.2.2:8080`)
   instead of rebuilding with `-PpmsBaseUrl=...`.
2. Copy a real TIS session into the app's cookie store (`--tis`), obtained
   through the Python client's authorizer — so the TIS screens can be verified
   against the live service without automating a password entry on the device.

The app stores cookies as JSON in `shared_prefs/sustech_mobile_session.xml`
(key `cookies`), which is exactly what this writes. Cookie *values* are never
printed; only names and the destination file.

Usage:
    python3 tools/inject_session.py --base-url http://10.0.2.2:8080
    python3 tools/inject_session.py --tis
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import xml.sax.saxutils as sax

PKG = "edu.sustech.mobile"
PREFS_SETTINGS = "shared_prefs/sustech_mobile.xml"
PREFS_SESSION = "shared_prefs/sustech_mobile_session.xml"


def adb(args: list[str], check: bool = True) -> str:
    result = subprocess.run(["adb"] + args, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def push_prefs(remote_name: str, content: str) -> None:
    local = f"/tmp/{remote_name.split('/')[-1]}"
    with open(local, "w", encoding="utf-8") as handle:
        handle.write(content)
    adb(["push", local, "/data/local/tmp/app_prefs.xml"])
    adb(["shell", "run-as", PKG, "mkdir", "-p", "shared_prefs"])
    adb(["shell", "run-as", PKG, "cp", "/data/local/tmp/app_prefs.xml", remote_name])
    adb(["shell", "am", "force-stop", PKG])
    print(f"wrote {remote_name}")


def prefs_xml(entries: dict[str, str]) -> str:
    body = "".join(
        f'    <string name="{sax.escape(key)}">{sax.escape(value)}</string>\n'
        for key, value in entries.items()
    )
    return (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n" + body + "</map>\n"
    )


def read_prefs_cookies() -> list[dict[str, str]]:
    """Whatever session the app currently holds, so an injection can merge."""
    raw = adb(["shell", "run-as", PKG, "cat", PREFS_SESSION], check=False)
    # Read the <string name="cookies"> value, not "the text between the first >
    # and the last <" — that grabbed the XML declaration and silently produced
    # an empty list, which then made the --tis merge drop a print session.
    import re as _re

    match = _re.search(r'<string name="cookies">(.*?)</string>', raw, _re.S)
    if not match:
        return []
    try:
        return json.loads(sax.unescape(match.group(1)))
    except Exception:
        return []


def mock_print_cookies(port: int = 8080) -> list[dict[str, str]]:
    """A print session for the mock server, taken from its own login endpoint.

    Signing in through the UI is already covered by the harness reading the
    login screen; this keeps the *content* scenarios deterministic.
    """
    import base64
    import json as _json
    import urllib.request

    from Crypto.Cipher import PKCS1_v1_5
    from Crypto.PublicKey import RSA

    host = "127.0.0.1"
    base = f"http://{host}:{port}"

    def call(path: str, payload=None, method: str = "POST"):
        # GetAuthToken is an empty POST, PublicKey a GET, Login a JSON POST —
        # the same three shapes the app sends.
        data = None
        if method == "POST":
            data = b"" if payload is None else _json.dumps(payload).encode()
        request = urllib.request.Request(
            base + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest"},
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            token = response.headers.get("Set-Cookie", "")
            return _json.load(response), token

    token, _ = call("/api/client/Auth/GetAuthToken")
    key_body, _ = call("/api/client/Auth/PublicKey", method="GET")
    result = key_body["result"]
    pem = "-----BEGIN PUBLIC KEY-----\n" + "\n".join(
        result["publicKey"][i:i + 64] for i in range(0, len(result["publicKey"]), 64)
    ) + "\n-----END PUBLIC KEY-----"
    key = RSA.import_key(pem)
    encrypted = base64.b64encode(
        PKCS1_v1_5.new(key).encrypt(("harness;" + result["nonceStr"]).encode())
    ).decode()
    _, cookie_header = call(
        "/api/client/Auth/Login",
        {"szLogonName": "harness", "szPassword": encrypted, "szToken": token["szToken"]},
    )
    sid = cookie_header.split(";")[0]
    return [{"name": "OSESSIONID", "value": sid.split("=", 1)[1], "domain": "10.0.2.2", "path": "/"}]


def tis_cookies() -> list[dict[str, str]]:
    """A live TIS session from the Python client's authorizer."""
    from sustech_survival.sso import TISAuth

    auth = TISAuth()
    ok, reason = auth.ensure()
    if not ok:
        raise SystemExit(f"TIS auth failed: {reason}")

    cache = getattr(auth, "_session_cache", {}) or {}
    if not cache:
        raise SystemExit("no cookies in the authorizer cache")

    out = []
    for name, value in cache.items():
        # The authorizer sends these on every request via a Cookie header; the
        # app needs them scoped to the host each one belongs to.
        domain = "cas.sustech.edu.cn" if name.upper() == "TGC" else "tis.sustech.edu.cn"
        out.append({"name": name, "value": value, "domain": domain, "path": "/"})
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", help="print server the app should use")
    parser.add_argument(
        "--mock",
        action="store_true",
        help="point the app at tools/mock_pms.py and copy in a print session",
    )
    parser.add_argument("--tis", action="store_true", help="copy a live TIS session in")
    parser.add_argument("--serial")
    args = parser.parse_args()

    if args.serial:
        global adb
        original = adb

        def adb(fp_args, check=True, _serial=args.serial, _orig=original):  # type: ignore[misc]
            result = subprocess.run(
                ["adb", "-s", _serial] + list(fp_args), capture_output=True, text=True
            )
            if check and result.returncode != 0:
                raise RuntimeError(f"adb failed: {result.stderr.strip()}")
            return result.stdout

    base_url = args.base_url
    if args.mock:
        # 10.0.2.2 is the emulator's alias for the host machine's loopback.
        base_url = "http://" + "10.0.2.2" + ":8080"

    if base_url:
        push_prefs(PREFS_SETTINGS, prefs_xml({"base_url": base_url}))

    if args.mock:
        print_prefs_session = {"cookies": json.dumps(mock_print_cookies(), ensure_ascii=False)}
        push_prefs(PREFS_SESSION, prefs_xml(print_prefs_session))
        print("print session injected from the mock")

    if args.tis:
        cookies = tis_cookies()
        # Merge rather than replace: a print session may already be in there.
        existing = read_prefs_cookies()
        merged = [c for c in existing if c["domain"] not in ("tis.sustech.edu.cn", "cas.sustech.edu.cn")]
        merged.extend(cookies)
        push_prefs(PREFS_SESSION, prefs_xml({"cookies": json.dumps(merged, ensure_ascii=False)}))
        print("TIS cookies injected:", ", ".join(c["name"] for c in cookies))

    if not args.base_url and not args.tis and not args.mock:
        parser.print_help()
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
