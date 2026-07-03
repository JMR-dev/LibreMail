---
name: preflight
description: Run LibreMail's fast CI gate locally (assembleDebug + testDebugUnitTest + compileDebugAndroidTestKotlin + lintDebug + ktlintCheck + detekt) plus the latest-API-level emulator E2E before pushing or opening a PR. Mirrors the merge gate and runs the highest-API E2E via its Gradle Managed Device; the full multi-API matrix stays CI-only. Use before treating a change as done.
---

# /preflight

Run the same fast checks CI enforces on every PR, in order, and report the outcome.

## Preconditions

- The Gradle daemon must run on **JDK 17–21**. AGP 9.2 fails on JDK 25+. If a build errors
  with a JDK/AGP version mismatch, check `java -version` / `JAVA_HOME` and point it at a 17–21
  JDK (e.g. Android Studio's bundled JBR) before retrying.
- PowerShell: invoke the wrapper as `.\gradlew`. Git Bash / the Bash tool: `./gradlew`.
- The final E2E step boots an emulator through a Gradle Managed Device, so the host needs
  hardware acceleration (WHPX on Windows, KVM on Linux, HVF on macOS). Gradle downloads the
  system image and boots/tears down the AVD itself — no manual emulator setup — but the first
  run is slow while the image downloads. If the host has no accelerated emulator and the
  managed device cannot boot, report the E2E step as not run rather than treating the gate as
  green.

## Steps

Run these, stopping at the first failure:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:lintDebug
./gradlew :app:ktlintCheck :app:detekt
./gradlew :app:api36DebugAndroidTest   # latest-API emulator E2E (highest level in the matrix)
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

`api36DebugAndroidTest` is last because it is the slowest: it runs the full instrumented/E2E
suite on `api36` — the highest API level in the E2E matrix — via its Gradle Managed Device,
which Gradle provisions, boots, and tears down automatically. This one latest-API level is the
E2E that preflight runs locally and it must pass; CI fans the same suite out across the whole
API matrix (plus the API 37 preview job). Keep `api36DebugAndroidTest` in lockstep with the
highest managed device in `app/build.gradle.kts` and the E2E matrix in
`.github/workflows/ci.yml` — when a newer API level is added there, run that one instead.

## Reporting

- If everything passes, say so plainly (e.g. "preflight green: build, unit tests, lint, ktlint, detekt, api36 E2E").
- On failure, surface the actual Gradle error and point at the relevant report:
  - unit tests → `app/build/reports/tests/testDebugUnitTest/`
  - lint → `app/build/reports/lint-results-debug.html`
  - ktlint → `app/build/reports/ktlint/` (per source set, e.g. `ktlintTestSourceSetCheck/`)
  - detekt → `app/build/reports/detekt/`
  - E2E → `app/build/reports/androidTests/managedDevice/` (per-device HTML, e.g. `.../api36/`)
- Run only the **latest-API** E2E here (`api36DebugAndroidTest`, the highest level in the
  matrix); the full multi-API matrix and the API 37 preview job stay CI's job. If the host has
  no accelerated emulator and the managed device cannot boot, report that E2E could not run
  rather than treating the gate as green.
