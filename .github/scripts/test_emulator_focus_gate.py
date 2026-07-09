# SPDX-License-Identifier: GPL-3.0-or-later
"""Unit tests for the pure readiness parser of emulator_focus_gate.py (no adb, no emulator).

Covers the decision core that decides whether a booted emulator is ready for a focus-dependent
instrumented UI suite (issue #468): interactive (``mWakefulness=Awake``) AND a real window holds
input focus (``mCurrentFocus`` is a non-null ``Window{...}``). The window-focus flake this guards
against is exactly the "awake but mCurrentFocus=null" state, so that case must read NOT ready."""

from __future__ import annotations

import unittest

import emulator_focus_gate as gate

# A ``dumpsys power`` where the display is interactive vs. asleep.
POWER_AWAKE = "Power Manager State:\n  mWakefulness=Awake\n  mWakefulnessChanging=false\n"
POWER_ASLEEP = "Power Manager State:\n  mWakefulness=Asleep\n  mWakefulnessChanging=false\n"

# ``dumpsys window`` with a focused app window (the healthy state RootViewPicker needs)...
WINDOW_FOCUSED = (
    "  mCurrentFocus=Window{23e192a u0 org.libremail.app/org.libremail.MainActivity}\n"
    "  mFocusedApp=ActivityRecord{a1 u0 org.libremail.app/.MainActivity t9}\n"
    "  mDreamingLockscreen=false\n"
)
# ...and the flake state: interactive-parse aside, NO window holds focus.
WINDOW_NO_FOCUS = "  mCurrentFocus=null\n  mFocusedApp=null\n  mDreamingLockscreen=true\n"


class AwakeParsingTests(unittest.TestCase):
    def test_mwakefulness_awake(self) -> None:
        self.assertTrue(gate._is_awake(POWER_AWAKE))

    def test_mwakefulness_asleep(self) -> None:
        self.assertFalse(gate._is_awake(POWER_ASLEEP))

    def test_display_power_state_on_fallback(self) -> None:
        self.assertTrue(gate._is_awake("Display Power: state=ON"))

    def test_minteractive_fallback(self) -> None:
        self.assertTrue(gate._is_awake("mInteractive=true"))

    def test_empty_is_not_awake(self) -> None:
        self.assertFalse(gate._is_awake(""))


class FocusParsingTests(unittest.TestCase):
    def test_non_null_current_focus(self) -> None:
        state, value = gate._focus(WINDOW_FOCUSED)
        self.assertEqual(state, "focused")
        self.assertTrue(value.startswith("Window{"))

    def test_null_current_focus(self) -> None:
        self.assertEqual(gate._focus(WINDOW_NO_FOCUS), ("unfocused", "null"))

    def test_focused_window_fallback_field(self) -> None:
        state, value = gate._focus("mFocusedWindow=Window{deadbeef u0 launcher}\n")
        self.assertEqual(state, "focused")
        self.assertEqual(value, "Window{deadbeef")

    def test_absent_focus_field_is_unknown(self) -> None:
        self.assertEqual(gate._focus("no focus fields here"), ("unknown", ""))


class KeyguardParsingTests(unittest.TestCase):
    def test_showing(self) -> None:
        self.assertEqual(gate._keyguard("mDreamingLockscreen=true"), "showing")

    def test_not_showing(self) -> None:
        self.assertEqual(gate._keyguard("isKeyguardShowing=false"), "not_showing")

    def test_unknown(self) -> None:
        self.assertEqual(gate._keyguard("nothing relevant"), "unknown")


class EvaluateReadinessTests(unittest.TestCase):
    def test_awake_and_focused_is_ready(self) -> None:
        result = gate.evaluate_readiness(POWER_AWAKE, WINDOW_FOCUSED)
        self.assertTrue(result.ready)
        self.assertTrue(result.awake)
        self.assertEqual(result.focus_state, "focused")

    def test_the_flake_awake_but_no_focus_is_not_ready(self) -> None:
        # The exact issue #468 signature: display parses/awake but no window has focus.
        result = gate.evaluate_readiness(POWER_AWAKE, WINDOW_NO_FOCUS)
        self.assertFalse(result.ready)

    def test_asleep_even_with_focus_is_not_ready(self) -> None:
        result = gate.evaluate_readiness(POWER_ASLEEP, WINDOW_FOCUSED)
        self.assertFalse(result.ready)

    def test_unknown_focus_but_awake_and_keyguard_gone_is_ready(self) -> None:
        # Fallback so a dump format without mCurrentFocus can't hang the gate forever.
        result = gate.evaluate_readiness(POWER_AWAKE, "mDreamingLockscreen=false")
        self.assertTrue(result.ready)

    def test_unknown_focus_and_keyguard_showing_is_not_ready(self) -> None:
        result = gate.evaluate_readiness(POWER_AWAKE, "mDreamingLockscreen=true")
        self.assertFalse(result.ready)

    def test_unknown_focus_and_keyguard_unknown_is_not_ready(self) -> None:
        result = gate.evaluate_readiness(POWER_AWAKE, "")
        self.assertFalse(result.ready)

    def test_summary_is_human_readable(self) -> None:
        summary = gate.evaluate_readiness(POWER_AWAKE, WINDOW_FOCUSED).summary
        self.assertIn("awake=True", summary)
        self.assertIn("focus=focused", summary)


if __name__ == "__main__":
    unittest.main()
