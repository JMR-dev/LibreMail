<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# `local_instrumented.sh` — reliable local instrumented/E2E runs

A helper for running LibreMail's instrumented / E2E tests **locally** without Gradle
Managed Devices (GMD). Companion to `api37_e2e.py`; born from issue #269.

## Usage

```bash
# in Git Bash, from anywhere — invoke the script by path:
.claude/skills/preflight/local_instrumented.sh org.libremail.ui.compose.ComposeScreenE2ETest
# multiple classes (comma-separated, no spaces):
.claude/skills/preflight/local_instrumented.sh org.libremail.a.FooTest,org.libremail.b.BarTest
```

The script is CWD-independent: it resolves its own repo/worktree root from its script
location (three directories up from `.claude/skills/preflight`) and `cd`s there before
invoking gradlew, so it always builds *that* tree's `:app` — never whatever tree your
shell happens to be sitting in. This matters most when you have several worktrees
checked out side by side; run the copy of this script that lives inside the worktree you
want to test, regardless of your current directory (issue #284).

It cold-boots **one** emulator (`-no-snapshot`, no GMD), waits for `sys.boot_completed`,
runs `:app:connectedDebugAndroidTest` filtered to the class(es) you pass, then tears the
emulator down and verifies no orphaned `qemu` process is left behind (exit **3** if one is).

## Why (short version)

- **GMD is broken locally on this box.** `apiXXDebugAndroidTest` fails in GMD's snapshot
  step — `AvdSnapshotHandler$EmulatorSnapshotCannotCreatedException: Snapshot creation
  timed out` (AEHD 2.2 can't save/load the snapshot). The emulator itself boots fine; only
  GMD's snapshot machinery is broken. CI is unaffected (it uses `connectedDebugAndroidTest`,
  not GMD). See issue #269.
- **Keep runs targeted.** The full ~114-test suite tends to wedge mid-run on this machine;
  small, targeted class sets do not. That's why the script requires an explicit class list —
  run only what you changed. The full matrix is CI's job.
- **Emulator hygiene is mandatory.** A hung `adb emu kill` leaves a detached
  `qemu-system-x86_64-headless.exe`; accumulated orphans have frozen this machine. The
  script force-kills stragglers before booting and after tearing down, and fails loudly if
  a zombie survives.

See the header comment of `local_instrumented.sh` for the full rationale, requirements, and
the `LOCAL_INSTRUMENTED_*` environment overrides (AVD name, JDK home, boot timeout, …).

## Requirements

Git Bash; Android SDK `emulator` + `adb` on `PATH`; a JDK **17–21** (AGP 9.2 fails on 25+ —
the script pins `JAVA_HOME` to a known JDK 21, overridable via `LOCAL_INSTRUMENTED_JDK`); and
a free hardware hypervisor (shut down VirtualBox / other VMs first).
