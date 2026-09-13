# SUSTech Mobile

An Android app for SUSTech services — one shell, one service at a time.

Today the app ships two of them, **Printing** (联创 PMS cloud print) and
**Courses & grades** (TIS timetable, courses, grades, exams), plus a **Today**
screen that answers "what is happening right now". Everything else SUSTech
offers is listed in the Services tab as planned work, not as a dead button.

**The UI is English only.** No screen has Chinese labels; the app's own copy
lives in `res/values/strings.xml`. Names that come *from* the university —
course titles, station names, room names — stay in the original language,
because they are data.

## Icon

The app icon and the in-app mark are the hand-drawn torch from
`sustech_survival/resources/logo.svg` — the same artwork the Electron app and
the web UI use — recoloured from its orange `#ed7005` to the wordmark green
`#004851` so the phone icon matches the lockup. It lives in
`res/drawable/ic_torch.xml` (adaptive icon foreground) and
`res/drawable/ic_torch_mark.xml` (used on the sign-in screen), both generated
from the source SVG path data, both scaled to 67% of the mark's original size.

## The shell

The bottom bar is fixed at three destinations (Today, Services, Account).
Services are **not** tabs: a new service would push the bar past its limit and
reshuffle every screen. They open in `ServiceActivity`, so adding a service is
one entry in `service/Services.kt` plus its root fragment — the shell never
changes.

| Service | State | Screens |
|---|---|---|
| Printing (PMS) | available | Print queue (+ upload), Stations, Scans, Usage |
| Courses & grades (TIS) | available | This week, Courses, Grades, Exams |
| Blackboard, Library, Venue booking, Campus transit, Course reviews, Papers, Faculty, Exchange programs, Language help, Campus Wi-Fi | planned | — |

Printing covers what the print website covers: cloud-print upload with all five
print options (color, paper, duplex, page range, copies), the queued-document
list with per-job delete, scanned documents with per-document delete, the
paginated usage report with date range and type filter, the station list with
its server-group filter, and account/session state.

Courses & grades covers the TIS reads that matter on a phone: the current
teaching week's timetable, the term's enrolled courses, every posted grade with
the credit-weighted GPA, and the exam schedule. Course selection, bidding and
evaluation are **not** in the app — they are irreversible writes and stay on the
website.

## Sign-in — one account, once

There is exactly one sign-in screen and exactly one credential pair: the school
account. It is stored on the device the first time it is entered and reused for
every service, forever after — the same contract as `sustech_survival`'s
`credentials.txt`.

- **No per-service sign-in.** Screens call `Session.ensureX()`; a live session is
  reused, an expired one is re-established silently from the stored account.
- **A network failure is never a credential failure.** `Session.signIn()`
  classifies the result: `ACCEPTED` (some service took the account), `REFUSED`
  (CAS or the print login answered and rejected it) or `UNREACHABLE` (off
  campus, offline, 5xx). Printing is campus-only, so its 403 off campus lands in
  `UNREACHABLE`: the user gets in with an explanation rather than being told
  their password is wrong. Only an explicit refusal keeps them on the sign-in
  screen.
- **No browser sign-in.** There is no WebView in the app. TIS refuses mobile
  browser sign-ins, and a native client has no reason to show someone else's
  login page. CAS is implemented natively in `sso/CasLogin.kt`, ported from the
  Python `CASAuthorizer`: scrape the `execution` token → POST
  `username/password/execution/_eventId=submit` → follow the ticket →
  cookie exchange, with the desktop user agent and `X-Requested-With` header TIS
  expects.
- **Printing** additionally accepts the print system's own RSA flow
  (`Auth/GetAuthToken` → `Auth/PublicKey` → `RSA/PKCS1Padding("password;nonce")` →
  `Auth/Login`), which the app runs with the same account.
- **Expiry is invisible.** Every call that comes back "session gone" re-runs the
  matching sign-in with the stored account and retries itself once
  (`PmsApi`/`TisApi` `withRelogin`), so nothing ever asks the user to sign in
  again — only a stored account the server *refuses* returns to the login screen.

The account lives in the app's private `SharedPreferences`
(`sustech_mobile_creds`), with `allowBackup=false` so it stays out of cloud
backups. It is never logged, never rendered, and never sent anywhere except the
university's own sign-in endpoints. Session cookies live separately in
`sustech_mobile_session`, host-scoped, so the printing and courses sessions
coexist and survive a restart. The Account tab shows the stored account and has
**Forget account** (clears both).

## Campus network

PMS answers `HTTP 403` + `Access forbidden, please contact administrator.` from
outside the campus network, and the app says so instead of failing with a JSON
error. TIS and the public weather/AQI APIs work from anywhere.

## Build

Requires JDK 17+ and the Android SDK with platform 34 / build-tools 34.0.0.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug          # or: tools/build_apk.sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (git-ignored) holds `sdk.dir`. A build can bake in a
different server with `-PpmsBaseUrl=...`; the login screen and the Account tab
can also change it at runtime.

## Testing

Print tests never need the campus network or a VPN: the app is pointed at
`tools/mock_pms.py` through the emulator's host alias, so the entire print flow is
exercised locally. The one campus-dependent fact is the 403 the *real* host answers
off campus, which the app reports as "Printing needs the campus network".
`tools/mock_pms.py` is a stand-in for the print API that speaks the same wire
format, including the RSA login step (it decrypts with a real key, so a padding
mistake in the app fails there too), and serves a stand-in sign-in page so the
WebView path is testable offline.

```bash
python3 tools/mock_pms.py --port 8080
python3 tools/inject_session.py --mock     # point the app at it (emulator alias)
python3 tools/drive_ui.py --scenario pms-smoke
```

`tools/drive_ui.py` drives the app through `uiautomator` and asserts on real row
counts, writing screenshots to `tools/screenshots/`:

| Scenario | What it proves |
|---|---|
| `shell` | Today renders, the catalog lists both services and the planned ones, Account shows per-service sessions |
| `pms-smoke` | the app signs in by itself, queue with upload button, delete removes a row, stations / scans / usage all populate |
| `pms-upload` | file picker → upload → the file reappears in the queue |
| `tis-live` | the real TIS: week header, classes, courses, grades (needs an injected session) |
| `theme` | the bottom bar actually paints in both device themes — pixel check, because an invisible item is still "present" to uiautomator |

`tools/inject_session.py --creds` copies the school account in (from the same
credentials file the Python client uses — the value is never printed). The app
then performs its own CAS and RSA sign-ins, so what gets verified is the app's
auto-login, not a session handed to it. `--tis` / `--pms-session` exist only for
isolating a sign-in problem.

Cleartext HTTP is allowed only for the emulator host alias and localhost
(`res/xml/network_security_config.xml`); every other host, including PMS and
TIS, stays HTTPS-only.

## Verified

Debug APK installed on an Android 14 (arm64) emulator, 2026-09-13:

- **Printing** against the mock: queue of 2 documents → delete → 1 (row count
  asserted), 4 stations, 1 scan, 20 usage rows, and an upload that came back
  listed in the queue.
- **Courses & grades** against the **real TIS** with a live session:
  `Week 1 · 2026Fall` with 6 classes on the timetable, 7 enrolled courses with
  teachers/rooms/week ranges, and 7 grade rows on screen (46 total; the GPA
  header is computed from all of them). Exams is empty because TIS has not
  published them.
- **Shell**: Today renders with the week number, the catalog shows both
  services plus the planned ones, Account shows the printing and TIS sessions
  separately.

Two real bugs the live run caught, both now fixed in `tis/TisModels.kt`: the
room was read from the teacher's bracket (the timetable's bracket order is
teacher, class group, then weeks/room/periods), and the single-week endpoint
sends no `ZC` week bitmap, so the week range now falls back to the row's own
`1-16周` label.

## Not implemented

- TIS writes: course selection, bidding, evaluation, venue booking.
- Blackboard, Library, transit, NCES, papers, faculty, exchange programs,
  language help, campus Wi-Fi — listed as planned in the catalog.
- Downloading a scan (the list exists; no download call has ever been captured
  from the website) and account balance/充值 (the wire fields are unknown; the
  Account tab shows whatever `Auth/Check` returns).

## Layout

```
app/src/main/java/edu/sustech/mobile/
  core/      ApiException, AppConfig (server URL), App (singletons), CookieStore, Hosts, Async
  service/   ServiceModule + Services catalog (the shell's only extension point)
  pms/       wire: Models (records + codes), PmsApi (8 endpoints), PmsAuth (RSA)
  tis/       wire: ClassEntry / CourseRow / GradeRecord / ExamRecord, TisApi, GPA table
  weather/   campus weather + air quality (both public)
  ui/        shell (Login, Main, Service, WebLogin), Today, Services, Account, NotImpl
  ui/pms/    PmsFragment + Print / Stations / Scans / Usage tabs, UploadActivity
  ui/tis/    TisFragment + This week / Courses / Grades / Exams tabs
tools/mock_pms.py       offline print API double
tools/inject_session.py put a session (or the mock server) into the installed app
tools/drive_ui.py       uiautomator harness, one scenario per user journey
```

The wire layers never touch UI copy: labels are resource ids resolved in the UI,
and the wire layer speaks codes (`StationState`, `Duplex`, `ReportType`).

## Relation to the other two clients

Field semantics were ported from the Python client (`sustech_survival`, which
owns TIS) and the TypeScript fork (`sustech-cli`, which owns the print wire,
approach adopted from sustech-cli). The GPA conversion table, the period-time
table, the "current term has no grades yet, read the timetable instead" rule,
`dwFrom = 0` meaning all pages, and `code = -1` meaning "already printed" all
come from those two, and are documented at their use sites here.

The planned-service list mirrors their submodules, so all three clients share
one roadmap.
