---
name: preflight
description: Run LibreMail's fast CI gate locally (assembleDebug + testDebugUnitTest + compileDebugAndroidTestKotlin + lintDebug + ktlintCheck + detekt) plus the top-of-matrix emulator E2E (currently API 35 + 36 via Gradle Managed Devices, then API 37 preview via the hand-provisioning script) before pushing or opening a PR. Mirrors the merge gate; the rest of the multi-API matrix stays CI-only. Use before treating a change as done.
---

# /preflight

Run the same fast checks CI enforces on every PR, in order, and report the outcome.

## Preconditions

- The Gradle daemon must run on **JDK 17–21**. AGP 9.2 fails on JDK 25+. If a build errors
  with a JDK/AGP version mismatch, check `java -version` / `JAVA_HOME` and point it at a 17–21
  JDK (e.g. Android Studio's bundled JBR) before retrying.
- PowerShell: invoke the wrapper as `.\gradlew`. Git Bash / the Bash tool: `./gradlew`.
- The final three E2E steps each boot an emulator, so the host needs a **free hardware
  hypervisor** (Intel VT-x / AMD-V, exposed as WHPX on Windows, KVM on Linux, HVF on macOS).
  Shut down VirtualBox, Hyper-V-based VMs, WSL2, Docker Desktop, or any other emulator first — a
  VM holding the hypervisor starves the AVD, and it hangs at 0% CPU and never reaches
  `sys.boot_completed`. The api35/api36 steps run through Gradle Managed Devices (Gradle
  downloads the image and boots/tears down each AVD itself); the api37 step is hand-provisioned
  by `api37_e2e.py` (see Steps). The first run per API level is slow while its system image
  downloads. If the host has no accelerated emulator and a device cannot boot, report the E2E
  step as not run rather than treating the gate as green.
- The api37 step is a stdlib-only, cross-platform **Python 3** script and needs `python3` plus
  the Android SDK command-line tools (`sdkmanager`/`avdmanager`) and `emulator` on the machine,
  located via `ANDROID_SDK_ROOT`/`ANDROID_HOME` (or the per-OS default:
  `%LOCALAPPDATA%\Android\Sdk` on Windows, `~/Library/Android/sdk` on macOS, `~/Android/Sdk` on
  Linux). The script installs the preview system image itself on first run.

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
python3 .claude/skills/preflight/api37_e2e.py   # api37 preview E2E (hand-provisioned; on Windows: py or python)
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

The three E2E steps run last because they are the slowest. `api35DebugAndroidTest` and
`api36DebugAndroidTest` run the full instrumented/E2E suite on `api35`, then `api36` — the top
two stable levels in the E2E matrix — each via its own Gradle Managed Device, which Gradle
provisions, boots, and tears down automatically.

`api37_e2e.py` then runs the same suite on the **API 37 preview** emulator. API 37 has no Gradle
Managed Device — its only published system image is the nonstandard `android-37.0` /
`google_apis_ps16k` pairing, which neither `ManagedVirtualDevice`'s `apiLevel` (Int) nor
`apiPreview` (codename) DSL resolves (see the comment above `testOptions.managedDevices` in
`app/build.gradle.kts`), so there is no `api37DebugAndroidTest` task. The script hand-provisions
it essentially the way CI's `e2e-preview` job does — installs the image with `sdkmanager`,
creates the AVD with `avdmanager`, cold-boots it headless, waits for `sys.boot_completed`, runs
`:app:connectedDebugAndroidTest`, then kills the emulator and deletes the AVD. The emulator flags
match `e2e-preview` with one deliberate local exception: the **GPU mode**. CI uses
`-gpu swiftshader_indirect` (software rendering, deterministic on a headless CI runner); the
local run uses `-gpu auto-no-window`, which renders on the host GPU — faster, and the mode that
boots cleanly on a dev machine.

All three levels are the E2E that preflight runs locally and all three must pass; CI fans the
same suite out across the whole matrix (API 29–36 in `e2e`, plus API 37 in `e2e-preview`). Keep
`api35DebugAndroidTest` / `api36DebugAndroidTest` in lockstep with the top of the managed-device
list in `app/build.gradle.kts`, and keep `api37_e2e.py` in lockstep with the `e2e-preview` job in
`.github/workflows/ci.yml` (same image string + emulator flags, apart from the intentional GPU-mode
difference noted above) — when a newer API level is added there, run the new top levels instead.

## Reporting

- If everything passes, say so plainly (e.g. "preflight green: build, unit tests, lint, ktlint, detekt, api35+api36+api37 E2E").
- On failure, surface the actual Gradle error and point at the relevant report:
  - unit tests → `app/build/reports/tests/testDebugUnitTest/`
  - lint → `app/build/reports/lint-results-debug.html`
  - ktlint → `app/build/reports/ktlint/` (per source set, e.g. `ktlintTestSourceSetCheck/`)
  - detekt → `app/build/reports/detekt/`
  - api35/api36 E2E → `app/build/reports/androidTests/managedDevice/` (per-device HTML, e.g.
    `.../api35/`, `.../api36/`)
  - api37 E2E → `app/build/reports/androidTests/connected/` (the `connectedDebugAndroidTest`
    report the script drives); the emulator's own boot log is at the temp path the script prints.
- Run the **top two stable-API** levels (`api35DebugAndroidTest` + `api36DebugAndroidTest`) plus
  the **API 37 preview** via `api37_e2e.py`; the rest of the multi-API matrix (API 29–34) stays
  CI's job. If the host has no accelerated emulator and a device cannot boot (see the hypervisor
  note in Preconditions), report that E2E could not run rather than treating the gate as green.
