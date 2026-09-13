# Changelog

## 0.3.0 — 2026-09-13

- **Credentials-only sign-in: one account, entered once.** The app stores the
  school account on first use and reuses it for every service; sessions renew
  themselves silently. Approval: user directive — one cred, auto-login forever,
  like the Python client.
- **The browser sign-in is gone.** `WebLoginActivity` and its layout are
  deleted, and no screen opens a WebView. TIS refuses mobile browser sign-ins,
  and the native client needs no browser, so CAS is now implemented directly in
  `sso/CasLogin.kt` (execution token → credential POST → ticket → cookie
  exchange, desktop user agent + XHR header) — approach adopted from
  `sustech_survival`'s `CASAuthorizer`.
- **Invisible expiry.** `PmsApi` and `TisApi` retry a call once after
  re-authenticating from the stored account (`withRelogin`), so an expired
  session never surfaces as a prompt.
- **The sign-in screen mentions no service.** It asks for a student ID and a
  password, says the account is stored on the phone and reused, and nothing
  else; the per-service copy and the print-server field moved out.
- Account tab reworked around the stored account: identity, per-service session
  state, **Forget account** (credentials + sessions), and the test-server
  override.
- Harness: `inject_session.py --creds` copies the school account in (never
  printed) so the app signs in by itself; `drive_ui.py` waits for that silent
  sign-in instead of treating "the login screen is up" as "unconfigured".
- Icon: the torch mark is scaled to 67% of its previous size, in both the
  launcher icon and the sign-in mark. Approval: user request.

## 0.2.0 — 2026-09-13

- **Multi-service shell.** The app is no longer a print client: a service
  catalog (`service/Services.kt`) plus a generic `ServiceActivity` host means a
  new SUSTech service is one entry and one root fragment. The bottom bar is
  fixed at Today / Services / Account, so the shell does not change as the
  catalog grows.
- **New service: courses & grades (TIS).** This week's timetable (week number
  from TIS itself), the term's enrolled courses grouped one row per course, all
  posted grades with the credit-weighted GPA, and the exam schedule. Sign-in is
  the school page in a WebView; writes (selection, bidding, evaluation) are not
  in the app.
- **New screen: Today.** Week number, today's classes, campus weather and air
  quality, next exam. Weather/AQI are public APIs and work off campus; the rest
  says so when TIS is not signed in.
- **App icon is the project's own torch mark.** `res/drawable/ic_torch.xml` and
  `ic_torch_mark.xml` are generated from `sustech_survival/resources/logo.svg`
  (the artwork the Electron app and the web UI already ship) with the fill
  changed from `#ed7005` to the wordmark green `#004851`; the placeholder
  printer glyph is gone. Approval: user request to use their torch, recoloured.
- **English-only UI.** The Chinese default strings file is gone, the app is
  named SUSTech Mobile (no Chinese name), and every label comes from
  `res/values/strings.xml`. Derived labels (idle/busy/fault, duplex, usage
  type, settle type) moved out of the wire layer into resources, so `pms/` and
  `tis/` now speak codes only.
- One WebView sign-in for every service (`WebLoginActivity`), parameterized by
  entry URL and the cookie that proves the landing; the print-only RSA account
  login stays in `PmsAuth`.
- Session probing is per service: the launcher advances if any stored session
  still works, and the Account tab reports the printing and TIS sessions
  separately.
- `tools/inject_session.py`: puts the mock server URL or a live TIS session into
  the installed app, so screens can be verified without typing credentials into
  a WebView. Cookie values are never printed.
- `tools/drive_ui.py` rewritten for the new navigation with four scenarios
  (shell, pms-smoke, pms-upload, tis-live), row-count assertions instead of
  "the screen looked right", and a wait for network-backed lists.
- Fixed two real bugs found by running against the live service: the timetable
  room was read out of the teacher's bracket, and the single-week endpoint's
  missing `ZC` bitmap left the week range blank. Teachers are now comma-spaced
  and de-duplicated, and repeated lab meetings collapse to one.
- Service catalog driven from the same submodule list the Python and TypeScript
  clients use — approach adopted from sustech-cli.

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
- Verified on an Android 14 emulator against the mock: CAS sign-in, password
  sign-in, all five tabs, job deletion (queue count asserted), and a full file
  upload that came back listed in the print queue.
