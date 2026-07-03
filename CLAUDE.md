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
# single unit test:
./gradlew :app:testDebugUnitTest --tests "org.libremail.data.SomeClassTest"
```

E2E/instrumented tests need a booted emulator: `./gradlew :app:connectedDebugAndroidTest`,
or via Gradle Managed Devices `./gradlew e2eGroupDebugAndroidTest` (whole matrix) /
`./gradlew api29DebugAndroidTest` (one API level). The managed-device list in
`app/build.gradle.kts` must stay in lockstep with the E2E matrix in `.github/workflows/ci.yml`.

**Before treating a change as done**, run the fast CI gate: `assembleDebug` +
`testDebugUnitTest` + `compileDebugAndroidTestKotlin` + `lintDebug` + `ktlintCheck` +
`detekt` (the `/preflight` skill does this). `compileDebugAndroidTestKotlin` compiles the
`androidTest` source set that the rest of the gate skips, catching E2E/instrumented-test
compile errors before they surface only in CI. `ktlintCheck`/`detekt` cover the
`test`/`androidTest` source sets that `lintDebug` skips, so they catch style violations that
would otherwise fail CI's Static analysis gate. Emulator E2E is left to CI unless asked.

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

## Repo etiquette

- Branch off `main`; branch names like `feat-…` / `fix-…`. PRs target `main` and must pass
  the `CI passed` gate.
- **Conventional Commits** for commit subjects and PR titles: `type(scope): summary`
  (`feat`, `fix`, `chore`, …), matching existing history.
