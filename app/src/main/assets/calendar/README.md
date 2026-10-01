# Bundled academic calendar

The app has no reliable network path to the calendar, so the year's files ship
inside the APK. **Everything here is copied verbatim from
[`dumixthestpd/sustech-calendar`](https://github.com/dumixthestpd/sustech-calendar)
and verified byte-for-byte** — not retyped, not summarised, not taken from any
other copy of a calendar.

The timetable TIS serves is a *pattern* ("weeks 2, 4, 6 … on Thursday") and says
nothing about holidays. Whether a given date is a teaching day, and whether a
flushed meeting moved to a make-up day, is a calendar fact and only lives here.

## Pinned contents

From `main` at commit
[`e5e102c7`](https://github.com/dumixthestpd/sustech-calendar/commit/e5e102c74670eba53c187d3f98b3f97921dbf42a)
(2026-07-09, "Drop spurious Apr 4 spring extra_break (PDF: not a no-class day)").

| file | size | git blob sha1 |
|---|---|---|
| `upstream-README.md`* | 3341 | `42a05fc32481` |
| `2026/general.json` | 607 | `45d4f78e7a3d` |
| `2026/graduate.json` | 1907 | `de34f9bb1fb3` |
| `2026/undergraduate.json` | 1907 | `8571b5b9168f` |

\* the repo's root `README.md`, renamed so this file can sit beside it. It is the
only rename; every other path is the repo's own.

`2026/academic-calendar-2026.pdf` (727,624 B, blob `a49fe8ceb602`) is **not**
bundled: it is the human-readable source the JSONs are derived from, and nothing
in the app reads it. It is the one file in the repo's `2026/` deliberately left
out — do not treat its absence as a sign this directory came from somewhere
else. Fetch it from the repo if you want the source document on disk.

`<year>` is the year the **fall** term started, so `2026/` covers Feb-Jun 2026
(spring) and Sep 2026 - Jan 2027 (fall).

## Refresh

```bash
REF=e5e102c74670eba53c187d3f98b3f97921dbf42a   # or: main
DEST=app/src/main/assets/calendar
curl -fsS --retry 3 --retry-all-errors \
  -o "$DEST/upstream-README.md" \
  "https://raw.githubusercontent.com/dumixthestpd/sustech-calendar/$REF/README.md"
for f in general.json graduate.json undergraduate.json; do
  curl -fsS --retry 3 --retry-all-errors \
    -o "$DEST/2026/$f" \
    "https://raw.githubusercontent.com/dumixthestpd/sustech-calendar/$REF/2026/$f"
done
# the PDF is not packaged; fetch it separately if you want the source document
```

Use **curl**, never a Python HTTP client: `raw.githubusercontent.com` is
unreliable from urllib on this host (hangs for 180 s, then
`RemoteDisconnected`), while curl succeeds in under a second.

## Verify

Byte-identity is provable without trusting the transfer — compare each file's
git blob hash to the repo's tree:

```bash
python - <<'PY'
import hashlib, json, urllib.request
HEAD = "e5e102c74670eba53c187d3f98b3f97921dbf42a"
url = f"https://api.github.com/repos/dumixthestpd/sustech-calendar/git/trees/{HEAD}?recursive=1"
tree = {e["path"]: e["sha"] for e in json.load(urllib.request.urlopen(url))["tree"]}
for local, remote in {
    "upstream-README.md": "README.md",
    "2026/general.json": "2026/general.json",
    "2026/graduate.json": "2026/graduate.json",
    "2026/undergraduate.json": "2026/undergraduate.json",
}.items():
    b = open(local, "rb").read()
    got = hashlib.sha1(b"blob %d\0" % len(b) + b).hexdigest()
    print("MATCH" if got == tree[remote] else "DIFFER", local)
PY
```

## Do not source this from `~/.sustech_survival/cache/calendar/`

That cache is where `sustech_survival` stores what its own fetch returned, and on
2026-10-01 its `2026/` copy did **not** match the repo: one holiday instead of
six, neither made-up class day, no extra break, `total_teaching_weeks` 17 instead
of 16, and ETags shaped `"v1-<filename>"` that the repo never emits. The repo's
`main/2026/` is the reference; a cache is not.
