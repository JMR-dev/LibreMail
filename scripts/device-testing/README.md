<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# LibreMail device-testing perf harness

A cross-platform, **standard-library-only** Python tool that replicates LibreMail's
on-device performance-test scenarios and logging capture. It codifies the methodology that
was first run by hand (2026-07-05, Pixel 10 Pro XL) and written up in
`scratchpad/perf/perf_summary.md`, `causation-report.md`, and `ab-run/timing-tables.md`.

Everything runs against **LibreMail only** plus read-only system-log/settings/dumpsys
collection, behind hard device-safety guardrails (see [Device safety](#device-safety)).

## Requirements

- **Python 3.8+** (standard library only — no `pip install`, no third-party deps).
- **`adb`** on `PATH` (or pass `--adb /path/to/adb`).
- A connected device with:
  - **LibreMail installed** as a **debuggable** build (the cache-clear uses `run-as`, which
    only works on debuggable APKs), and
  - **at least one account signed in** with some **not-yet-cached** messages in the inbox
    (message bodies are fetched on first open), and
  - the screen **unlocked** (the harness keeps it awake during a run and guards every step
    against the keyguard, but it cannot get you *past* a secure lock screen).

No build step. Run it straight from the repo.

## Usage

```bash
python scripts/device-testing/perf_harness.py <scenario> [options]
```

Scenarios (each independently selectable):

| Scenario         | What it does |
|------------------|--------------|
| `cold-open`      | Force-stop LibreMail, clear **only** its `cache/`, `am start -W` ×N, parse `TotalTime`/`WaitTime`. |
| `message-open`   | Open N distinct **uncached** messages one at a time; time spinner→content from the breadcrumbs. |
| `back-nav`       | Time reader→mailbox back transitions ×N (dump-latency-bound; see caveat in the report). |
| `prefetch-ab`    | Run `message-open` under **Fetch all on Wi-Fi** (prefetch ON) vs **Always on-demand** (prefetch OFF), cache cleared between conditions. |
| `cross-provider` | Open N messages from the (unified) inbox and tabulate per provider (`imap:…` vs `outlook:…`) from the breadcrumb account refs. |
| `cold-fetch-ab`  | Pause-hook cold-vs-warm A/B (**debug build only**). Pre-arm the FETCH_GATE halt, detect sign-in, confirm the halt, let headers sync, measure genuine **cold** opens, `resume`, then measure **warm** (cached) re-opens — reports the delta, the `connect=0ms` reuse proof, and any throttle signature. |

Common options:

| Option | Default | Meaning |
|--------|---------|---------|
| `--serial <id>`   | auto (if exactly one device) | choose the device |
| `-n, --count <N>` | per-scenario | samples / runs (per condition for `prefetch-ab`) |
| `--out <dir>`     | `./device-perf-runs` | output root; a timestamped subdir is created per run |
| `--package <pkg>` | `org.libremail.app` | target package |
| `--component <c>` | `org.libremail.app/org.libremail.MainActivity` | launcher component |
| `--adb <path>`    | `adb` | path to the adb executable |
| `--dry-run`       | off | **print the exact command plan without changing device state** |

**Always start with `--dry-run`** to review the command plan a scenario will issue:

```bash
python scripts/device-testing/perf_harness.py prefetch-ab --dry-run
python scripts/device-testing/perf_harness.py cold-open -n 5 --serial 5C310DLCQ000G3
```

## What each scenario measures

Timing comes **primarily from the on-device breadcrumbs** (PII-free), with uiautomator used
only as a "content is ready" signal so the driver knows when to move on:

- `MailReader: openMessage <acctRef> folder=<label> fetchedBody=<bool> took=<ms>ms`
  — end-to-end reader open. `fetchedBody=true` ⇒ a real network body fetch (uncached).
- `Reader: reader ready took=<ms>ms html=<bool> inline=<n>` — spinner→content.
- `ImapPerf: <op> connect=<ms>ms work=<ms>ms live=<N>` and
  `ImapPerf: body-fetch select=<ms>ms body=<ms>ms flag=<ms>ms rfc822=<n>B chars=<n> att=<n>`
  — connection + phase split; `body KB/s` is `rfc822 / body_ms`.
- `MailBackfiller: backfill … pages=<n> complete=<bool>` / `backfill slice…` — backfill activity.

`cold-open` instead parses `am start -W`'s `TotalTime` / `WaitTime`.

## Cold-fetch A/B (pause hook) — `cold-fetch-ab`

Folds in the proven 2026-07-06 cold-vs-warm methodology (issue #405) as a first-class,
reproducible scenario so the connection-reuse / throttle A/B can be re-run on demand to catch
perf regressions. The flow:

1. **Pre-arm the halt** — broadcast `FETCH_GATE pause backfill,prefetch` *before* sign-in, so
   proactive body fetch is gated the instant sync starts (bodies stay uncached). The receiver
   echoes its state back as ordered-broadcast result data (`data="paused=[backfill,prefetch]"`),
   which the harness parses for a race-free read-back.
2. **Detect sign-in** — tail the log for `MailSyncer: sync all: N accounts` (sign-in is manual;
   OAuth can’t be automated, so add the account on the device when prompted).
3. **Confirm the halt** — wait for `prefetch skipped: fetch-gate paused` (proof the gate held).
4. **Wait for header sync** — until uncached message rows appear.
5. **Measure cold opens** — genuine uncached opens (`ImapPerf` connect/work + `MailReader
   openMessage fetchedBody=true`).
6. **Resume + measure warm opens** — `FETCH_GATE resume`, then re-open the same messages (now
   cached) for the A/B.

It reports the cold-vs-warm median delta, the **`connect=0ms`** connection-reuse proof, and any
**server-side throttle signature** (a very high cold IMAP `work`). The gate is **always cleared
on exit** (even on error) — the scenario never leaves a device with fetch paused.

> **Needs a debug build.** The `FETCH_GATE` pause hook (`FetchGateReceiver` / `DebugFetchGate`,
> issues #393/#395) is compiled **only** into `src/debug` and R8-stripped from release, so this
> scenario requires the **debug** APK. A full on-device run is a follow-up; the mocked unit
> tests + `--dry-run` are the automated validation.

## Output

Each run writes a timestamped directory under `--out`:

```
device-perf-runs/20260706-131612-cold-open/
├── timing-tables.md   # per-scenario tables + aggregates (mirrors the manual timing-tables.md)
├── session-raw.log    # the full `adb logcat -b all -v threadtime` stream for the run
├── perf-extract.log   # the ImapPerf|MailReader|Reader|MailBackfiller subset of the raw log
└── driver.log         # what the harness did, step by step
```

## Device safety

Every device call goes through a guarded `adb` wrapper (`adb.py`). Two independent layers
mean a dangerous command **cannot be constructed**:

- **Allow-list** of adb subcommands: `devices`, `get-state`, `install`, `shell`, `logcat`,
  `wait-for-device`, `start-server`. Anything else (`uninstall`, `root`, `remount`,
  `reboot`, `disable-verity`, `emu`, `push`, `pull`, …) is refused.
- **Deny-list + assertions** on every `shell` command: no `pm clear` / `pm uninstall`, no
  reboot/remount/root/verity/factory-reset, **no touching the app's `databases/` / `files/`
  / `shared_prefs/` / `datastore/`**, no output redirects, and `run-as` / `am force-stop` /
  `am start` are constrained to the target package.

The **only** sanctioned mutation of app state is clearing LibreMail's own **`cache/`**
(`run-as org.libremail.app sh -c 'rm -rf cache/*'`) — an exact-match allow-list; any other
`rm`/`mv`/`dd`/… is refused. There is **no** `pm clear`, uninstall, or data wipe anywhere.

The screen is kept awake (`svc power stayon true` + `KEYCODE_WAKEUP`) for the run and
restored afterwards, and every uiautomator/input step is guarded against the keyguard and
against a foreign app being in the foreground — a sample taken against either is **skipped**,
not measured (the manual run hit exactly these: a lock-screen dump and a deskclock alarm).

## Tests

Pure-logic modules (the breadcrumb parser, the uiautomator parser, the safety guardrails,
the report renderers) are unit-tested with the standard-library `unittest` against the
**real captures** from the manual run:

```bash
python -m unittest discover -s scripts/device-testing/tests -p "test_*.py"
```

`tests/fixtures/perf-extract-sample.log` is a verbatim slice of the manual run's
`perf-extract-ALL.log`, so the parser tests assert the harness reproduces the exact figures
in the hand-written `timing-tables.md` (e.g. Gmail A1: `took=31227 ms`, `rfc822=60457 B`,
`4.2 KB/s`; Outlook O1: `took=2934 ms`, `139.5 KB/s`). The UI fixtures include the reader,
plus the lockscreen and deskclock-alarm negatives the keyguard/foreground guards must catch.

## Validated vs. needs the live run

**Validated offline** (by the unit tests, no device):

- Breadcrumb parsing + open-correlation reproduce the manual `timing-tables.md` figures.
- The safety guardrails accept the known-good commands and refuse every forbidden one.
- Screen recognition (mailbox rows + cached flag, reader, lockscreen, foreign app).
- The report renders the same tables/aggregates as the manual write-up.
- **Pause-hook helpers** (`fetchgate.py`): the `FETCH_GATE` pause/resume/query broadcast is the
  one the safety wrapper accepts, and the ordered-broadcast read-back (`paused=[…]`) parses.
- **Cold-fetch A/B** flow: sign-in / halt-confirm detection, cold/warm open correlation, the
  `connect=0ms` reuse proof + throttle signature, and the **always-resume** restore on error.
- `--dry-run` emits the correct command plan for all six scenarios.

**Needs the first monitored live run** (a device makes the state real):

- End-to-end timing capture on hardware (streamed logcat → per-sample breadcrumb tailing).
- **On-device `cold-fetch-ab` run** (needs a **debug** APK for the `FETCH_GATE` hook): pre-arm →
  manual sign-in → cold/warm A/B. The pause/resume broadcasts, read-back parsing, and flow are
  validated offline; the live run confirms the timings on real hardware.
- **Settings-screen navigation for `prefetch-ab`.** No uiautomator dump of the settings
  screen was captured in the manual run, so `set_fetch_policy` navigates by the on-screen
  option text (`"Fetch all on Wi-Fi"`, `"Always on-demand"`, from `res/values/strings.xml`)
  with a scroll fallback. The bottom-nav "Settings" tap target is confirmed from
  `ui_mailbox.xml`; the option rows themselves need one live confirmation.
- Row selection under a live, scrolling list and the auto-lock recovery path.

## Notes / design decisions

- **Uncached opens.** Message bodies live in the Room DB (`libremail.db`), **not** in
  `cache/`, so bodies can't be force-uncached without touching `databases/` (forbidden).
  The harness therefore opens **naturally-uncached** messages and verifies each was a real
  network fetch via the `fetchedBody=true` breadcrumb — exactly as the manual run did.
- **Cross-provider** uses the unified inbox: a single mixed pass yields both providers, and
  the harness buckets rows by the breadcrumb account ref (`imap:…` vs `outlook:…`) — no
  account switching required.
- **Back-nav** timings are dominated by the ~2.5–3 s uiautomator-dump latency floor; the
  report labels them accordingly (true in-app back is sub-second and not resolvable via adb
  UI polling under load).
- **Portability (subsumes #392).** The UI dump is **file-based** (`uiautomator dump
  /sdcard/window_dump.xml` + `cat`), not `dump /dev/tty`, which interleaves a status banner
  with the XML and is unreliable across devices/hosts. All adb output is decoded as **UTF-8**
  (and the harness forces `PYTHONUTF8`/UTF-8 console I/O) so non-ASCII sender/subject text
  doesn’t mojibake or crash on a Windows cp1252 console. Reader-open readiness is taken from
  the `MailReader openMessage` **breadcrumb** (authoritative) rather than UI polling alone, and
  row selection **skips non-message rows** (a tappable container with no sender/subject label).
- Dev-script convention: Python 3, standard library only, cross-platform (Windows-primary),
  matching `.claude/skills/preflight/*.py`.
