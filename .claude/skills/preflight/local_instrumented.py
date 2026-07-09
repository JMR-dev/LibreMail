#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""local_instrumented.py -- reliable LOCAL instrumented / E2E test runner for LibreMail.

Usage:  local_instrumented.py <fully.qualified.TestClass>[,<Class2>,...]
Example:
    python .claude/skills/preflight/local_instrumented.py \
        org.libremail.ui.compose.ComposeScreenE2ETest
    python .claude/skills/preflight/local_instrumented.py \
        org.libremail.ui.compose.ComposeScreenE2ETest,org.libremail.ui.compose.RecipientChipTest

Cross-platform (Windows / Linux / macOS), pure standard library. Companion to
``api37_e2e.py``; ported from the original ``local_instrumented.sh`` (issue #281) so the
helper runs the same on the Windows primary dev box and on *nix -- no Git Bash, no ``jq``,
no ``taskkill`` vs ``kill`` portability gaps.

WHY THIS SCRIPT EXISTS  (issue #269)
------------------------------------
On this machine (Windows + the AEHD 2.2 hypervisor) the Gradle Managed Device (GMD)
instrumented tasks -- ``apiXXDebugAndroidTest`` -- FAIL during setup. GMD tries to
save/load an AVD *snapshot* and AEHD 2.2 cannot complete it:

    AvdSnapshotHandler$EmulatorSnapshotCannotCreatedException: Snapshot creation timed out

GMD retries the snapshot ~5x, rebooting the AVD each time -- that endless reboot is the
"cycling" that eats hours. The emulator ITSELF is healthy (8 GB RAM, sys.boot_completed=1,
shell-responsive); only GMD's snapshot step is broken. So every LOCAL GMD task is affected:
the coverage lanes and the /preflight api35/api36 steps. CI is unaffected -- it uses
reactivecircus/android-emulator-runner + ``connectedDebugAndroidTest``, never GMD.

THE RELIABLE LOCAL PATH (this script):
    Cold-boot ONE emulator by hand with ``-no-snapshot`` (no GMD, no snapshot machinery),
    then run ``:app:connectedDebugAndroidTest`` -- the exact technique CI and ``api37_e2e.py``
    already use. We reuse a GMD-provisioned AVD by name so we don't re-download a system
    image; GMD re-provisions its own copy on its next run, so the ``-wipe-data`` cold boot
    here does not disturb it.

KEEP RUNS TARGETED -- THE ~114-TEST MID-SUITE WEDGE
---------------------------------------------------
Running the WHOLE instrumented suite (~114 tests) via ``connectedDebugAndroidTest`` on this
box tends to wedge partway through -- the emulator stops making progress mid-run. Small,
targeted class sets do NOT hit that wedge. That is why this helper takes an explicit
``<fully.qualified.TestClass>[,...]`` argument and filters the run with
``-Pandroid.testInstrumentationRunnerArguments.class=...`` instead of running everything.
Run the class(es) you actually changed; do not use this to run the full suite (that is
CI's / preflight's job across the API matrix).

FREEZE / HYGIENE RATIONALE -- WHY THE ORPHAN-KILL + TEARDOWN VERIFY ARE MANDATORY
--------------------------------------------------------------------------------
A hung ``adb emu kill`` (or an interrupted run) leaves a detached qemu VM process behind
(``qemu-system-x86_64-headless.exe`` on Windows; a ``qemu-system-*`` process on *nix).
These orphans do not show up in ``adb devices``, they keep holding the hypervisor + RAM,
and accumulated orphans have FROZEN this machine outright. So this script:
  * PREAMBLE -- force-kills any pre-existing qemu/emulator processes and resets the adb
                server BEFORE booting, so we always start from a clean slate.
  * TEARDOWN -- ``adb emu kill``, kill the launcher we spawned, then re-check for ANY
                surviving emulator/qemu process and force-kill it (the ``-no-window`` emulator
                can leave a sibling ``emulator.exe`` that briefly outlives the qemu VM). Teardown
                runs even on Ctrl-C / error / SIGTERM (try/finally + atexit + SIGINT/SIGTERM
                handlers) and is idempotent.
  * VERIFY   -- if a qemu process is STILL alive after the force-kill, the script exits
                non-zero (code 3) so the leak is never silently ignored.
Never leave an emulator running after this script; if it exits 3, hunt the zombie down by
hand (Windows: ``tasklist | findstr qemu`` then ``taskkill /F /IM
qemu-system-x86_64-headless.exe``; *nix: ``pgrep -fa qemu-system`` then ``pkill -f
qemu-system``).

CROSS-PLATFORM PROCESS KILL
---------------------------
Listing and force-killing the emulator/qemu processes is abstracted per-OS (see
``list_procs`` / ``force_kill``): Windows uses ``tasklist`` + ``taskkill /F /IM <image>``;
*nix uses ``ps ax`` + ``pkill -f qemu-system`` (alongside the graceful ``adb emu kill``).
The qemu VM is the freeze-causing orphan on every platform.

EXIT CODES (preserved from local_instrumented.sh)
  0    tests passed
  2    usage / precondition failure
  3    a qemu zombie survived teardown -- clean it up by hand before the next run
  4    emulator never reached sys.boot_completed
  <n>  connectedDebugAndroidTest's own non-zero exit code (test failures)

REQUIREMENTS
  * Android SDK ``emulator`` + ``adb`` on PATH.
  * A JDK 17-21 for the Gradle daemon -- AGP 9.2 fails on JDK 25+. This script pins
    JAVA_HOME to a known JDK 21 (override with LOCAL_INSTRUMENTED_JDK) because the ambient
    JAVA_HOME on the primary box points at JDK 25.
  * A free hardware hypervisor (VT-x/WHPX/AEHD/KVM/HVF). Shut down VirtualBox / other VMs
    first or the AVD hangs at 0% CPU and never reaches sys.boot_completed.

Overridable via environment (defaults target the primary Windows dev box):
  LOCAL_INSTRUMENTED_AVD           AVD name to boot   (dev36_google_apis_x86_64_Pixel_2)
  ANDROID_AVD_HOME                 AVD home dir       (C:/Users/jasonross/.android/avd/gradle-managed)
  LOCAL_INSTRUMENTED_JDK           JDK 17-21 home     (Eclipse Adoptium jdk-21.0.11.10-hotspot)
  LOCAL_INSTRUMENTED_SERIAL        adb serial         (emulator-5554)
  LOCAL_INSTRUMENTED_BOOT_TIMEOUT  boot wait seconds  (300)
"""

from __future__ import annotations

import argparse
import atexit
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

IS_WINDOWS = os.name == "nt"

# ---- configuration (env-overridable; defaults are correct for the primary dev box) ------
AVD_NAME = os.environ.get("LOCAL_INSTRUMENTED_AVD", "dev36_google_apis_x86_64_Pixel_2")
AVD_HOME = os.environ.get("ANDROID_AVD_HOME", "C:/Users/jasonross/.android/avd/gradle-managed")
JDK_HOME = os.environ.get(
    "LOCAL_INSTRUMENTED_JDK", "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
)
SERIAL = os.environ.get("LOCAL_INSTRUMENTED_SERIAL", "emulator-5554")
BOOT_TIMEOUT = int(os.environ.get("LOCAL_INSTRUMENTED_BOOT_TIMEOUT", "300"))

# Windows qemu/emulator image names (see FREEZE / HYGIENE above). The ``-headless`` variant is
# what a ``-no-window`` emulator launches; the plain qemu name is swept too, belt-and-suspenders.
QEMU_IMAGE = "qemu-system-x86_64-headless.exe"
QEMU_IMAGE_ALT = "qemu-system-x86_64.exe"
EMULATOR_IMAGE = "emulator.exe"

REPO_ROOT = Path(__file__).resolve().parents[3]  # .claude/skills/preflight -> repo root
GRADLEW = REPO_ROOT / ("gradlew.bat" if IS_WINDOWS else "gradlew")
EMU_LOG = Path(tempfile.gettempdir()) / "libremail-local-instrumented-emulator.log"


class _RunState:
    """Mutable run state shared by main(), teardown(), the atexit hook and the signal
    handlers -- mirrors the bash globals EMU_PID / TEST_EXIT / ZOMBIE / TEARDOWN_DONE."""

    def __init__(self) -> None:
        self.proc: subprocess.Popen | None = None
        self.test_exit = 1
        self.zombie = False
        self.teardown_done = False


_STATE = _RunState()


def log(msg: str) -> None:
    print(f"\n=== {msg} ===")


def warn(msg: str) -> None:
    print(f"WARNING: {msg}", file=sys.stderr)


def die(msg: str) -> None:
    """Print an error and exit 2 (usage / precondition failure). Called before the teardown
    backstops are armed, so nothing has booted and there is nothing to tear down."""
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(2)


def cmd(tool: str, *args: str) -> list[str]:
    """Build an argv list, wrapping Windows ``.bat``/``.cmd`` launchers (e.g. gradlew.bat)
    through ``cmd /c`` -- matching api37_e2e.py. ``.exe`` tools pass through unchanged."""
    if IS_WINDOWS and tool.lower().endswith((".bat", ".cmd")):
        return ["cmd", "/c", tool, *args]
    return [tool, *args]


def _run_quiet(argv: list[str]) -> None:
    """Run a command, discarding output and swallowing any error -- teardown/kill helpers
    must always make progress."""
    try:
        subprocess.run(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    except (OSError, subprocess.SubprocessError):
        pass


def _run_capture(argv: list[str]) -> str:
    try:
        return subprocess.run(argv, capture_output=True, text=True, check=False).stdout or ""
    except (OSError, subprocess.SubprocessError):
        return ""


def list_procs(*needles: str) -> str:
    """Return the lines of currently-running processes whose name/command line contains any
    of ``needles`` (case-insensitive); empty string if none. Cross-platform stand-in for the
    .sh's ``tasklist | grep``: ``tasklist`` on Windows, ``ps ax`` on *nix."""
    out = _run_capture(["tasklist"] if IS_WINDOWS else ["ps", "ax"])
    lowered = [n.lower() for n in needles]
    return "\n".join(ln for ln in out.splitlines() if any(n in ln.lower() for n in lowered))


def list_qemu() -> str:
    return list_procs("qemu")


def list_emu_procs() -> str:
    return list_procs("qemu", "emulator")


def force_kill(win_images: list[str], nix_patterns: list[str]) -> None:
    """Best-effort force-kill. Windows: ``taskkill /F /IM <image> ...``. *nix: ``pkill -f
    <pattern>`` per pattern. Never raises -- teardown must always make progress."""
    if IS_WINDOWS:
        argv = ["taskkill", "/F"]
        for image in win_images:
            argv += ["/IM", image]
        _run_quiet(argv)
    else:
        for pattern in nix_patterns:
            _run_quiet(["pkill", "-f", pattern])


def tail(path: Path, lines: int = 40) -> None:
    try:
        with open(path, "r", errors="replace") as handle:
            content = handle.readlines()[-lines:]
        print("".join(content), file=sys.stderr, end="")
    except OSError:
        pass


def teardown() -> None:
    """Kill the emulator and verify no orphaned emulator/qemu process remains. Idempotent --
    safe to call from the finally block, the atexit hook and the signal handlers (mirrors the
    .sh TEARDOWN_DONE guard). Sets _STATE.zombie if a *qemu* process survives the force-kill --
    the machine-freezing case (exit 3)."""
    if _STATE.teardown_done:
        return
    _STATE.teardown_done = True

    log("Teardown: killing emulator and verifying no orphaned emulator/qemu remains")
    adb = shutil.which("adb")
    if adb:
        _run_quiet(cmd(adb, "-s", SERIAL, "emu", "kill"))
    time.sleep(2)

    # Belt-and-suspenders: kill the emulator launcher process we started, if still alive.
    proc = _STATE.proc
    if proc is not None and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=1)
        except subprocess.TimeoutExpired:
            proc.kill()

    # Reap any lingering emulator/qemu process, then verify. Unlike the original .sh -- which
    # swept qemu ONLY -- we also force-kill the emulator *launcher* image: on Windows the
    # ``-no-window`` emulator spawns a sibling ``emulator.exe`` that is NOT the Popen child we
    # tracked and outlives both it and the qemu VM by a few seconds, so a qemu-only sweep
    # returns while it is still shutting down -- an orphan the freeze-safety rule forbids. So we
    # trigger on any emulator-or-qemu survivor and taskkill the launcher too.
    if list_emu_procs():
        warn("emulator/qemu still present after 'adb emu kill'; force-killing:")
        print(list_emu_procs(), file=sys.stderr)
        force_kill([QEMU_IMAGE, QEMU_IMAGE_ALT, EMULATOR_IMAGE], ["qemu-system"])
        time.sleep(2)
        # A surviving QEMU is the machine-freezing zombie (exit 3); a stray launcher is not.
        remaining = list_qemu()
        if remaining:
            warn("qemu ZOMBIE survived teardown -- kill it by hand or the machine may freeze:")
            print(remaining, file=sys.stderr)
            _STATE.zombie = True

    if adb:
        _run_quiet(cmd(adb, "kill-server"))


def orphan_kill_preamble(adb: str) -> None:
    """Force-kill any pre-existing qemu/emulator processes and reset the adb server, so we
    always cold-boot from a clean slate."""
    log("Orphan-kill preamble: ensuring a clean slate before boot")
    existing = list_emu_procs()
    if existing:
        warn("Pre-existing emulator/qemu processes found -- force-killing them first:")
        print(existing, file=sys.stderr)
        force_kill([QEMU_IMAGE, EMULATOR_IMAGE], ["qemu-system"])
        time.sleep(2)
    else:
        print("No pre-existing qemu/emulator processes.")
    _run_quiet(cmd(adb, "kill-server"))
    _run_quiet(cmd(adb, "start-server"))


def start_emulator(emulator: str) -> subprocess.Popen:
    """Cold-boot ONE emulator by hand (no GMD, no snapshot), logging to EMU_LOG. Records the
    launcher process in _STATE so teardown can reap it even if we are interrupted next."""
    log(f"Cold-booting @{AVD_NAME} (no GMD, no snapshot); log -> {EMU_LOG}")
    flags = [
        f"@{AVD_NAME}",
        "-no-window", "-no-snapshot", "-no-boot-anim", "-no-audio",
        "-gpu", "auto-no-window", "-cores", "8", "-wipe-data",
    ]
    logf = open(EMU_LOG, "wb")  # noqa: SIM115 - handed to the child; parent copy closed below
    try:
        proc = subprocess.Popen(cmd(emulator, *flags), stdout=logf, stderr=subprocess.STDOUT)
    finally:
        logf.close()  # the child inherited its own fd; the parent's copy is no longer needed
    _STATE.proc = proc
    print(f"emulator launcher pid={proc.pid}")
    return proc


def wait_for_boot(adb: str, proc: subprocess.Popen, timeout: int) -> bool:
    """Poll ``adb get-state`` + ``getprop sys.boot_completed`` until the emulator is up, or the
    launcher dies, or ``timeout`` seconds elapse. Mirrors the .sh boot loop."""
    print(f"Waiting up to {timeout}s for sys.boot_completed on {SERIAL}...")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            warn("emulator process exited during boot; last log lines:")
            tail(EMU_LOG, 40)
            return False
        state = _run_capture(cmd(adb, "-s", SERIAL, "get-state")).strip()
        if state == "device":
            booted = _run_capture(
                cmd(adb, "-s", SERIAL, "shell", "getprop", "sys.boot_completed")
            ).strip()
            if booted == "1":
                return True
        time.sleep(3)
    return False


def prepare_focus(adb: str) -> None:
    # Force the emulator to grant the app window focus BEFORE the suite, then gate on it -- the
    # SAME shared helper CI's e2e / e2e-preview jobs invoke (issue #468), so local preflight
    # exercises the identical fix. It wakes the display, dismisses + disables the keyguard, keeps
    # the screen on, disables animations, and waits for a focused window. Best-effort: fall back
    # to the legacy `input keyevent 82` nudge if the shared helper is somehow missing.
    gate = REPO_ROOT / ".github" / "scripts" / "emulator_focus_gate.py"
    if gate.is_file():
        subprocess.run([sys.executable, str(gate), "--adb", adb, "--serial", SERIAL], check=False)
    else:
        _run_quiet(cmd(adb, "-s", SERIAL, "shell", "input", "keyevent", "82"))


def run_tests(test_classes: str) -> int:
    """Run :app:connectedDebugAndroidTest filtered to ``test_classes`` from the repo root
    (JAVA_HOME / ANDROID_AVD_HOME are already in the environment)."""
    log(f"Running :app:connectedDebugAndroidTest for: {test_classes}")
    print(f"JAVA_HOME={os.environ.get('JAVA_HOME', '')}")
    return subprocess.run(
        cmd(
            str(GRADLEW),
            ":app:connectedDebugAndroidTest",
            f"-Pandroid.testInstrumentationRunnerArguments.class={test_classes}",
            "--stacktrace",
        ),
        cwd=str(REPO_ROOT),
        check=False,
    ).returncode


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="local_instrumented.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            "Cold-boot ONE emulator (no GMD, no snapshot) and run "
            ":app:connectedDebugAndroidTest filtered to the given instrumented test class(es)."
        ),
        epilog=(
            "Keep the class set small and targeted -- the full ~114-test suite tends to wedge\n"
            "mid-run on this box (see the module docstring). The full matrix is CI's job.\n"
            "Example:\n"
            "  python .claude/skills/preflight/local_instrumented.py \\\n"
            "      org.libremail.ui.compose.ComposeScreenE2ETest,org.libremail.ui.compose.RecipientChipTest"
        ),
    )
    parser.add_argument(
        "test_classes",
        metavar="TEST_CLASSES",
        help=(
            "Comma-separated fully-qualified instrumented test class(es), no spaces "
            "(e.g. org.libremail.a.FooTest,org.libremail.b.BarTest)."
        ),
    )
    args = parser.parse_args()

    # ---- preconditions (before arming teardown; nothing has booted yet) ------------------
    emulator = shutil.which("emulator")
    adb = shutil.which("adb")
    if not emulator:
        die("emulator not on PATH (install Android SDK emulator).")
    if not adb:
        die("adb not on PATH (install Android SDK platform-tools).")
    kill_tool = "taskkill" if IS_WINDOWS else "pkill"
    if not shutil.which(kill_tool):
        die(f"{kill_tool} not found -- required to force-kill orphaned emulator/qemu processes.")
    if not GRADLEW.is_file():
        die(f"gradlew not found at {GRADLEW}.")
    if not os.path.isdir(JDK_HOME):
        die(f"JDK 17-21 not found at '{JDK_HOME}'. Set LOCAL_INSTRUMENTED_JDK.")
    if not os.path.isfile(os.path.join(AVD_HOME, AVD_NAME + ".ini")):
        die(
            f"AVD '{AVD_NAME}' not found under '{AVD_HOME}'. "
            "Set LOCAL_INSTRUMENTED_AVD / ANDROID_AVD_HOME. "
            "(GMD AVDs are created by any local apiXXDebugAndroidTest run.)"
        )

    # Move gradlew's working dir to this tree's repo root (below) and pin JAVA_HOME/AVD home,
    # exactly like the .sh -- the ambient JAVA_HOME on this box points at JDK 25 (AGP-incompatible).
    os.environ["JAVA_HOME"] = JDK_HOME
    os.environ["ANDROID_AVD_HOME"] = AVD_HOME

    # ---- arm teardown backstops BEFORE touching the emulator -----------------------------
    # try/finally is the primary path; atexit covers sys.exit()/unhandled-exception exits; the
    # signal handlers make SIGINT/SIGTERM tear down too (Python does not raise on SIGTERM by
    # default). teardown() is idempotent, so firing from several paths is safe (mirrors the
    # .sh's ``trap teardown EXIT INT TERM`` + TEARDOWN_DONE guard).
    atexit.register(teardown)

    def _signal_teardown(signum: int, _frame: object) -> None:
        teardown()
        sys.exit(128 + signum)

    signal.signal(signal.SIGINT, _signal_teardown)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, _signal_teardown)

    boot_failed = False
    try:
        orphan_kill_preamble(adb)
        proc = start_emulator(emulator)
        if wait_for_boot(adb, proc, BOOT_TIMEOUT):
            print("Emulator booted.")
            prepare_focus(adb)
            _STATE.test_exit = run_tests(args.test_classes)
        else:
            warn(f"Emulator did not reach sys.boot_completed within {BOOT_TIMEOUT}s.")
            tail(EMU_LOG, 40)
            boot_failed = True
    finally:
        teardown()

    if boot_failed:
        return 4
    if _STATE.zombie:
        warn(
            "Exiting 3: a qemu zombie was left behind (see above) -- "
            "clean it up before the next run."
        )
        return 3
    if _STATE.test_exit != 0:
        warn(
            f"connectedDebugAndroidTest failed (exit {_STATE.test_exit}). "
            "Report: app/build/reports/androidTests/connected/"
        )
        return _STATE.test_exit
    log(f"PASS -- instrumented tests green for: {args.test_classes}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
