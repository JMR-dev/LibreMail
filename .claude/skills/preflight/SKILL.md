---
name: preflight
description: Run LibreMail's fast CI gate locally (assembleDebug + testDebugUnitTest + compileDebugAndroidTestKotlin + lintDebug + ktlintCheck + detekt) plus the top-of-matrix emulator E2E (currently API 35 + 36) before pushing or opening a PR. Mirrors the merge gate and runs the two highest stable API levels via their Gradle Managed Devices; the rest of the multi-API matrix and the API 37 preview job stay CI-only. Use before treating a change as done.
---

# /preflight

Run the same fast checks CI enforces on every PR, in order, and report the outcome.

## Preconditions

- The Gradle daemon must run on **JDK 17–21**. AGP 9.2 fails on JDK 25+. If a build errors
  with a JDK/AGP version mismatch, check `java -version` / `JAVA_HOME` and point it at a 17–21
  JDK (e.g. Android Studio's bundled JBR) before retrying.
- PowerShell: invoke the wrapper as `.\gradlew`. Git Bash / the Bash tool: `./gradlew`.
- The final two E2E steps each boot an emulator through their own Gradle Managed Device, so the
  host needs hardware acceleration (WHPX on Windows, KVM on Linux, HVF on macOS). Gradle
  downloads the system image and boots/tears down each AVD itself — no manual emulator setup —
  but the first run per API level is slow while its image downloads. If the host has no
  accelerated emulator and a managed device cannot boot, report the E2E step as not run rather
  than treating the gate as green.

## Steps

Run these, stopping at the first failure:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:lintDebug
./gradlew :app:ktlintCheck :app:detekt
./gradlew :app:api35DebugAndroidTest   # top-of-matrix emulator E2E (2nd-highest stable level)
./gradlew :app:api36DebugAndroidTest   # top-of-matrix emulator E2E (highest stable level)
```

`compileDebugAndroidTestKotlin` compiles the `androidTest` source set — the E2E/instrumented
tests — without needing an emulator. `assembleDebug`, `testDebugUnitTest`, and `lintDebug` never
compile that source set, so a change that breaks it (e.g. an instrumented test calling a UI API
that just changed signature) passes the rest of this gate locally yet fails CI.

`ktlintCheck` + `detekt` are exactly what CI's **Static analysis** job runs — they cover the
`test`/`androidTest` source sets that `lintDebug` does not, so a style violation there fails the
merge gate even when the build and lint are green. Add `--continue` to any command (e.g.
`:app:ktlintCheck :app:detekt --continue`) to collect every failure in one pass instead of
stopping at the first.

`api35DebugAndroidTest` and `api36DebugAndroidTest` run last because they are the slowest: each
runs the full instrumented/E2E suite — on `api35`, then `api36`, the top two stable levels in
the E2E matrix — via its own Gradle Managed Device, which Gradle provisions, boots, and tears
down automatically. These two levels are the E2E that preflight runs locally and both must
pass; CI fans the same suite out across the whole API matrix (API 29–36) plus the API 37
preview job. API 37 has no Gradle Managed Device yet — its only published system image is the
nonstandard `android-37.0` / `google_apis_ps16k` pairing, which neither `ManagedVirtualDevice`'s
`apiLevel` (Int) nor `apiPreview` (codename) DSL resolves (see the comment above
`testOptions.managedDevices` in `app/build.gradle.kts`) — so CI's `e2e-preview` job provisions
it by hand instead, and there is no `api37DebugAndroidTest` task to run here. Keep
`api35DebugAndroidTest` / `api36DebugAndroidTest` in lockstep with the top of the managed-device
list in `app/build.gradle.kts` and the E2E matrix in `.github/workflows/ci.yml` — when a newer
API level is added there, run the new top two instead.

## Reporting

- If everything passes, say so plainly (e.g. "preflight green: build, unit tests, lint, ktlint, detekt, api35+api36 E2E").
- On failure, surface the actual Gradle error and point at the relevant report:
  - unit tests → `app/build/reports/tests/testDebugUnitTest/`
  - lint → `app/build/reports/lint-results-debug.html`
  - ktlint → `app/build/reports/ktlint/` (per source set, e.g. `ktlintTestSourceSetCheck/`)
  - detekt → `app/build/reports/detekt/`
  - E2E → `app/build/reports/androidTests/managedDevice/` (per-device HTML, e.g. `.../api35/`,
    `.../api36/`)
- Run only the **top two stable-API** levels here (`api35DebugAndroidTest` +
  `api36DebugAndroidTest`); the rest of the multi-API matrix and the API 37 preview job stay
  CI's job — API 37 has no Gradle Managed Device to run locally (see Steps above). If the host
  has no accelerated emulator and a managed device cannot boot, report that E2E could not run
  rather than treating the gate as green.
