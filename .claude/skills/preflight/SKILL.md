---
name: preflight
description: Run LibreMail's fast CI gate locally (assembleDebug + testDebugUnitTest + compileDebugAndroidTestKotlin + lintDebug + ktlintCheck + detekt) before pushing or opening a PR. Mirrors the merge gate; does NOT run emulator E2E. Use before treating a change as done.
---

# /preflight

Run the same fast checks CI enforces on every PR, in order, and report the outcome.

## Preconditions

- The Gradle daemon must run on **JDK 17–21**. AGP 9.2 fails on JDK 25+. If a build errors
  with a JDK/AGP version mismatch, check `java -version` / `JAVA_HOME` and point it at a 17–21
  JDK (e.g. Android Studio's bundled JBR) before retrying.
- PowerShell: invoke the wrapper as `.\gradlew`. Git Bash / the Bash tool: `./gradlew`.

## Steps

Run these, stopping at the first failure:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:lintDebug
./gradlew :app:ktlintCheck :app:detekt
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

## Reporting

- If everything passes, say so plainly (e.g. "preflight green: build, unit tests, lint, ktlint, detekt").
- On failure, surface the actual Gradle error and point at the relevant report:
  - unit tests → `app/build/reports/tests/testDebugUnitTest/`
  - lint → `app/build/reports/lint-results-debug.html`
  - ktlint → `app/build/reports/ktlint/` (per source set, e.g. `ktlintTestSourceSetCheck/`)
  - detekt → `app/build/reports/detekt/`
- Do **not** run emulator/E2E (`connectedDebugAndroidTest`) here — that's CI's job unless the
  user explicitly asks.
