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
    # required, no cameras), with ONE deliberate LOCAL exception -- the GPU mode. CI uses
    # `-gpu swiftshader_indirect` (software rendering, deterministic on a headless CI runner);
    # locally we use `-gpu auto-no-window`, which renders on the host GPU: faster, and the mode
    # that boots cleanly on a dev machine. Keep everything except the GPU mode in lockstep with
    # that job.
    flags = [
        "-avd", AVD_NAME,
        "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", "on",
        "-gpu", "auto-no-window", "-camera-back", "none", "-camera-front", "none",
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
    adb: str | None = None
    proc: subprocess.Popen | None = None
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

        # 3. Cold-boot headless, retrying once (mirrors CI's two-attempt boot loop).
        booted = False
        for attempt in (1, 2):
            proc = start_emulator(emulator, emu_log, attempt)
            if wait_for_boot(adb, proc, args.boot_timeout):
                booted = True
                break
            print(f"API 37 emulator did not boot within {args.boot_timeout}s (attempt {attempt}).",
                  file=sys.stderr)
            tail(emu_log)
            stop_emulator(adb, proc)
            proc = None
            time.sleep(5)
        if not booted:
            raise RuntimeError("API 37 preview emulator failed to boot after 2 attempts.")

        # 4. Dismiss the keyguard, then run the instrumented/E2E suite against the booted emulator.
        subprocess.run(cmd(adb, "shell", "input", "keyevent", "82"), check=False)

        repo_root = Path(__file__).resolve().parents[3]
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
        stop_emulator(adb, proc)
        subprocess.run(cmd(avdmanager, "delete", "avd", "-n", AVD_NAME), check=False,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    if test_exit != 0:
        print(f"api37 connectedDebugAndroidTest failed (exit {test_exit}).", file=sys.stderr)
        return test_exit
    print("api37 E2E passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
