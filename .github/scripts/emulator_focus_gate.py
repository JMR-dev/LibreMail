#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""emulator_focus_gate.py -- make a booted emulator reliably grant the app window focus
BEFORE an instrumented UI suite runs, then GATE on that state (issue #468).

WHY THIS EXISTS  (issue #468 -- the environmental app-window-focus flake)
------------------------------------------------------------------------
Intermittently, on the CI emulator the launched activity window has
``has-window-focus=false`` for the WHOLE instrumented run, so Espresso's ``RootViewPicker``
(used by ``onView(...).check()``, ``Intents.intended()``, ``Espresso.pressBack()`` and
focus-dependent clipboard reads) waits 10s for a focused root and times out --
``RootViewWithoutFocusException``. It fails EVERY window-focus-dependent test at once while
the ~280 pure-Compose semantics tests (which do not need window focus) pass. Root-cause
evidence from a failing ``E2E (35)`` leg (PR #470, run 28985259521): across the entire
captured logcat ``has-window-focus=true`` appears ZERO times and both the first attempt and
the once-retry fail identically -- i.e. the window NEVER gains focus for the session, a
persistent environmental state, not a per-test transient.

The prior mitigation was a single fire-and-forget ``adb shell input keyevent 82`` (MENU)
right after ``sys.boot_completed=1``. On modern Android (API 30+) MENU does NOT reliably
dismiss the keyguard, and when it is delivered before SystemUI/keyguard finishes coming up it
is simply dropped ("no focused window"). The insecure keyguard / non-interactive display then
persists and no app window ever takes focus -- hence the intermittent, whole-leg flake.

WHAT THIS DOES
--------------
A single shared mechanism invoked identically by every E2E job (the ``e2e`` API 29-36 matrix
AND the ``e2e-preview`` API 37 job in ``.github/workflows/ci.yml``) and by the local preflight
runners (``local_instrumented.py`` / ``api37_e2e.py``), so the fix cannot drift between them:

  1. PREPARE the device so an app window CAN take focus, and keep it that way for the whole
     run (all best-effort; a missing service right after boot must never abort the leg):
       * ``input keyevent WAKEUP`` (224)  -- force the display INTERACTIVE (never toggles it
         off the way POWER would).
       * ``wm dismiss-keyguard``          -- dismiss the (insecure) keyguard now.
       * ``locksettings set-disabled true`` -- disable the lock screen for the session so it
         cannot re-curtain the app window mid-run.
       * ``svc power stayon true`` + a max ``screen_off_timeout`` -- never sleep during the run.
       * ``input keyevent 82`` (MENU)     -- legacy nudge, kept harmless for parity with #454.
       * zero the three animation scales   -- deterministic UI tests (this also gives the
         ``e2e-preview`` job the animation-disable the matrix already had -- uniformly).
  2. GATE: poll ``dumpsys power`` + ``dumpsys window`` until the device is interactive
     (``mWakefulness=Awake``) AND a real window holds input focus (``mCurrentFocus`` is a
     ``Window{...}``, not ``null``) -- i.e. the exact precondition ``RootViewPicker`` needs --
     re-issuing the wake / dismiss-keyguard nudges each iteration so a lost race self-heals.

The gate is SOFT: it waits up to ``--timeout`` seconds and then proceeds regardless, printing a
GitHub ``::warning::`` annotation and the final device state if it never confirmed focus (the
determinism comes from the PREPARE actions + the wait; a parsing quirk on some API level must
not convert an otherwise-fine leg into a hard failure -- the real tests remain the arbiter).
It always prints the final ``mWakefulness`` / ``mCurrentFocus`` / keyguard state so a genuine
environmental failure is diagnosable from the step log without downloading artifacts.

Pure standard library, cross-platform (Windows / Linux / macOS): ``adb`` is invoked via
subprocess. The readiness parser (``evaluate_readiness``) is a pure function, unit-tested by
``test_emulator_focus_gate.py`` (run by the ``traffic-control-tests`` CI job).
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import time
from typing import NamedTuple

# WAKEUP (not POWER): guarantees the display ends up INTERACTIVE. POWER (26) toggles, so it
# would turn an already-on display OFF. MENU (82) is kept only as a legacy parity nudge.
KEYCODE_WAKEUP = "224"
KEYCODE_MENU = "82"
# Max int -- effectively "never" auto-sleep the screen during the suite.
SCREEN_OFF_TIMEOUT_MS = "2147483647"
DEFAULT_TIMEOUT_S = 90
POLL_INTERVAL_S = 2


class Readiness(NamedTuple):
    """Outcome of parsing ``dumpsys power`` + ``dumpsys window`` for focus readiness."""

    ready: bool
    awake: bool
    focus_state: str  # 'focused' | 'unfocused' | 'unknown'
    focus_value: str  # the mCurrentFocus / mFocusedWindow token, or ''
    keyguard_state: str  # 'showing' | 'not_showing' | 'unknown'

    @property
    def summary(self) -> str:
        return (
            f"awake={self.awake} focus={self.focus_state}"
            f"({self.focus_value or '-'}) keyguard={self.keyguard_state}"
        )


def _is_awake(power_out: str) -> bool:
    """True if ``dumpsys power`` reports an INTERACTIVE display. ``mWakefulness=Awake`` is the
    stable signal across API 29-37; ``Display Power: state=ON`` / ``mInteractive=true`` are
    accepted as fallbacks for dump-format drift."""
    return bool(
        re.search(r"mWakefulness=Awake\b", power_out)
        or re.search(r"Display Power:\s*state=ON\b", power_out)
        or re.search(r"mInteractive=true\b", power_out)
    )


def _focus(window_out: str) -> tuple[str, str]:
    """Classify the current input focus from ``dumpsys window``.

    Returns ``(state, value)`` where state is 'focused' (a non-null ``Window{...}`` holds
    focus -- what RootViewPicker needs), 'unfocused' (focus is explicitly ``null`` -- asleep /
    keyguard-curtained / no focusable window), or 'unknown' (the field is absent on this dump
    format). ``mCurrentFocus`` is preferred; ``mFocusedWindow`` is the fallback field name."""
    tokens = re.findall(r"mCurrentFocus=(\S+)", window_out)
    if not tokens:
        tokens = re.findall(r"mFocusedWindow=(\S+)", window_out)
    if not tokens:
        return ("unknown", "")
    non_null = [t for t in tokens if t != "null"]
    if non_null:
        return ("focused", non_null[0])
    return ("unfocused", "null")


def _keyguard(window_out: str) -> str:
    """Best-effort keyguard state from ``dumpsys window``: 'showing' / 'not_showing' /
    'unknown'. Informational for the summary, plus a fallback readiness signal when the focus
    field is absent. Field names vary by API level, so several are accepted."""
    match = re.search(
        r"(?:mShowingLockscreen|mDreamingLockscreen|isKeyguardShowing|"
        r"mKeyguardShowing|keyguardShowing|mKeyguardOccluded)=(true|false)",
        window_out,
    )
    if not match:
        return "unknown"
    return "showing" if match.group(1) == "true" else "not_showing"


def evaluate_readiness(power_out: str, window_out: str) -> Readiness:
    """Pure decision core (unit-tested). The device is READY for a focus-dependent UI suite
    when it is interactive AND a real window holds input focus. When the focus field is absent
    on a given dump format, fall back to "interactive AND keyguard explicitly not showing" so a
    format quirk cannot hang the gate forever."""
    awake = _is_awake(power_out)
    focus_state, focus_value = _focus(window_out)
    keyguard_state = _keyguard(window_out)
    ready = awake and (
        focus_state == "focused"
        or (focus_state == "unknown" and keyguard_state == "not_showing")
    )
    return Readiness(ready, awake, focus_state, focus_value, keyguard_state)


def _adb_base(adb: str, serial: str | None) -> list[str]:
    return [adb, "-s", serial] if serial else [adb]


def _adb_quiet(adb: str, serial: str | None, *args: str) -> None:
    """Run an ``adb`` command, swallowing output and any error -- every prepare nudge is
    best-effort (a service can lose a race right after boot; a missing tool must not abort)."""
    try:
        subprocess.run(
            _adb_base(adb, serial) + list(args),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        pass


def _adb_capture(adb: str, serial: str | None, *args: str) -> str:
    try:
        return (
            subprocess.run(
                _adb_base(adb, serial) + list(args),
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            ).stdout
            or ""
        )
    except (OSError, subprocess.SubprocessError):
        return ""


def nudge_focus(adb: str, serial: str | None) -> None:
    """Wake the display + dismiss the keyguard. Cheap and idempotent, so it is re-issued every
    poll iteration to self-heal a nudge that lost the post-boot race with SystemUI/keyguard."""
    _adb_quiet(adb, serial, "shell", "input", "keyevent", KEYCODE_WAKEUP)
    _adb_quiet(adb, serial, "shell", "wm", "dismiss-keyguard")


def prepare_device(adb: str, serial: str | None) -> None:
    """One-time device preparation: disable the lock screen for the session, keep the screen on
    for the whole run, zero the animation scales for deterministic UI tests, and issue the first
    wake / dismiss-keyguard nudge. All best-effort."""
    print("focus-gate: preparing device (wake + dismiss-keyguard + stay-awake + no-animations)")
    nudge_focus(adb, serial)
    _adb_quiet(adb, serial, "shell", "input", "keyevent", KEYCODE_MENU)  # legacy #454 parity
    _adb_quiet(adb, serial, "shell", "locksettings", "set-disabled", "true")
    _adb_quiet(adb, serial, "shell", "svc", "power", "stayon", "true")
    _adb_quiet(adb, serial, "shell", "settings", "put", "system",
               "screen_off_timeout", SCREEN_OFF_TIMEOUT_MS)
    for scale in ("window_animation_scale", "transition_animation_scale",
                  "animator_duration_scale"):
        _adb_quiet(adb, serial, "shell", "settings", "put", "global", scale, "0.0")


def probe(adb: str, serial: str | None) -> Readiness:
    power_out = _adb_capture(adb, serial, "shell", "dumpsys", "power")
    window_out = _adb_capture(adb, serial, "shell", "dumpsys", "window")
    return evaluate_readiness(power_out, window_out)


def wait_for_focus(adb: str, serial: str | None, timeout: int, label: str) -> Readiness:
    """Prepare the device, then poll (re-nudging each iteration) until it is interactive with a
    focused window, or ``timeout`` seconds elapse. Returns the final Readiness (SOFT gate: the
    caller proceeds regardless -- see the module docstring)."""
    tag = f" [{label}]" if label else ""
    prepare_device(adb, serial)
    deadline = time.monotonic() + timeout
    last = probe(adb, serial)
    attempt = 0
    while True:
        if last.ready:
            elapsed = timeout - max(0, int(deadline - time.monotonic()))
            print(f"focus-gate{tag}: READY after ~{elapsed}s -- {last.summary}")
            return last
        if time.monotonic() >= deadline:
            print(f"::warning::focus-gate{tag}: window focus NOT confirmed within {timeout}s "
                  f"-- proceeding anyway -- {last.summary}")
            return last
        attempt += 1
        if attempt % 5 == 0:
            print(f"focus-gate{tag}: waiting for window focus -- {last.summary}")
        nudge_focus(adb, serial)
        time.sleep(POLL_INTERVAL_S)
        last = probe(adb, serial)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="emulator_focus_gate.py",
        description=(
            "Force a booted emulator to grant the app window focus (wake + dismiss-keyguard + "
            "stay-awake + no-animations) and gate on that state before an instrumented UI "
            "suite runs. Shared by CI's e2e / e2e-preview jobs and the local preflight runners "
            "(issue #468)."
        ),
    )
    parser.add_argument("--serial", default=None,
                        help="adb device serial (default: the single attached device).")
    parser.add_argument("--adb", default=None,
                        help="Path to adb (default: resolve from PATH). For callers that resolve "
                             "adb from the SDK rather than PATH (e.g. api37_e2e.py).")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT_S,
                        help=f"Max seconds to wait for window focus (default {DEFAULT_TIMEOUT_S}).")
    parser.add_argument("--label", default="",
                        help="Label for log lines (e.g. an API level), for multi-leg runs.")
    args = parser.parse_args(argv)

    adb = args.adb or shutil.which("adb")
    if not adb:
        # Non-fatal by contract: never turn a missing-tool hiccup into a red leg. The suite that
        # follows will surface a genuinely broken device.
        print("::warning::focus-gate: adb not on PATH -- skipping focus preparation/gate")
        return 0

    wait_for_focus(adb, args.serial, args.timeout, args.label)
    return 0


if __name__ == "__main__":
    sys.exit(main())
