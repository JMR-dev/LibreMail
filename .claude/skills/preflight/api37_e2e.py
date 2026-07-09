#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Hand-provision an API 37 (Android 17, preview) emulator, run LibreMail's instrumented/E2E
suite against it, then tear the emulator and AVD down. Invoked by the /preflight skill as the
third (api37) E2E step, alongside the api35/api36 Gradle Managed Devices.

WHY THIS SCRIPT EXISTS
----------------------
API 37's only published system image is the nonstandard "android-37.0" / google_apis_ps16k
(16 KB page size) pairing. AGP's Gradle Managed Device DSL can only build an
"android-<apiLevel:Int>" package id (apiLevel = 37 -> "android-37") or an
"android-<apiPreview:codename>" one -- neither resolves to "android-37.0" -- so there is NO
api37DebugAndroidTest task to run. This script custom-provisions the emulator with
sdkmanager / avdmanager / emulator directly, closely mirroring CI's `e2e-preview` job in
.github/workflows/ci.yml (same system image string, same provisioning/boot sequence, same
emulator flags EXCEPT the GPU mode -- CI uses `-gpu swiftshader_indirect` for headless
determinism, while this local run uses `-gpu auto-no-window` to render on the host GPU, which
is faster; see start_emulator). Keep the two in lockstep on everything but that GPU flag: when a
stable, GMD-compatible API 37 image ships, delete this script, fold api37 into
testOptions.managedDevices, and fold 37 into CI's `e2e` matrix (dropping the `e2e-preview` job).

Pure standard library, cross-platform (Windows / Linux / macOS): tool paths and executable
suffixes are resolved per-OS, and `.bat` launchers are wrapped through `cmd /c` on Windows.

HYPERVISOR REQUIREMENT
----------------------
The emulator boots with `-accel on`, so it needs a FREE hardware hypervisor (Intel VT-x / AMD-V,
exposed as WHPX on Windows, KVM on Linux, HVF on macOS). If VirtualBox, Hyper-V, WSL2, Docker
Desktop, or another emulator is holding it, `-accel on` fails or the AVD hangs at 0% CPU and never
reaches sys.boot_completed. Shut those down before running preflight.

JDK: the final Gradle step needs a JDK 17-21 daemon (AGP 9.2 fails on JDK 25+), same as the rest
of preflight -- point JAVA_HOME at a 17-21 JDK before invoking.
"""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

# Defaults mirror .github/workflows/ci.yml e2e-preview (env.API37_IMAGE, env.ANDROID_PLATFORM,
# env.ANDROID_BUILD_TOOLS) and its avdmanager invocation. Do not diverge without updating ci.yml.
API37_IMAGE = "system-images;android-37.0;google_apis_ps16k;x86_64"
PLATFORM_PKG = "platforms;android-37.0"
BUILD_TOOLS = "build-tools;37.0.0"
AVD_NAME = "api37"
DEVICE_PROFILE = "pixel_2"
BOOT_TIMEOUT = 300
# GPU mode: the ONE deliberate divergence from CI's e2e-preview (which uses `swiftshader_indirect`
# for headless determinism). Locally we render on the host GPU -- faster, and the mode that boots
# cleanly on a dev machine. See start_emulator. Kept as a constant so start_emulator and the
# boot-failure diagnostics dump report the same value.
GPU_MODE = "auto-no-window"

IS_WINDOWS = os.name == "nt"
BAT = ".bat" if IS_WINDOWS else ""
EXE = ".exe" if IS_WINDOWS else ""


def cmd(tool: str, *args: str) -> list[str]:
    """Build an argv list, wrapping Windows `.bat`/`.cmd` launchers through `cmd /c`."""
    if IS_WINDOWS and tool.lower().endswith((".bat", ".cmd")):
        return ["cmd", "/c", tool, *args]
    return [tool, *args]


def find_sdk_root() -> str:
    candidates = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    system = platform.system()
    if system == "Windows":
        local = os.environ.get("LOCALAPPDATA")
        if local:
            candidates.append(os.path.join(local, "Android", "Sdk"))
    elif system == "Darwin":
        candidates.append(os.path.expanduser("~/Library/Android/sdk"))
    else:
        candidates.append(os.path.expanduser("~/Android/Sdk"))
    for candidate in candidates:
        if candidate and os.path.isdir(candidate):
            return os.path.abspath(candidate)
    raise RuntimeError(
        "Android SDK not found. Set ANDROID_SDK_ROOT (or ANDROID_HOME) to your SDK location."
    )


def resolve_tool(sdk_root: str, rel_paths: list[list[str]], name: str) -> str:
    for rel in rel_paths:
        path = os.path.join(sdk_root, *rel)
        if os.path.isfile(path):
            return path
    raise RuntimeError(
        f"Could not find {name} under {sdk_root}. "
        "Install the Android SDK command-line tools + emulator."
    )


def run_sdkmanager(sdkmanager: str, args: list[str]) -> None:
    # Feed a stream of "y" so any unaccepted (incl. preview) license prompt is auto-accepted; this
    # is the non-interactive equivalent of the CI runner having licenses pre-accepted.
    result = subprocess.run(cmd(sdkmanager, *args), input="y\n" * 50, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"sdkmanager failed (exit {result.returncode}) for: {' '.join(args)}")


def create_avd(avdmanager: str, emulator: str) -> None:
    print(f"Creating AVD '{AVD_NAME}' from {API37_IMAGE} (device: {DEVICE_PROFILE})...")
    # "no" answers avdmanager's "create a custom hardware profile?" prompt, mirroring CI.
    result = subprocess.run(
        cmd(avdmanager, "create", "avd", "-n", AVD_NAME, "-k", API37_IMAGE,
            "-d", DEVICE_PROFILE, "--force"),
        input="no\n", text=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"avdmanager create avd failed (exit {result.returncode}).")
    print("AVDs visible to the emulator:")
    subprocess.run(cmd(emulator, "-list-avds"), check=False)


def start_emulator(emulator: str, emu_log: Path, attempt: int) -> subprocess.Popen:
    print(f"Starting API 37 emulator (attempt {attempt})...")
    # Flags mirror .github/workflows/ci.yml e2e-preview (cold headless boot, hardware accel
    # required, no cameras), with ONE deliberate LOCAL exception -- the GPU mode (GPU_MODE above:
    # CI uses `-gpu swiftshader_indirect`, deterministic on a headless CI runner; locally we render
    # on the host GPU -- faster, and the mode that boots cleanly on a dev machine). `-verbose -debug
    # init,avd_config,kernel` turns emulator boot logging on by default (mirrors CI) so a boot flake
    # is diagnosable from $EMU_LOG; it is DIAGNOSTICS ONLY and does not change any boot-affecting
    # flag. Keep everything except the GPU mode in lockstep with that job.
    flags = [
        "-avd", AVD_NAME,
        "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", "on",
        "-gpu", GPU_MODE, "-camera-back", "none", "-camera-front", "none",
        "-verbose", "-debug", "init,avd_config,kernel",
    ]
    log = open(emu_log, "wb")  # noqa: SIM115 - handed to the child; closed in the parent below
    try:
        proc = subprocess.Popen(cmd(emulator, *flags), stdout=log, stderr=subprocess.STDOUT)
    finally:
        log.close()  # the child has inherited its own fd; the parent's copy is no longer needed
    return proc


def wait_for_boot(adb: str, proc: subprocess.Popen, timeout: int) -> bool:
    subprocess.run(cmd(adb, "start-server"), check=False)
    deadline = time.monotonic() + timeout

    # Phase 1 -- the literal `adb wait-for-device`, bounded so a dead emulator can't hang the run
    # (returns as soon as the emulator registers). Mirrors CI's `adb wait-for-device`.
    try:
        subprocess.run(cmd(adb, "wait-for-device"), timeout=max(1, deadline - time.monotonic()),
                       check=False)
    except subprocess.TimeoutExpired:
        return False
    if proc.poll() is not None:
        return False

    # Phase 2 -- poll sys.boot_completed until it flips to 1 (mirrors CI's getprop loop).
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            print(f"Emulator process exited during boot (code {proc.returncode}).",
                  file=sys.stderr)
            return False
        out = subprocess.run(cmd(adb, "shell", "getprop", "sys.boot_completed"),
                             capture_output=True, text=True, check=False)
        if out.stdout.strip() == "1":
            return True
        time.sleep(2)
    return False


def stop_emulator(adb: str | None, proc: subprocess.Popen | None) -> None:
    if adb:
        subprocess.run(cmd(adb, "emu", "kill"), check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)
    if proc and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


def tail(path: Path, lines: int = 80) -> None:
    try:
        with open(path, "r", errors="replace") as handle:
            content = handle.readlines()[-lines:]
        print("--- emulator.log (tail) ---")
        print("".join(content))
    except OSError:
        pass


def _accel_check(emulator: str) -> str:
    """`emulator -accel-check` output -- the accelerator status (WHPX / KVM / HVF availability)."""
    try:
        out = subprocess.run(cmd(emulator, "-accel-check"), capture_output=True, text=True,
                             check=False)
        return (out.stdout + out.stderr).strip() or f"(no output; exit {out.returncode})"
    except OSError as exc:
        return f"(accel-check failed: {exc})"


def _kvm_status() -> str:
    """/dev/kvm presence (Linux). Off-Linux the accelerator is WHPX/HVF -- see -accel-check."""
    if os.path.exists("/dev/kvm"):
        return "/dev/kvm present"
    return f"/dev/kvm absent (expected off-Linux; platform={platform.system()})"


def _mem_info() -> str:
    """Free/total memory. Reads /proc/meminfo on Linux (where CI runs); best-effort elsewhere."""
    try:
        meminfo = Path("/proc/meminfo")
        if meminfo.exists():
            wanted = {"MemTotal", "MemFree", "MemAvailable"}
            lines = [line.strip() for line in meminfo.read_text().splitlines()
                     if line.split(":", 1)[0] in wanted]
            if lines:
                return "; ".join(lines)
    except OSError:
        pass
    return f"(memory stats unavailable on {platform.system()})"


def _disk_info(path: Path) -> str:
    """Free/total disk for the filesystem holding `path` (cross-platform via shutil.disk_usage)."""
    try:
        usage = shutil.disk_usage(path)
        gib = 1024 ** 3
        return f"total={usage.total / gib:.1f}GiB free={usage.free / gib:.1f}GiB ({path})"
    except OSError as exc:
        return f"(disk stats unavailable: {exc})"


def start_logcat(adb: str, logcat_log: Path, attempt: int) -> subprocess.Popen | None:
    """Background `adb wait-for-device logcat -v time` to a file. wait-for-device blocks until the
    device registers, so streaming starts the moment the emulator appears and captures the whole
    boot. Mirrors CI's e2e-preview logcat capture; appended (with a header) per boot attempt."""
    try:
        with open(logcat_log, "a") as marker:
            marker.write(f"===== logcat (attempt {attempt}) =====\n")
        log = open(logcat_log, "ab")  # noqa: SIM115 - child inherits fd; parent copy closed below
        try:
            return subprocess.Popen(cmd(adb, "wait-for-device", "logcat", "-v", "time"),
                                    stdout=log, stderr=subprocess.STDOUT)
        finally:
            log.close()  # the child has inherited its own fd; the parent's copy is no longer needed
    except OSError as exc:
        print(f"WARNING: could not start logcat capture: {exc}", file=sys.stderr)
        return None


def stop_logcat(proc: subprocess.Popen | None) -> None:
    if proc and proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def dump_diagnostics(adb: str, emulator: str, emu_log: Path, avd_home: Path, attempt: int) -> None:
    """Print boot diagnostics + a concise failure summary to the console -- the local mirror of CI's
    e2e-preview boot-timeout dump (accel/KVM/GPU/mem/disk/AVD config + emulator.log tail). Local
    runs PRINT these; CI uploads the same set as an artifact and prints only the concise summary."""
    accel = _accel_check(emulator)
    kvm = _kvm_status()
    config_ini = avd_home / f"{AVD_NAME}.avd" / "config.ini"
    print(f"===== API 37 boot diagnostics (attempt {attempt}) =====")
    print("--- adb devices ---")
    subprocess.run(cmd(adb, "devices"), check=False)
    print(f"--- emulator -accel-check ---\n{accel}")
    print(f"--- KVM/hypervisor ---\n{kvm}")
    print(f"--- GPU mode ---\n{GPU_MODE}")
    print(f"--- free memory ---\n{_mem_info()}")
    print(f"--- free disk ---\n{_disk_info(Path(tempfile.gettempdir()))}")
    print("--- AVD config.ini ---")
    try:
        print(config_ini.read_text(errors="replace"))
    except OSError as exc:
        print(f"(could not read {config_ini}: {exc})")
    # Concise failure summary (mirrors CI): accel/KVM status + the last 50 lines of emulator.log.
    print(f"----- BOOT FAILURE SUMMARY (attempt {attempt}) -----")
    print(f"accel-check: {accel}")
    print(f"kvm: {kvm}")
    tail(emu_log, 50)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Hand-provision + run the API 37 preview E2E suite.")
    parser.add_argument("--boot-timeout", type=int, default=BOOT_TIMEOUT,
                        help="Seconds to wait for the emulator to reach sys.boot_completed.")
    args = parser.parse_args()

    sdk_root = find_sdk_root()
    print(f"Using Android SDK at: {sdk_root}")
    sdkmanager = resolve_tool(sdk_root, [
        ["cmdline-tools", "latest", "bin", "sdkmanager" + BAT],
        ["cmdline-tools", "bin", "sdkmanager" + BAT],
        ["tools", "bin", "sdkmanager" + BAT],
    ], "sdkmanager")
    avdmanager = resolve_tool(sdk_root, [
        ["cmdline-tools", "latest", "bin", "avdmanager" + BAT],
        ["cmdline-tools", "bin", "avdmanager" + BAT],
        ["tools", "bin", "avdmanager" + BAT],
    ], "avdmanager")

    # Pin ANDROID_AVD_HOME so avdmanager (writes it) and the emulator (reads it) agree on the AVD
    # dir -- the same fix CI's e2e-preview applies to avoid "Unknown AVD name [api37]".
    avd_home = Path.home() / ".android" / "avd"
    avd_home.mkdir(parents=True, exist_ok=True)
    os.environ["ANDROID_AVD_HOME"] = str(avd_home)

    emu_log = Path(tempfile.gettempdir()) / "libremail-api37-emulator.log"
    logcat_log = Path(tempfile.gettempdir()) / "libremail-api37-logcat.txt"
    try:
        logcat_log.unlink()  # start fresh; start_logcat appends (with a header) per attempt
    except OSError:
        pass
    adb: str | None = None
    proc: subprocess.Popen | None = None
    logcat_proc: subprocess.Popen | None = None
    test_exit = 1

    try:
        # 1. Install the SDK platform, build-tools, platform-tools, emulator, and preview image.
        print(f"Installing SDK packages + API 37 preview system image ({API37_IMAGE})...")
        run_sdkmanager(sdkmanager, ["--licenses"])
        run_sdkmanager(sdkmanager, [PLATFORM_PKG, BUILD_TOOLS, "platform-tools", "emulator",
                                    API37_IMAGE])

        # adb + emulator are only guaranteed present after the install above.
        adb = resolve_tool(sdk_root, [["platform-tools", "adb" + EXE]], "adb")
        emulator = resolve_tool(sdk_root, [["emulator", "emulator" + EXE]], "emulator")

        # 2. Create the AVD, mirroring CI.
        create_avd(avdmanager, emulator)

        # 3. Cold-boot headless, retrying once (mirrors CI's two-attempt boot loop). Diagnostics
        # (logcat capture + a boot-timeout dump) are ADDITIVE -- the retry/boot-wait is unchanged.
        booted = False
        for attempt in (1, 2):
            proc = start_emulator(emulator, emu_log, attempt)
            # Capture logcat from device registration onward (mirrors CI); killed on failure.
            logcat_proc = start_logcat(adb, logcat_log, attempt)
            if wait_for_boot(adb, proc, args.boot_timeout):
                booted = True
                break
            print(f"API 37 emulator did not boot within {args.boot_timeout}s (attempt {attempt}).",
                  file=sys.stderr)
            dump_diagnostics(adb, emulator, emu_log, avd_home, attempt)
            stop_logcat(logcat_proc)
            logcat_proc = None
            stop_emulator(adb, proc)
            proc = None
            time.sleep(5)
        if not booted:
            raise RuntimeError("API 37 preview emulator failed to boot after 2 attempts.")

        # 4. Force the emulator to grant the app window focus, then GATE on it (the SAME shared
        # helper CI's e2e / e2e-preview jobs invoke, issue #468), before running the suite: wake
        # the display, dismiss + disable the keyguard, keep the screen on, disable animations, and
        # wait for a focused window. Replaces the lone `input keyevent 82`. Best-effort: fall back
        # to that legacy nudge if the shared helper is somehow missing.
        repo_root = Path(__file__).resolve().parents[3]
        focus_gate = repo_root / ".github" / "scripts" / "emulator_focus_gate.py"
        if focus_gate.is_file():
            subprocess.run([sys.executable, str(focus_gate), "--adb", adb], check=False)
        else:
            subprocess.run(cmd(adb, "shell", "input", "keyevent", "82"), check=False)

        gradlew = repo_root / ("gradlew.bat" if IS_WINDOWS else "gradlew")
        print(f"Running :app:connectedDebugAndroidTest against {AVD_NAME}...")
        test_exit = subprocess.run(
            cmd(str(gradlew), ":app:connectedDebugAndroidTest", "--stacktrace"),
            cwd=str(repo_root), check=False,
        ).returncode
    except Exception as exc:  # noqa: BLE001 - top-level guard so teardown always runs
        print(f"ERROR: {exc}", file=sys.stderr)
        test_exit = 1
    finally:
        # 5. Always tear the emulator down and delete the AVD, even on failure.
        print("Tearing down API 37 emulator and AVD...")
        stop_logcat(logcat_proc)
        stop_emulator(adb, proc)
        subprocess.run(cmd(avdmanager, "delete", "avd", "-n", AVD_NAME), check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"(emulator boot log: {emu_log})")
        print(f"(logcat: {logcat_log})")

    if test_exit != 0:
        print(f"api37 connectedDebugAndroidTest failed (exit {test_exit}).", file=sys.stderr)
        return test_exit
    print("api37 E2E passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
