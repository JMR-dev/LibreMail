---
name: preflight
description: Run LibreMail's fast CI gate locally (assembleDebug + testDebugUnitTest + jacocoTestCoverageVerification + compileDebugAndroidTestKotlin + lintDebug + ktlintCheck + detekt) plus the local emulator E2E — the instrumented test class(es) you changed via local_instrumented.py (cold-boot, no Gradle Managed Devices), then the API 37 preview via api37_e2e.py — before pushing or opening a PR. Mirrors the merge gate; CI runs the full multi-API matrix. Use before treating a change as done.
---

# /preflight

Run the same fast checks CI enforces on every PR, in order, and report the outcome.

## Preconditions

- The Gradle daemon must run on **JDK 17–21**. AGP 9.2 fails on JDK 25+. If a build errors
  with a JDK/AGP version mismatch, check `java -version` / `JAVA_HOME` and point it at a 17–21
  JDK (e.g. Android Studio's bundled JBR) before retrying.
- PowerShell: invoke the wrapper as `.\gradlew`. Git Bash / the Bash tool: `./gradlew`.
- The final two E2E steps each boot an emulator, so the host needs a **free hardware
  hypervisor** (Intel VT-x / AMD-V, exposed as WHPX on Windows, KVM on Linux, HVF on macOS).
  Shut down VirtualBox, Hyper-V-based VMs, WSL2, Docker Desktop, or any other emulator first — a
  VM holding the hypervisor starves the AVD, and it hangs at 0% CPU and never reaches
  `sys.boot_completed`. Both steps are hand-provisioned by cross-platform Python scripts (**not**
  Gradle Managed Devices, which fail locally on this box — see Steps): `local_instrumented.py`
  cold-boots one existing AVD and runs the instrumented class(es) you changed; `api37_e2e.py`
  installs + boots the API 37 preview image. The first API 37 run is slow while its system image
  downloads. If the host has no accelerated emulator and a device cannot boot, report the E2E
  step as not run rather than treating the gate as green.
- Both E2E steps are stdlib-only, cross-platform **Python 3** scripts. `local_instrumented.py`
  needs `python3` plus the Android SDK `emulator` + `adb` on `PATH` and an existing AVD (any local
  `apiXXDebugAndroidTest` run creates one; override with `LOCAL_INSTRUMENTED_AVD` /
  `ANDROID_AVD_HOME`), and it pins `JAVA_HOME` to a JDK 17–21 itself (override with
  `LOCAL_INSTRUMENTED_JDK`). `api37_e2e.py` additionally needs the Android SDK command-line tools
  (`sdkmanager`/`avdmanager`), located via `ANDROID_SDK_ROOT`/`ANDROID_HOME` (or the per-OS
  default: `%LOCALAPPDATA%\Android\Sdk` on Windows, `~/Library/Android/sdk` on macOS,
  `~/Android/Sdk` on Linux); it installs the preview system image itself on first run.

## Steps

Run these, stopping at the first failure:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:jacocoTestCoverageVerification
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:lintDebug
./gradlew :app:ktlintCheck :app:detekt
python3 .claude/skills/preflight/local_instrumented.py <your.Changed.TestClass>[,<Class2>,...]   # local instrumented/E2E, cold-boot (no GMD)
python3 .claude/skills/preflight/api37_e2e.py   # api37 preview E2E (hand-provisioned; on Windows: py or python)
```

`jacocoTestCoverageVerification` runs right after `testDebugUnitTest` because it reads that
task's JVM exec data — it enforces the whole-app **no-regression line-coverage floor (currently
0.84)**, so a coverage regression is caught locally instead of only in CI (the exact class of
failure that reached CI on #367).

`compileDebugAndroidTestKotlin` compiles the `androidTest` source set — the E2E/instrumented
tests — without needing an emulator. `assembleDebug`, `testDebugUnitTest`, and `lintDebug` never
compile that source set, so a change that breaks it (e.g. an instrumented test calling a UI API
that just changed signature) passes the rest of this gate locally yet fails CI.

`ktlintCheck` + `detekt` are exactly what CI's **Static analysis** job runs — they cover the
`test`/`androidTest` source sets that `lintDebug` does not, so a style violation there fails the
merge gate even when the build and lint are green. Add `--continue` to any command (e.g.
`:app:ktlintCheck :app:detekt --continue`) to collect every failure in one pass instead of
stopping at the first.

The two E2E steps run last because they are the slowest, and they run through **cross-platform
Python scripts, not Gradle Managed Devices (GMD)**. GMD's `apiXXDebugAndroidTest` tasks fail
locally on this box — GMD's AVD-snapshot step times out under the AEHD 2.2 hypervisor
(`AvdSnapshotHandler$EmulatorSnapshotCannotCreatedException`), cycling for hours — so preflight
does **not** call them (issue #269/#281). `local_instrumented.py` instead cold-boots one existing
AVD by hand with `-no-snapshot` (the exact `connectedDebugAndroidTest` technique CI and
`api37_e2e.py` use), runs `:app:connectedDebugAndroidTest` filtered to the instrumented class(es)
you pass, then tears the emulator down and verifies no orphaned `qemu` process is left behind
(exit 3 if one survives). Pass the instrumented/E2E class(es) you actually changed
(comma-separated, no spaces) — the full ~114-test suite tends to wedge mid-run locally, so
targeted runs are deliberate; the whole suite across every API level is CI's job.

`api37_e2e.py` then runs the instrumented/E2E suite on the **API 37 preview** emulator. API 37 has no Gradle
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

Both E2E steps are the E2E that preflight runs locally and both must pass; CI then fans the full
instrumented/E2E suite out across the whole matrix (API 29–36 in `e2e`, plus API 37 in
`e2e-preview`). Keep `local_instrumented.py` pointed at the instrumented class(es) you changed,
and keep `api37_e2e.py` in lockstep with the `e2e-preview` job in `.github/workflows/ci.yml`
(same image string + emulator flags, apart from the intentional GPU-mode difference noted above).
The local gate no longer runs the GMD `apiXXDebugAndroidTest` tasks (they are unusable locally —
see above); full multi-API coverage stays CI's job.

## Reporting

- If everything passes, say so plainly (e.g. "preflight green: build, unit tests, lint, ktlint, detekt, local + api37 E2E").
- On failure, surface the actual Gradle error and point at the relevant report:
  - unit tests → `app/build/reports/tests/testDebugUnitTest/`
  - lint → `app/build/reports/lint-results-debug.html`
  - ktlint → `app/build/reports/ktlint/` (per source set, e.g. `ktlintTestSourceSetCheck/`)
  - detekt → `app/build/reports/detekt/`
  - local + api37 E2E → `app/build/reports/androidTests/connected/` (the `connectedDebugAndroidTest`
    report both scripts drive — the later run overwrites the earlier); each emulator's own boot log
    is at the temp path the script prints (`local_instrumented.py` also exits **3** if it leaves an
    orphaned `qemu`, **4** if the emulator never booted).
- Run the instrumented class(es) you changed via `local_instrumented.py`, plus the **API 37
  preview** via `api37_e2e.py`; the full multi-API matrix (API 29–37) stays CI's job. If the host
  has no accelerated emulator and a device cannot boot (see the hypervisor note in Preconditions),
  report that E2E could not run rather than treating the gate as green.
