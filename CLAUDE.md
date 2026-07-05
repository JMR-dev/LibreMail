# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

LibreMail is a GPL-3.0 Android email client (Kotlin, Jetpack Compose, Material 3).
See `@README.md` for OAuth/account setup (Gmail, Outlook), the tech stack, and the
offline-first architecture — this file only covers what isn't obvious from the code.

## Build, test, lint

Use a **JDK 17–21** for the Gradle daemon. AGP 9.2 does **not** support JDK 25 — if
`JAVA_HOME` points at 25+, builds fail. Commands (PowerShell: use `.\gradlew`):

```bash
./gradlew :app:assembleDebug        # build debug APK
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:lintDebug            # Android lint
./gradlew :app:ktlintCheck :app:detekt   # static analysis (CI's "Static analysis" gate)
./gradlew :app:jacocoTestReport     # JVM unit-test coverage (XML+HTML under app/build/reports/jacoco/)
# single unit test:
./gradlew :app:testDebugUnitTest --tests "org.libremail.data.SomeClassTest"
```

E2E/instrumented tests need a booted emulator: `./gradlew :app:connectedDebugAndroidTest`,
or via Gradle Managed Devices `./gradlew e2eGroupDebugAndroidTest` (whole matrix) /
`./gradlew api29DebugAndroidTest` (one API level). The managed-device list in
`app/build.gradle.kts` must stay in lockstep with the E2E matrix in `.github/workflows/ci.yml`.

**Before treating a change as done**, run the fast CI gate: `assembleDebug` +
`testDebugUnitTest` + `compileDebugAndroidTestKotlin` + `lintDebug` + `ktlintCheck` +
`detekt` + the local emulator E2E — the instrumented test class(es) you changed via
`python3 .claude/skills/preflight/local_instrumented.py <classes>` + the API 37 preview E2E via
`python3 .claude/skills/preflight/api37_e2e.py` (the `/preflight` skill does all of this).
`compileDebugAndroidTestKotlin` compiles the `androidTest` source set
that the static part of the gate skips, catching E2E/instrumented-test compile errors before
they surface only in CI. `ktlintCheck`/`detekt` cover the `test`/`androidTest` source sets that
`lintDebug` skips, so they catch style violations that would otherwise fail CI's Static analysis
gate. The local E2E does **not** use Gradle Managed Devices (`apiXXDebugAndroidTest`): GMD's
snapshot step fails locally under the AEHD 2.2 hypervisor. Instead `local_instrumented.py`
cold-boots one existing AVD by hand (no GMD, no snapshot) and runs `connectedDebugAndroidTest`
filtered to the class(es) you pass — run the ones you changed; the full ~114-test suite wedges
mid-run locally. API 37 (preview) has no Gradle Managed Device — its
only image is the nonstandard `android-37.0` / `google_apis_ps16k` pairing (see the comment above
`testOptions.managedDevices` in `app/build.gradle.kts`) — so `api37_e2e.py` hand-provisions it,
mirroring CI's `e2e-preview` job (same image + emulator flags, except it uses host-GPU
`-gpu auto-no-window` locally vs CI's headless `-gpu swiftshader_indirect`), boots it headless,
runs `connectedDebugAndroidTest`, and tears it down. Both local E2E steps are required; the
full multi-API matrix (API 29–37) stays CI's job. Emulators need a free hardware
hypervisor (VT-x/WHPX), so shut down VirtualBox/other VMs first or the AVD hangs at 0% CPU.

**Dev scripts: prefer Python (stdlib).** Auxiliary dev / CI-helper scripts — like the preflight
E2E runners (`.claude/skills/preflight/local_instrumented.py`, `api37_e2e.py`) and
`.claude/hooks/check-spdx.py` — are written in **Python 3, standard library only**, for
cross-platform portability. The primary dev box is Windows, where bash-only helpers need Git Bash
and hit gaps (`jq` missing, `taskkill` vs `kill`, path/quoting). **Do not add new bash-only
(`.sh`) or PowerShell-only dev scripts**; write new helpers in Python (or extend the existing
ones). Scope is auxiliary tooling only — product code stays Kotlin and Gradle stays Kotlin DSL.

## Build-config gotchas

- **Built-in Kotlin (AGP 9.x).** Kotlin compilation is handled by AGP's built-in Kotlin;
  the Kotlin version (2.4.0) is pinned via the root `build.gradle.kts` buildscript classpath.
  **Never apply the `org.jetbrains.kotlin.android` plugin** — it throws a ClassCastException
  against AGP 9's DSL. (The `kotlin-android` alias in `libs.versions.toml` exists but must
  not be used.) `libs.versions.toml` still supplies all *library* versions.
- **KSP, not KAPT** for all annotation processing (Hilt, Room).
- Room schemas are exported to `app/schemas` and validated by migration tests — commit
  schema changes.
- OAuth client IDs come from `secrets.properties` (git-ignored) via `BuildConfig`; the
  build works without it (empty/placeholder values).

## Code conventions

- Sources live under `app/src/{main,test,androidTest}/kotlin/`; package root `org.libremail`
  (applicationId `org.libremail.app`).
- **Every source file starts with** `// SPDX-License-Identifier: GPL-3.0-or-later` (or the
  `<!-- ... -->` form for XML/Markdown). All 117 current `.kt` files follow this.
- `kotlin.code.style=official`.

## Testing

JVM unit tests use JUnit4 + `kotlin.test`, **Turbine** for `Flow`, **MockK** for mocks,
**GreenMail** for a real in-process IMAP/SMTP server, and coroutines-test. `org.json` is
pulled in as a real dependency for unit tests because `android.jar`'s version is a no-op stub.

## Definition of done

A change is not done until it ships with passing **unit tests** and **E2E/instrumented tests**
that exercise the new or changed behaviour. Writing and committing that E2E/instrumented test
is a required part of every task — and the test must actually **run and pass**, not merely
compile: preflight runs the changed instrumented test class(es) on a locally cold-booted
emulator via `local_instrumented.py` (no Gradle Managed Devices — they fail locally) plus the
API 37 preview via `api37_e2e.py` (hand-provisioned, mirroring CI's `e2e-preview` job) — and both
must be green before the change is done. CI then runs the full multi-API matrix plus the API 37
preview job.

No **app source-code** change is complete without **appropriate logging** added at its key
points — lifecycle transitions, error/fallback paths, significant state changes — so behaviour is
diagnosable from a user's debug report. Log through the `AppLog` facade
(`org.libremail.reporting.AppLog`), which mirrors to Logcat **and** the in-memory
`RingLogBuffer` that feeds a `DebugReport` — never raw `android.util.Log` (a detekt guard forbids
it). Logging must be **PII-free**: never log emails, server hosts, message content, or
credentials — use `accountLogRef(account.id)` for account references; throwables passed to
`AppLog` are auto-scrubbed. This applies to app source changes; pure test/config/doc changes
don't need new logging.

## Repo etiquette

- Branch off `main`; branch names like `feat-…` / `fix-…`. PRs target `main` and must pass
  the `CI passed` gate.
- **Conventional Commits** for commit subjects and PR titles: `type(scope): summary`
  (`feat`, `fix`, `chore`, …), matching existing history.
