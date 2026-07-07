# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for the cold-fetch pause-hook A/B scenario (issue #405).

Everything runs against fakes (adb / breadcrumb tailer / fetch-gate) so the whole flow --
pre-arm halt, sign-in detection, halt confirmation, cold opens, resume, warm opens, and the
always-resume restore -- is exercised without a device, plus the ``--dry-run`` path end to end
through :func:`perf_harness.main`.
"""

import contextlib
import glob
import io
import os
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import breadcrumbs  # noqa: E402
import fetchgate  # noqa: E402
import perf_harness  # noqa: E402
import report  # noqa: E402
import scenarios  # noqa: E402
import uidump  # noqa: E402
from report import ReaderOpenRow  # noqa: E402

PKG = "org.libremail.app"
COMPONENT = "org.libremail.app/org.libremail.MainActivity"
FIX = os.path.join(os.path.dirname(__file__), "fixtures")

SYNC_ALL = "07-06 15:00:06.896 15192 15217 I MailSyncer: sync all: 1 accounts"
GATE_SKIP = "07-06 15:00:07.100 15192 15217 I MailSyncer: prefetch skipped: fetch-gate paused"

COLD_1 = "\n".join(
    [
        "07-06 15:01:00.000 10261 15306 D ImapPerf: body-fetch select=2566ms body=14253ms "
        "flag=2356ms rfc822=60457B chars=51610 att=0",
        "07-06 15:01:00.100 10261 15306 D ImapPerf: body-fetch connect=0ms work=26211ms live=3",
        "07-06 15:01:00.200 10261 15306 I MailReader: openMessage imap:94058a folder=INBOX "
        "fetchedBody=true took=31227ms",
        "07-06 15:01:00.300 10261 10261 D Reader  : reader ready took=31617ms html=true inline=0",
    ]
)
COLD_2 = "\n".join(
    [
        "07-06 15:01:40.000 10261 15306 D ImapPerf: body-fetch select=2000ms body=13000ms "
        "flag=2000ms rfc822=59000B chars=50000 att=0",
        "07-06 15:01:40.100 10261 15306 D ImapPerf: body-fetch connect=0ms work=27000ms live=3",
        "07-06 15:01:40.200 10261 15306 I MailReader: openMessage imap:94058a folder=INBOX "
        "fetchedBody=true took=32059ms",
        "07-06 15:01:40.300 10261 10261 D Reader  : reader ready took=32100ms html=true inline=0",
    ]
)
WARM_1 = "\n".join(
    [
        "07-06 15:03:00.000 10261 15306 I MailReader: openMessage imap:94058a folder=INBOX "
        "fetchedBody=false took=21ms",
        "07-06 15:03:00.050 10261 10261 D Reader  : reader ready took=82ms html=true inline=0",
    ]
)
WARM_2 = "\n".join(
    [
        "07-06 15:03:10.000 10261 15306 I MailReader: openMessage imap:94058a folder=INBOX "
        "fetchedBody=false took=18ms",
        "07-06 15:03:10.050 10261 10261 D Reader  : reader ready took=70ms html=true inline=0",
    ]
)


def _mailbox_xml():
    with open(os.path.join(FIX, "ui_mailbox.xml"), encoding="utf-8") as fh:
        return fh.read()


# --------------------------------------------------------------------------- #
# Fakes
# --------------------------------------------------------------------------- #
class FakeTailer:
    """Yields scripted chunks, one per ``read_new()`` (empty once exhausted)."""

    def __init__(self, chunks):
        self._chunks = list(chunks)
        self.marks = 0

    def mark(self):
        self.marks += 1

    def read_new(self):
        return self._chunks.pop(0) if self._chunks else ""


class RaisingTailer:
    def mark(self):
        pass

    def read_new(self):
        raise RuntimeError("logcat pipe broke")


class FakeAdb:
    """Minimal adb stand-in for the UI-driving parts of the scenario."""

    def __init__(self, dump_xml, package=PKG):
        self.package = package
        self.dry_run = False
        self.dump_xml = dump_xml
        self.taps = []
        self.keyevents = []
        self.swipes = 0

    def uiautomator_dump(self):
        return self.dump_xml

    def input_tap(self, x, y):
        self.taps.append((x, y))

    def input_keyevent(self, key):
        self.keyevents.append(key)

    def input_swipe(self, *args):
        self.swipes += 1

    def settle(self, seconds):
        pass

    def wake(self):
        pass

    def stay_on(self, on=True):
        pass


class FakeGate:
    """Records pause/resume/query and returns canned read-backs."""

    _PAUSED = fetchgate.BroadcastResult(0, "paused=[backfill,prefetch]", frozenset({"backfill", "prefetch"}))
    _CLEARED = fetchgate.BroadcastResult(0, "paused=[]", frozenset())

    def __init__(self):
        self.pauses = []
        self.resumes = []
        self.queries = []

    def pause(self, scope=fetchgate.DEFAULT_SCOPE):
        self.pauses.append(scope)
        return self._PAUSED

    def resume(self, scope=fetchgate.DEFAULT_SCOPE):
        self.resumes.append(scope)
        return self._CLEARED

    def query(self, scope=fetchgate.DEFAULT_SCOPE):
        self.queries.append(scope)
        return self._PAUSED


def _noop(_msg):
    pass


# --------------------------------------------------------------------------- #
# Breadcrumb control-signal matchers
# --------------------------------------------------------------------------- #
class TestControlSignalMatchers(unittest.TestCase):
    def test_match_sync_all(self):
        self.assertEqual(breadcrumbs.match_sync_all(SYNC_ALL), 1)
        self.assertEqual(
            breadcrumbs.match_sync_all(
                "07-06 15:00:06.896 15192 15217 I MailSyncer: sync all: 3 accounts"
            ),
            3,
        )

    def test_match_sync_all_rejects_other_lines(self):
        self.assertIsNone(breadcrumbs.match_sync_all(GATE_SKIP))
        self.assertIsNone(breadcrumbs.match_sync_all(COLD_1.splitlines()[0]))
        self.assertIsNone(breadcrumbs.match_sync_all("garbage"))

    def test_is_fetch_gate_skip(self):
        self.assertTrue(breadcrumbs.is_fetch_gate_skip(GATE_SKIP))
        self.assertTrue(
            breadcrumbs.is_fetch_gate_skip(
                "07-06 15:00:07.100 15192 15217 I MailBackfiller: prefetch skipped: fetch-gate paused"
            )
        )

    def test_is_fetch_gate_skip_rejects_other_lines(self):
        self.assertFalse(breadcrumbs.is_fetch_gate_skip(SYNC_ALL))
        self.assertFalse(
            breadcrumbs.is_fetch_gate_skip(
                "07-06 15:00:07.100 15192 15217 I Other: prefetch skipped: fetch-gate paused"
            )
        )


# --------------------------------------------------------------------------- #
# Wait helpers
# --------------------------------------------------------------------------- #
class TestWaitHelpers(unittest.TestCase):
    def test_wait_for_sign_in_detects(self):
        tailer = FakeTailer([SYNC_ALL])
        self.assertEqual(scenarios.wait_for_sign_in(tailer, 5.0, _noop), 1)

    def test_wait_for_sign_in_timeout(self):
        tailer = FakeTailer([])
        self.assertIsNone(scenarios.wait_for_sign_in(tailer, 0.0, _noop))

    def test_wait_for_gate_skip_detects(self):
        tailer = FakeTailer(["noise\n" + GATE_SKIP])
        self.assertTrue(scenarios.wait_for_gate_skip(tailer, 5.0, _noop))

    def test_wait_for_gate_skip_timeout(self):
        self.assertFalse(scenarios.wait_for_gate_skip(FakeTailer([]), 0.0, _noop))

    def test_wait_for_open_breadcrumb_returns_all_events(self):
        events = scenarios.wait_for_open_breadcrumb(FakeTailer([COLD_1]), 5.0, _noop)
        kinds = [type(e).__name__ for e in events]
        self.assertIn("OpenMessage", kinds)
        self.assertIn("BodyFetch", kinds)
        self.assertIn("ImapPerfOp", kinds)

    def test_wait_for_open_breadcrumb_timeout_returns_partial(self):
        # A chunk with perf lines but no openMessage -> returns what it saw, no hang.
        partial = COLD_1.splitlines()[0]
        events = scenarios.wait_for_open_breadcrumb(FakeTailer([partial]), 0.0, _noop)
        self.assertFalse(any(isinstance(e, breadcrumbs.OpenMessage) for e in events))

    def test_wait_for_header_sync(self):
        adb = FakeAdb(_mailbox_xml())
        self.assertTrue(scenarios.wait_for_header_sync(adb, PKG, 5.0, _noop))

    def test_wait_for_header_sync_timeout_no_rows(self):
        adb = FakeAdb(_mailbox_xml().replace('scrollable="true"', 'scrollable="false"'))
        self.assertFalse(scenarios.wait_for_header_sync(adb, PKG, 0.0, _noop))


# --------------------------------------------------------------------------- #
# Row-selection hardening (subsumes #392)
# --------------------------------------------------------------------------- #
class TestRowHardening(unittest.TestCase):
    HARDENING_XML = (
        "<?xml version='1.0' encoding='UTF-8'?>"
        '<hierarchy rotation="0">'
        '<node class="android.view.View" package="org.libremail.app" scrollable="true" '
        'clickable="false" long-clickable="false" enabled="true" bounds="[0,0][1344,2000]">'
        # A real message row (has a multi-char text label).
        '<node class="android.view.View" package="org.libremail.app" clickable="true" '
        'long-clickable="true" enabled="true" bounds="[0,0][1344,200]">'
        '<node class="android.widget.TextView" package="org.libremail.app" text="Alice" '
        'clickable="false" long-clickable="false" bounds="[10,10][300,60]"/>'
        '<node class="android.widget.TextView" package="org.libremail.app" text="Hello there" '
        'clickable="false" long-clickable="false" bounds="[10,70][900,120]"/>'
        "</node>"
        # A non-message tappable row: only a single-letter monogram, no real label.
        '<node class="android.view.View" package="org.libremail.app" clickable="true" '
        'long-clickable="true" enabled="true" bounds="[0,200][1344,400]">'
        '<node class="android.widget.TextView" package="org.libremail.app" text="X" '
        'clickable="false" long-clickable="false" bounds="[10,210][60,260]"/>'
        "</node>"
        "</node></hierarchy>"
    )

    def test_non_message_rows_skipped(self):
        root = uidump.parse_dump(self.HARDENING_XML)
        rows = uidump.find_message_rows(root, PKG)
        self.assertEqual(len(rows), 1)
        self.assertIn("Alice", rows[0].label)

    def test_existing_mailbox_still_three_rows(self):
        # Hardening must not drop legitimate rows from the real fixture.
        root = uidump.parse_dump(_mailbox_xml())
        self.assertEqual(len(uidump.find_message_rows(root, PKG)), 3)


# --------------------------------------------------------------------------- #
# The scenario
# --------------------------------------------------------------------------- #
class TestColdFetchScenario(unittest.TestCase):
    def _run(self, adb, tailer, gate, **kw):
        with patch("time.sleep"), patch.object(scenarios, "OPEN_TIMEOUT_S", 1.0):
            return scenarios.cold_fetch_ab(
                adb,
                PKG,
                COMPONENT,
                tailer,
                gate,
                count=3,
                log=_noop,
                sign_in_timeout_s=5.0,
                gate_confirm_timeout_s=5.0,
                header_sync_timeout_s=5.0,
                **kw,
            )

    def test_happy_path(self):
        adb = FakeAdb(_mailbox_xml())
        tailer = FakeTailer([SYNC_ALL, GATE_SKIP, COLD_1, COLD_2, WARM_1, WARM_2])
        gate = FakeGate()
        results = self._run(adb, tailer, gate)

        # Flow signals.
        self.assertEqual(results["sign_in_accounts"], 1)
        self.assertTrue(results["gate_confirmed"])
        self.assertTrue(results["header_ready"])

        # Cold opens: two uncached, connect=0ms (reuse), throttle-level work.
        cold = results["cold"]
        self.assertEqual(len(cold), 2)
        self.assertTrue(all(not r.cached for r in cold))
        self.assertEqual([r.took_ms for r in cold], [31227, 32059])
        self.assertEqual([r.connect_ms for r in cold], [0, 0])
        self.assertEqual([r.work_ms for r in cold], [26211, 27000])

        # Warm opens: same messages, now cached and fast.
        warm = results["warm"]
        self.assertEqual(len(warm), 2)
        self.assertTrue(all(r.cached for r in warm))
        self.assertEqual([r.took_ms for r in warm], [21, 18])

        # Gate lifecycle: pre-armed once, resumed for warm phase AND in the finally.
        self.assertEqual(gate.pauses, [fetchgate.DEFAULT_SCOPE])
        self.assertEqual(gate.resumes, [fetchgate.DEFAULT_SCOPE, fetchgate.DEFAULT_SCOPE])
        self.assertEqual(results["gate_prearm"].paused, frozenset({"backfill", "prefetch"}))
        self.assertEqual(results["gate_restored"].paused, frozenset())

        # Two distinct messages were tapped in each phase (4 opens -> 4 BACK presses).
        self.assertEqual(adb.keyevents.count("KEYCODE_BACK"), 4)

    def test_always_resumes_on_error(self):
        adb = FakeAdb(_mailbox_xml())
        gate = FakeGate()
        with self.assertRaises(RuntimeError):
            self._run(adb, RaisingTailer(), gate)
        # Pre-armed, then the finally cleared the gate despite the mid-run exception.
        self.assertEqual(gate.pauses, [fetchgate.DEFAULT_SCOPE])
        self.assertEqual(gate.resumes, [fetchgate.DEFAULT_SCOPE])

    def test_dry_run_issues_pause_resume_plan(self):
        logged = []
        real = perf_harness.Adb(package=PKG, dry_run=True, logger=logged.append)
        gate = fetchgate.FetchGate(real, log=logged.append)
        results = scenarios.cold_fetch_ab(
            real, PKG, COMPONENT, scenarios._NullTailer(), gate, count=3, log=logged.append
        )
        plan = "\n".join(logged)
        self.assertIn("--es action pause", plan)
        self.assertIn("--es action resume", plan)
        self.assertIn("--es action query", plan)
        self.assertTrue(results["cold"][0].skipped)
        self.assertTrue(results["warm"][0].skipped)


# --------------------------------------------------------------------------- #
# The report renderer
# --------------------------------------------------------------------------- #
class TestColdFetchReport(unittest.TestCase):
    def _results(self):
        paused = fetchgate.BroadcastResult(0, "paused=[backfill,prefetch]", frozenset({"backfill", "prefetch"}))
        cleared = fetchgate.BroadcastResult(0, "paused=[]", frozenset())
        return {
            "gate_prearm": paused,
            "sign_in_accounts": 1,
            "gate_confirmed": True,
            "header_ready": True,
            "cold": [
                ReaderOpenRow(index=1, label="A1", cached=False, took_ms=31227, connect_ms=0, work_ms=26211),
                ReaderOpenRow(index=2, label="A2", cached=False, took_ms=32059, connect_ms=0, work_ms=27000),
            ],
            "warm": [
                ReaderOpenRow(index=1, label="A1", cached=True, took_ms=21),
                ReaderOpenRow(index=2, label="A2", cached=True, took_ms=18),
            ],
            "gate_resume": cleared,
            "gate_restored": cleared,
        }

    def test_render_sections_and_proofs(self):
        md = report.render_cold_fetch_ab(self._results())
        self.assertIn("Fetch-gate A/B control", md)
        self.assertIn("paused=[backfill,prefetch]", md)
        self.assertIn("Cold opens", md)
        self.assertIn("Warm opens", md)
        # connect=0ms reuse proof, throttle signature, and the cold/warm speedup.
        self.assertIn("connect=0ms on 2/2 cold opens (reuse active)", md)
        self.assertIn("throttle signature", md)
        self.assertIn("Speedup", md)
        self.assertIn("debug-build-only", md)

    def test_render_handles_empty_dry_run_results(self):
        results = {
            "gate_prearm": fetchgate.BroadcastResult(None, None, frozenset()),
            "cold": [ReaderOpenRow(index=1, label="<dry-run>", skipped=True, reason="dry-run")],
            "warm": [ReaderOpenRow(index=1, label="<dry-run>", skipped=True, reason="dry-run")],
            "gate_restored": fetchgate.BroadcastResult(None, None, frozenset()),
        }
        md = report.render_cold_fetch_ab(results)  # must not raise on missing/empty fields
        self.assertIn("Fetch-gate A/B control", md)
        self.assertIn("no read-back", md)


# --------------------------------------------------------------------------- #
# End-to-end --dry-run through the CLI entry point
# --------------------------------------------------------------------------- #
class TestDryRunThroughMain(unittest.TestCase):
    def test_cold_fetch_ab_dry_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            with contextlib.redirect_stdout(io.StringIO()):
                rc = perf_harness.main(["cold-fetch-ab", "--dry-run", "--out", tmp])
            self.assertEqual(rc, 0)
            tables = glob.glob(os.path.join(tmp, "*", "timing-tables.md"))
            self.assertEqual(len(tables), 1)
            with open(tables[0], encoding="utf-8") as fh:
                doc = fh.read()
            self.assertIn("Fetch-gate A/B control", doc)
            self.assertIn("Cold vs warm (A/B)", doc)


if __name__ == "__main__":
    unittest.main()
