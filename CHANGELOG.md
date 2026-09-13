# Changelog

## 0.1.0 — 2026-09-13

- New project: Android client for SUSTech campus services, first subsystem
  PMS 联创云打印.
- Feature parity with the print website: cloud-print upload with all five
  print options, queued print documents list + per-job delete, scanned
  documents list + per-document delete, paginated usage report with date
  range and type filter, print-point list with server-group filter, account
  and session state.
- Two sign-in paths: CAS in a WebView (cookie only, no password) and the
  site's own RSA login flow (`Auth/GetAuthToken` → `Auth/PublicKey` →
  `PKCS1v15(password;nonce)` → `Auth/Login`).
- Session cookies persisted; off-campus `403 Access forbidden` reported as a
  campus-network message instead of a JSON parse failure.
- `tools/mock_pms.py`: offline API double with a real RSA handshake, so the
  app is testable without the campus network.
- Endpoint set and field semantics ported from `sustech_survival.pms` (Python)
  and `sustech-cli` (TypeScript) — approach adopted from sustech-cli.
- Debug build-time server override (`-PpmsBaseUrl=...`) so a test APK can point
  at a LAN or mock server without a code change.
- `tools/drive_ui.py`: uiautomator-driven emulator harness with three scenarios
  (WebView sign-in, password sign-in + all tabs + delete, upload round trip).
  Screenshots are written to `tools/screenshots/`.
- Mock server also serves a stand-in sign-in page at the print-page URL, so the
  WebView login path is testable without a CAS server.
- Verified on an Android 14 emulator against the mock: CAS sign-in, password
  sign-in, all five tabs, job deletion (queue count asserted), and a full file
  upload that came back listed in the print queue.
