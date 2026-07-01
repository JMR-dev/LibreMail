---
name: preflight
description: Run LibreMail's fast CI gate locally (assembleDebug + testDebugUnitTest + lintDebug) before pushing or opening a PR. Mirrors the merge gate; does NOT run emulator E2E. Use before treating a change as done.
---

# /preflight

Run the same fast checks CI enforces on every PR, in order, and report the outcome.

## Preconditions

- The Gradle daemon must run on **JDK 17–21**. AGP 9.2 fails on JDK 25+. If a build errors
  with a JDK/AGP version mismatch, check `java -version` / `JAVA_HOME` and point it at a 17–21
  JDK (e.g. Android Studio's bundled JBR) before retrying.
- PowerShell: invoke the wrapper as `.\gradlew`. Git Bash / the Bash tool: `./gradlew`.

## Steps

Run these three, stopping at the first failure:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

To keep going and collect every failure in one pass, add `--continue`.

## Reporting

- If all three pass, say so plainly (e.g. "preflight green: build, unit tests, lint").
- On failure, surface the actual Gradle error. For test failures, point at the report under
  `app/build/reports/tests/testDebugUnitTest/`; for lint, `app/build/reports/lint-results-debug.html`.
- Do **not** run emulator/E2E (`connectedDebugAndroidTest`) here — that's CI's job unless the
  user explicitly asks.
