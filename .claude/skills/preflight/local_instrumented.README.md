<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# `local_instrumented.py` — reliable local instrumented/E2E runs

A helper for running LibreMail's instrumented / E2E tests **locally** without Gradle
Managed Devices (GMD). Cross-platform, pure Python 3 standard library (Windows / Linux /
macOS). Companion to `api37_e2e.py`; born from issue #269, ported from bash to Python in
issue #281 so it runs the same on the Windows primary dev box and on \*nix — no Git Bash,
no `jq`, no `taskkill`-vs-`kill` gaps.

## Usage

```bash
# from anywhere — invoke the script by path (Windows: use `py` or `python`):
python .claude/skills/preflight/local_instrumented.py org.libremail.ui.compose.ComposeScreenE2ETest
# multiple classes (comma-separated, no spaces):
python .claude/skills/preflight/local_instrumented.py org.libremail.a.FooTest,org.libremail.b.BarTest
```

The script is CWD-independent: it resolves its own repo/worktree root from its script
location (three directories up from `.claude/skills/preflight`) and runs gradlew there, so
it always builds *that* tree's `:app` — never whatever tree your shell happens to be
sitting in. This matters most when you have several worktrees checked out side by side; run
the copy of this script that lives inside the worktree you want to test, regardless of your
current directory (issue #284).

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
- **Emulator hygiene is mandatory.** A hung `adb emu kill` leaves a detached qemu VM
  (`qemu-system-x86_64-headless.exe` on Windows, a `qemu-system-*` process on \*nix);
  accumulated orphans have frozen this machine. The script force-kills stragglers before
  booting and after tearing down, and fails loudly (exit 3) if a zombie survives. The
  orphan-kill is abstracted per-OS (`taskkill /F /IM …` on Windows, `pkill -f qemu-system`
  on \*nix), and teardown always runs — even on Ctrl-C / error / SIGTERM (try/finally +
  atexit + SIGINT/SIGTERM handlers).

Exit codes: **0** pass · **2** usage/precondition failure · **3** a qemu zombie survived
teardown · **4** emulator never booted · any other non-zero = `connectedDebugAndroidTest`'s
own test-failure exit code.

See the module docstring at the top of `local_instrumented.py` for the full rationale,
requirements, and the `LOCAL_INSTRUMENTED_*` environment overrides (AVD name, JDK home,
boot timeout, …).

## Requirements

`python3` (Windows: `py`/`python`); Android SDK `emulator` + `adb` on `PATH`; a JDK
**17–21** (AGP 9.2 fails on 25+ — the script pins `JAVA_HOME` to a known JDK 21, overridable
via `LOCAL_INSTRUMENTED_JDK`); and a free hardware hypervisor (shut down VirtualBox / other
VMs first).
