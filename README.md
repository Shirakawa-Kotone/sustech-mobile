# sustech-mobile

An Android app for SUSTech campus services. First subsystem: **联创 PMS cloud
print** (`pms.sustech.edu.cn`).

The goal is feature parity with the website, not a thin wrapper around one
screen: every page the print site offers has a native equivalent, and every
operation the site can perform on your queue is available from the phone.

## Features (PMS)

| Website page | App screen | Wire call |
|---|---|---|
| 云打印 upload | `UploadActivity` | `POST /api/client/CloudPrint/Upload` |
| 打印文档 (queued jobs) | `JobsFragment` + upload FAB | `GET /api/client/PrintJob/Get` |
| delete a queued job | row delete button | `POST /api/client/PrintJob/Del` |
| 扫描文档 | `ScanFragment` | `GET /api/client/Scan/Get` |
| delete a scan | row delete button | `POST /api/client/Scan/Del` |
| 使用记录 (usage report) | `UsageFragment` | `POST /api/client/Report/DetailPage` |
| 打印点 (stations) | `StationsFragment` | `GET /api/client/Station/GetList` |
| 打印点 group filter | spinner | `GET /api/client/Station/GetSrvList` |
| account / session state | `AccountFragment` | `POST /api/client/Auth/Check` |
| login | `LoginActivity`, `CasLoginActivity` | `Auth/GetAuthToken` → `Auth/PublicKey` → `Auth/Login` |

Print options on upload mirror the site's five controls: color, paper (A3/A4/
unspecified), single/duplex short/duplex long, page range, copies.

**Uploading is free.** Money is only taken when the file is collected at a
physical printer — the upload screen says so, because the website buries that
fact.

## Sign-in

Two paths, both matching what the site accepts:

1. **CAS WebView** (default button) — the real SUSTech sign-in page loads in a
   WebView, the PMS back end links your CAS identity to your print account,
   and the app takes only the resulting `OSESSIONID` cookie. No password ever
   reaches the app.
2. **Print-system account** — the site's own flow: `GetAuthToken` → RSA
   public key + nonce → `RSA/PKCS1Padding("password;nonce")` → `Login`.
   Identical to what `JSEncrypt` does in the browser, so the server cannot tell
   the difference. The password is used once and never stored.

Cookies persist in `SharedPreferences` (`sustech_mobile_session`) so the
session survives an app restart. `Auth/Check` decides at launch whether to go
straight to the tabs or show the login screen.

## Campus network

PMS answers `HTTP 403` + `Access forbidden, please contact administrator.`
from outside the campus network. The app detects exactly that response and
prints a "campus network required" message instead of a JSON decode crash.
There is no VPN inside the app — connect to the campus network (or the school
VPN) first.

## Build

Requires JDK 17+ and the Android SDK with platform 34 / build-tools 34.0.0.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug          # or: tools/build_apk.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (git-ignored) holds `sdk.dir`.

## Testing off campus

`tools/mock_pms.py` is a stand-in for the API that speaks the same wire format,
including the RSA login step (it decrypts with a real key, so a padding
mistake in the app fails there too).

```bash
python3 tools/mock_pms.py --port 8080
# in the app: login screen → 服务器设置 (or the 我的 tab) → server URL
#   emulator:        http://10.0.2.2:8080
#   physical device: http://<your-lan-ip>:8080
```

Cleartext HTTP is allowed only for `10.0.2.2`, `127.0.0.1` and `localhost`
(`res/xml/network_security_config.xml`); every other host, including the real
PMS, stays HTTPS-only.

To bake the mock server in instead of typing it on the device:

```bash
./gradlew assembleDebug -PpmsBaseUrl=http://10.0.2.2:8080
```

`tools/drive_ui.py` drives the app on a booted emulator and writes screenshots
to `tools/screenshots/`:

```bash
python3 tools/drive_ui.py --scenario pms-cas     # WebView sign-in path
python3 tools/drive_ui.py --scenario pms-smoke   # password sign-in, all tabs, delete a job
python3 tools/drive_ui.py --scenario pms-upload  # pick a file, upload, see it queued
```
The mock also serves a stand-in sign-in page at the print-page URL, which is
what makes `pms-cas` testable without a CAS server.

## Verified

Debug APK built, installed on an Android 14 (arm64) emulator and driven against
`tools/mock_pms.py` — 2026-09-13:

- `pms-cas` — WebView sign-in lands on the tabs; server log shows the print-page
  load followed by `Auth/Check`, i.e. the cookie taken out of the WebView is the
  one the API accepts.
- `pms-smoke` — password sign-in (`GetAuthToken` → `PublicKey` → `Login`), all
  five tabs populated (4 stations, 2 queued documents, 1 scan, usage rows,
  account name), and deleting a job took the queue from
  `[report-draft.pdf, thesis-appendix.pdf]` to `[thesis-appendix.pdf]`.
- `pms-upload` — picked a PDF through the system picker, uploaded it, and the
  queue then listed it (`thesis-appendix.pdf, smoke-upload.pdf`) with the
  options rendered back (`1 份 · 黑白 · 单面`).

Screenshots from that run are in `tools/screenshots/` (git-ignored).

Everything above ran against the mock. The real endpoints are the ones the two
sibling clients already use, but no request has gone to `pms.sustech.edu.cn`
from this app yet — that needs the campus network.

## Layout

```
app/src/main/java/edu/sustech/mobile/
  core/      AppConfig (server URL), App (singletons), CookieStore, Async
  pms/       Models.kt (records + wire constants), PmsApi.kt, PmsAuth.kt
  ui/        LoginActivity, CasLoginActivity, MainActivity, 5 tabs, upload
tools/mock_pms.py     offline API double
```

One rule carried over from the paired repos: the wire format lives in
`pms/`, and the UI never parses JSON.

## Relation to the other two clients

`edu.sustech.mobile.pms` is a port of the same endpoints already implemented in
Python (`sustech_survival.pms`, approach adopted from sustech-cli) and
TypeScript (`sustech-cli`). Field semantics — `JSJC`/`KEY` style naming aside,
`dwFrom = 0` meaning "all pages", `code = -1` meaning "already printed",
`dwProperty` capability bitmask, the 0x20/0x200/… fault flags — come from those
two and are documented at their use sites here.

## Not verified yet

Endpoints that exist on the website but have no known wire format, so they are
deliberately absent rather than guessed:

- downloading a scanned document (`Scan/Get` lists scans; no download call has
  ever been captured)
- account balance / 充值 (the account tab dumps whatever `Auth/Check` returns,
  which is how those fields get discovered)
- print-by-code / release-from-phone on the printer

Run the app on the campus network and check the 我的 tab's raw dump before
adding a screen for any of these.
