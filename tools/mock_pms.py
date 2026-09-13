#!/usr/bin/env python3
"""Offline stand-in for the 联创 PMS API.

The real PMS refuses every request from outside the campus network (HTTP 403
"Access forbidden"), so the app cannot be exercised end to end while off
campus. This server speaks the same wire format as `pms.sustech.edu.cn/api/client/*`
so the whole app — login, stations, print queue, scans, usage history, upload —
can be driven against it from an emulator or a phone on the same LAN.

It is a test double, not a client: it never touches the real service and it
holds no real credentials.

Run it:

    python3 tools/mock_pms.py --port 8080

Then set the app's server URL (login screen → 服务器设置, or the 我的 tab) to
`http://10.0.2.2:8080` from an Android emulator, or `http://<your-lan-ip>:8080`
from a physical device. The default `http://127.0.0.1:8080` only works from a
browser on the same machine.

The RSA step is real: the server generates a 1024-bit key, hands out the SPKI
as base64, and decrypts `password + ";" + nonceStr` with PKCS#1 v1.5 padding.
A wrong padding scheme in the app therefore fails the login here too.
"""
from __future__ import annotations

import argparse
import base64
import json
import re
import secrets
import threading
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

# -- Fixture data -------------------------------------------------------------

SERVER_GROUPS = [
    {"dwSN": 1, "szName": "OPMServer"},
    {"dwSN": 2, "szName": "LibraryServer"},
]

STATIONS = [
    {
        "dwDevSN": 1001,
        "szName": "慧园1栋二楼彩色(W1-2F CO)",
        "szStatInfo": "正常-就绪",
        "dwStatus": 1,
        "dwTrayPaper1": 9,
        "dwTrayPaper2": 8,
        "dwTrayPaper3": -1,
        "dwTrayPaper4": -1,
        "dwProperty": 1 | 2 | 4 | 8,
    },
    {
        "dwDevSN": 1002,
        "szName": "慧园1栋二楼黑白(W1-2F BW)",
        "szStatInfo": "正常-打印中",
        "dwStatus": 2,
        "dwTrayPaper1": 9,
        "dwTrayPaper2": -1,
        "dwTrayPaper3": -1,
        "dwTrayPaper4": -1,
        "dwProperty": 1 | 2,
    },
    {
        "dwDevSN": 2001,
        "szName": "图书馆一楼自助复印(B1 CO)",
        "szStatInfo": "缺纸",
        "dwStatus": 0x20,
        "dwTrayPaper1": 9,
        "dwTrayPaper2": -1,
        "dwTrayPaper3": -1,
        "dwTrayPaper4": -1,
        "dwProperty": 1 | 2 | 8,
    },
    {
        "dwDevSN": 2002,
        "szName": "图书馆三楼扫描站(B3 SC)",
        "szStatInfo": "正常-就绪",
        "dwStatus": 1,
        "dwTrayPaper1": -1,
        "dwTrayPaper2": -1,
        "dwTrayPaper3": -1,
        "dwTrayPaper4": -1,
        "dwProperty": 4,
    },
]

PRINT_JOBS = [
    {
        "dwJobId": 88234,
        "szJobName": "report-draft.pdf",
        "dwCreateDate": 20260911,
        "dwCreateTime": 143012,
        "dwCopies": 2,
        "szAttribe": "bw,single",
        "szPaperDetail": json.dumps([{"dwPaperID": 9, "dwBWPages": 12, "dwColorPages": 0}]),
    },
    {
        "dwJobId": 88235,
        "szJobName": "thesis-appendix.pdf",
        "dwCreateDate": 20260912,
        "dwCreateTime": 90544,
        "dwCopies": 1,
        "szAttribe": "color,vdup",
        "szPaperDetail": json.dumps([{"dwPaperID": 8, "dwBWPages": 0, "dwColorPages": 3}]),
    },
]

SCAN_JOBS = [
    {
        "dwJobId": 5510,
        "szDisplayName": "lab-notes-20260912.pdf",
        "dwFileSize": 428_133,
        "dwSubmitDate": 20260912,
        "dwSubmitTime": 171233,
    }
]

USAGE = [
    {
        "dwSID": 9001,
        "dwDate": 20260910,
        "dwTime": 101500,
        "dwPages": 24,
        "dwPaperID": 9,
        "dwUnitFee": 10,
        "dwUsedCardMoney": 240,
        "dwUsedFreeMoney": 0,
        "dwUsedMoney": 0,
        "dwSettleType": 1,
        "dwMFPSN": 1001,
        "dwType": 1,
        "szMemo": "",
    },
    {
        "dwSID": 9002,
        "dwDate": 20260912,
        "dwTime": 171400,
        "dwPages": 6,
        "dwPaperID": 8,
        "dwUnitFee": 20,
        "dwUsedCardMoney": 120,
        "dwUsedFreeMoney": 0,
        "dwUsedMoney": 0,
        "dwSettleType": 1,
        "dwMFPSN": 2002,
        "dwType": 2,
        "szMemo": "扫描",
    },
]

# -- Server state -------------------------------------------------------------

LOCK = threading.Lock()
STATE = {
    "print_jobs": list(PRINT_JOBS),
    "scan_jobs": list(SCAN_JOBS),
    "uploads": [],
    "sessions": set(),
    "nonce": "mock-nonce-0001",
    "log": [],
}

KEY = rsa.generate_private_key(public_exponent=65537, key_size=1024)
PUBLIC_SPKI = base64.b64encode(
    KEY.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
).decode()


def envelope(result=None, code=0, message="", **extra):
    body = {"code": code, "message": message, "result": result}
    body.update(extra)
    return body


class Handler(BaseHTTPRequestHandler):
    server_version = "MockPMS/0.1"

    # -- Plumbing ------------------------------------------------------------

    def log_message(self, fmt, *args):  # noqa: A003 - stdlib signature
        line = "%s - %s" % (self.address_string(), fmt % args)
        print(f"[mock-pms] {line}", flush=True)
        STATE["log"].append(line)

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def _json_body(self) -> dict:
        raw = self._read_body()
        if not raw:
            return {}
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def _send(self, payload: dict, status: int = 200, cookie: str | None = None):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(data)

    def _authed(self) -> bool:
        cookie = self.headers.get("Cookie") or ""
        return any(sid in cookie for sid in STATE["sessions"])

    def _require_auth(self) -> bool:
        if self._authed():
            return True
        self._send(envelope(code=401, message="not authenticated"), status=200)
        return False

    # -- Routing -------------------------------------------------------------

    def do_GET(self):  # noqa: N802 - stdlib signature
        path = self.path.split("?")[0]
        if not path.startswith("/api/"):
            # Any non-API path stands in for the CAS-fronted print page: it
            # hands the browser a session cookie the way the real sign-in
            # redirect does, which is how the app's WebView login path is
            # exercised offline.
            self.web_landing()
            return
        routes = {
            "/api/client/Auth/PublicKey": self.public_key,
            "/api/client/Station/GetSrvList": self.server_groups,
            "/api/client/Station/GetList": self.stations,
            "/api/client/PrintJob/Get": self.print_jobs,
            "/api/client/Scan/Get": self.scan_jobs,
            "/api/client/Usage/Get": self.usage,
        }
        handler = routes.get(path)
        if handler is None:
            self._send(envelope(code=404, message=f"no such endpoint: {path}"), status=404)
            return
        handler()

    def do_POST(self):  # noqa: N802 - stdlib signature
        path = self.path.split("?")[0]
        routes = {
            "/api/client/Auth/Check": self.auth_check,
            "/api/client/Auth/GetAuthToken": self.get_token,
            "/api/client/Auth/Login": self.login,
            "/api/client/PrintJob/Del": self.delete_print_job,
            "/api/client/Scan/Del": self.delete_scan_job,
            "/api/client/Report/DetailPage": self.report,
            "/api/client/CloudPrint/Upload": self.upload,
        }
        handler = routes.get(path)
        if handler is None:
            self._send(envelope(code=404, message=f"no such endpoint: {path}"), status=404)
            return
        handler()

    # -- Web sign-in page (stands in for CAS + the print landing page) --------

    def web_landing(self):
        sid = "OSESSIONID=" + secrets.token_hex(12)
        STATE["sessions"].add(sid)
        page = f"""<!doctype html>
<html lang="zh"><head><meta charset="utf-8"><title>Mock 打印系统</title></head>
<body style="font-family:sans-serif;padding:24px">
<h3>Mock sign-in page</h3>
<p>This page sets the session cookie and redirects, like the real CAS flow.</p>
<script>
  document.cookie = "{sid}; path=/";
  document.body.insertAdjacentHTML('beforeend', '<p>signed in: {sid}</p>');
</script>
</body></html>"""
        data = page.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    # -- Auth ----------------------------------------------------------------

    def public_key(self):
        self._send(envelope(result={"publicKey": PUBLIC_SPKI, "nonceStr": STATE["nonce"]}))

    def get_token(self):
        self._send(envelope(szToken=secrets.token_hex(8)))

    def login(self):
        payload = self._json_body()
        username = payload.get("szLogonName", "")
        ciphertext = payload.get("szPassword", "")
        if not payload.get("szToken"):
            self._send(envelope(code=-1, message="missing szToken"))
            return
        try:
            plaintext = KEY.decrypt(base64.b64decode(ciphertext), padding.PKCS1v15()).decode()
        except Exception as exc:
            self._send(envelope(code=-1, message=f"RSA decrypt failed: {exc}"))
            return
        # The site sends `password;<nonceStr>` — verify both halves.
        if ";" not in plaintext:
            self._send(envelope(code=-1, message="malformed password payload"))
            return
        password, nonce = plaintext.split(";", 1)
        if nonce != STATE["nonce"]:
            self._send(envelope(code=-1, message="stale nonce"))
            return
        if not password:
            self._send(envelope(code=-1, message="empty password"))
            return
        sid = "OSESSIONID=" + secrets.token_hex(12)
        STATE["sessions"].add(sid)
        self._send(
            envelope(result={"szTrueName": "测试用户", "szLogonName": username}),
            cookie=f"{sid}; Path=/",
        )

    def auth_check(self):
        if not self._require_auth():
            return
        self._send(envelope(result={
            "szTrueName": "测试用户",
            "szLogonName": "12413021",
            "szCardNo": "0000000001",
            "dwBalance": 1250,
        }))

    # -- Reads ---------------------------------------------------------------

    def server_groups(self):
        if not self._require_auth():
            return
        self._send(envelope(result=SERVER_GROUPS))

    def stations(self):
        if not self._require_auth():
            return
        self._send(envelope(result=STATIONS))

    def print_jobs(self):
        if not self._require_auth():
            return
        self._send(envelope(result=STATE["print_jobs"]))

    def scan_jobs(self):
        if not self._require_auth():
            return
        self._send(envelope(result=STATE["scan_jobs"]))

    def usage(self):
        if not self._require_auth():
            return
        self._send(envelope(result=USAGE, dwTotalPage=1))

    def report(self):
        if not self._require_auth():
            return
        payload = self._json_body()
        wanted = int(payload.get("dwType") or 1)
        rows = [r for r in USAGE if int(r["dwType"]) == wanted]
        page = max(1, int(payload.get("dwPageNo") or 1))
        size = max(1, int(payload.get("dwRowCount") or 5))
        start = (page - 1) * size
        total_pages = max(1, -(-len(rows) // size))
        self._send(envelope(result=rows[start:start + size], dwTotalPage=total_pages))

    # -- Writes --------------------------------------------------------------

    def delete_print_job(self):
        if not self._require_auth():
            return
        payload = self._json_body()
        job_id = int(payload.get("dwJobId") or 0)
        with LOCK:
            for job in STATE["print_jobs"]:
                if int(job["dwJobId"]) == job_id:
                    STATE["print_jobs"].remove(job)
                    self._send(envelope(code=0, message="ok"))
                    return
        # The real service answers "already printed" with code=-1, not a 4xx.
        self._send(envelope(code=-1, message="job not found (maybe already printed)"))

    def delete_scan_job(self):
        if not self._require_auth():
            return
        payload = self._json_body()
        job_id = int(payload.get("dwJobId") or 0)
        with LOCK:
            for job in STATE["scan_jobs"]:
                if int(job["dwJobId"]) == job_id:
                    STATE["scan_jobs"].remove(job)
                    self._send(envelope(code=0, message="ok"))
                    return
        self._send(envelope(code=-1, message="scan job not found"))

    def upload(self):
        if not self._require_auth():
            return
        content_type = self.headers.get("Content-Type") or ""
        match = re.search(r'boundary=(?:"([^"]+)"|([^;]+))', content_type)
        if not match:
            self._send(envelope(code=-1, message="not a multipart request"))
            return
        boundary = (match.group(1) or match.group(2)).strip().encode()
        raw = self._read_body()

        fields: dict[str, str] = {}
        filename = ""
        file_bytes = 0
        for part in raw.split(b"--" + boundary):
            if b"\r\n\r\n" not in part:
                continue
            head, body = part.split(b"\r\n\r\n", 1)
            body = body.rstrip(b"\r\n--")
            header = head.decode("utf-8", "replace")
            name_match = re.search(r'name="([^"]+)"', header)
            if not name_match:
                continue
            name = name_match.group(1)
            file_match = re.search(r'filename="([^"]*)"', header)
            if file_match:
                filename = file_match.group(1)
                file_bytes = len(body)
            else:
                fields[name] = body.decode("utf-8", "replace")

        if not filename:
            self._send(envelope(code=-1, message="no szPath part"))
            return

        record = {
            "dwJobId": 90000 + len(STATE["uploads"]) + 1,
            "szJobName": filename,
            "dwCreateDate": int(date.today().strftime("%Y%m%d")),
            "dwCreateTime": int(datetime.now().strftime("%H%M%S")),
            "dwCopies": int(fields.get("dwCopies") or 1),
            "szAttribe": ",".join(
                part for part, flag in (
                    ("color" if fields.get("dwColor") == "2" else "bw", True),
                    ("single" if fields.get("dwDuplex") == "1" else "",
                    fields.get("dwDuplex") != "1"),
                    ("vdup" if fields.get("dwDuplex") == "3" else "",
                    fields.get("dwDuplex") == "3"),
                ) if flag and part
            ),
            "szPaperDetail": json.dumps([{
                "dwPaperID": int(fields.get("dwPaperId") or -1),
                "dwBWPages": 0,
                "dwColorPages": 0,
            }]),
            "_bytes": file_bytes,
            "_fields": fields,
        }
        with LOCK:
            STATE["uploads"].append(record)
            STATE["print_jobs"].append(record)
        self._send(envelope(code=0, message="upload ok"))


def main():
    parser = argparse.ArgumentParser(description="Offline mock of the SUSTech PMS API")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--host", default="0.0.0.0")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[mock-pms] listening on http://{args.host}:{args.port}", flush=True)
    print("[mock-pms] emulator URL: http://10.0.2.2:%d" % args.port, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock-pms] bye", flush=True)


if __name__ == "__main__":
    main()
