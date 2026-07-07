# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for the debug FETCH_GATE pause-hook helpers (issue #405).

Cover the ordered-broadcast read-back parser and the :class:`fetchgate.FetchGate` wrapper,
mocking adb so the pause/resume/query flow is exercised without a device. The fake adb runs
the *real* :func:`adb.assert_safe` guard on every broadcast, so these tests also prove the
FETCH_GATE command the harness builds is one the safety wrapper accepts.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import adb  # noqa: E402
import fetchgate  # noqa: E402
from adb import Adb, AdbSafetyError  # noqa: E402

PKG = "org.libremail.app"

# A verbatim `am broadcast` read-back from the debug FetchGateReceiver (ordered delivery).
COMPLETED_PAUSED = (
    "Broadcasting: Intent { act=org.libremail.debug.FETCH_GATE flg=0x400000 "
    "cmp=org.libremail.app/org.libremail.debug.FetchGateReceiver (has extras) }\n"
    'Broadcast completed: result=0, data="paused=[backfill,prefetch]"\n'
)
COMPLETED_CLEARED = 'Broadcast completed: result=0, data="paused=[]"\n'


class RecordingAdb:
    """A fake Adb that validates every broadcast through the real guard, records it, and
    returns canned ``am broadcast`` stdout -- FetchGate end-to-end without a device."""

    def __init__(self, package=PKG, stdout=""):
        self.package = package
        self.dry_run = False
        self.calls = []
        self.stdout = stdout

    def broadcast(self, action, component, extras=None):
        args = ["shell", "am", "broadcast", "-a", action, "-n", component]
        for key, value in (extras or {}).items():
            args += ["--es", str(key), str(value)]
        adb.assert_safe(self.package, args)  # real guard -- raises on anything unsafe
        self.calls.append(args)
        return adb.AdbResult(args, 0, self.stdout, "")


class TestParsePausedScopes(unittest.TestCase):
    def test_two_scopes(self):
        self.assertEqual(
            fetchgate.parse_paused_scopes("paused=[backfill,prefetch]"),
            frozenset({"backfill", "prefetch"}),
        )

    def test_single_scope(self):
        self.assertEqual(fetchgate.parse_paused_scopes("paused=[backfill]"), frozenset({"backfill"}))

    def test_empty_gate(self):
        self.assertEqual(fetchgate.parse_paused_scopes("paused=[]"), frozenset())

    def test_none_and_garbage(self):
        self.assertEqual(fetchgate.parse_paused_scopes(None), frozenset())
        self.assertEqual(fetchgate.parse_paused_scopes(""), frozenset())
        self.assertEqual(fetchgate.parse_paused_scopes("no brackets here"), frozenset())


class TestParseBroadcastResult(unittest.TestCase):
    def test_paused_read_back(self):
        res = fetchgate.parse_broadcast_result(COMPLETED_PAUSED)
        self.assertEqual(res.result_code, 0)
        self.assertEqual(res.data, "paused=[backfill,prefetch]")
        self.assertEqual(res.paused, frozenset({"backfill", "prefetch"}))
        self.assertTrue(res.read_back)
        self.assertTrue(res.is_paused("prefetch"))

    def test_cleared_read_back(self):
        res = fetchgate.parse_broadcast_result(COMPLETED_CLEARED)
        self.assertEqual(res.result_code, 0)
        self.assertEqual(res.data, "paused=[]")
        self.assertEqual(res.paused, frozenset())
        self.assertTrue(res.read_back)
        self.assertFalse(res.is_paused("backfill"))

    def test_completion_without_data(self):
        res = fetchgate.parse_broadcast_result("Broadcast completed: result=0")
        self.assertEqual(res.result_code, 0)
        self.assertIsNone(res.data)
        self.assertEqual(res.paused, frozenset())
        self.assertFalse(res.read_back)

    def test_trailing_extras_not_swallowed(self):
        line = 'Broadcast completed: result=0, data="paused=[backfill]", extras: Bundle[...]'
        res = fetchgate.parse_broadcast_result(line)
        self.assertEqual(res.data, "paused=[backfill]")
        self.assertEqual(res.paused, frozenset({"backfill"}))

    def test_empty_output_is_no_read_back(self):
        res = fetchgate.parse_broadcast_result("")
        self.assertIsNone(res.result_code)
        self.assertIsNone(res.data)
        self.assertFalse(res.read_back)


class TestFetchGateHelper(unittest.TestCase):
    def test_pause_builds_targeted_broadcast_and_parses(self):
        fake = RecordingAdb(stdout=COMPLETED_PAUSED)
        gate = fetchgate.FetchGate(fake)
        state = gate.pause()
        self.assertEqual(state.paused, frozenset({"backfill", "prefetch"}))
        self.assertEqual(len(fake.calls), 1)
        args = fake.calls[0]
        # Correct action, component (our package) and default scope on the wire.
        self.assertEqual(args[:5], ["shell", "am", "broadcast", "-a", fetchgate.FETCH_GATE_ACTION])
        self.assertIn("-n", args)
        self.assertEqual(args[args.index("-n") + 1], f"{PKG}/{fetchgate.FETCH_GATE_RECEIVER}")
        self.assertIn("pause", args)
        self.assertIn("backfill,prefetch", args)

    def test_resume_and_query_actions(self):
        fake = RecordingAdb(stdout=COMPLETED_CLEARED)
        gate = fetchgate.FetchGate(fake)
        self.assertEqual(gate.resume().paused, frozenset())
        self.assertIn("resume", fake.calls[-1])
        gate.query()
        self.assertIn("query", fake.calls[-1])

    def test_custom_scope_passed_through(self):
        fake = RecordingAdb(stdout=COMPLETED_CLEARED)
        gate = fetchgate.FetchGate(fake)
        gate.pause("backfill")
        self.assertIn("backfill", fake.calls[-1])
        self.assertNotIn("backfill,prefetch", fake.calls[-1])

    def test_logs_read_back(self):
        fake = RecordingAdb(stdout=COMPLETED_PAUSED)
        logged = []
        gate = fetchgate.FetchGate(fake, log=logged.append)
        gate.pause()
        self.assertTrue(any("paused=[backfill,prefetch]" in m for m in logged))


class TestFetchGateThroughRealGuardedAdb(unittest.TestCase):
    """FetchGate over a real dry-run Adb: the guard runs, the exact plan is logged, no device."""

    def test_dry_run_issues_safe_command_and_no_read_back(self):
        logged = []
        real = Adb(serial="SERIAL1", package=PKG, dry_run=True, logger=logged.append)
        gate = fetchgate.FetchGate(real, log=logged.append)
        state = gate.pause()
        # Dry-run has no stdout -> no read-back, but also no exception (command was safe).
        self.assertFalse(state.read_back)
        plan = "\n".join(logged)
        self.assertIn("am broadcast", plan)
        self.assertIn("-a org.libremail.debug.FETCH_GATE", plan)
        self.assertIn(f"{PKG}/{fetchgate.FETCH_GATE_RECEIVER}", plan)
        self.assertIn("--es action pause", plan)
        self.assertIn("--es scope backfill,prefetch", plan)

    def test_foreign_component_is_refused_by_guard(self):
        real = Adb(package=PKG, dry_run=True)
        gate = fetchgate.FetchGate(real, receiver="com.other.app/.Evil")
        # component becomes "org.libremail.app/com.other.app/.Evil" -> comp pkg still ours,
        # so instead assert a genuinely foreign package target is refused:
        gate.component = "com.other.app/org.libremail.debug.FetchGateReceiver"
        with self.assertRaises(AdbSafetyError):
            gate.pause()


if __name__ == "__main__":
    unittest.main()
