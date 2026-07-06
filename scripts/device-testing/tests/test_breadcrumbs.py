# SPDX-License-Identifier: GPL-3.0-or-later
"""Unit tests for the breadcrumb parser -- validated against the manual run's real captures.

The fixture ``fixtures/perf-extract-sample.log`` is a verbatim slice of the manual run's
``perf-extract-ALL.log`` (plus the Outlook control), so these tests assert that the parser
reproduces the exact figures in the hand-written ``timing-tables.md``.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import breadcrumbs as bc  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "perf-extract-sample.log")


def _load_fixture_lines():
    with open(FIXTURE, encoding="utf-8") as fh:
        return fh.read().splitlines()


class TestLogcatLine(unittest.TestCase):
    def test_parses_threadtime_fields(self):
        line = "07-05 18:07:20.817 10261 15306 I MailReader: openMessage imap:94058a folder=INBOX fetchedBody=true took=31227ms"
        parsed = bc.parse_logcat_line(line)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.date, "07-05")
        self.assertEqual(parsed.time, "18:07:20.817")
        self.assertEqual(parsed.pid, 10261)
        self.assertEqual(parsed.tid, 15306)
        self.assertEqual(parsed.level, "I")
        self.assertEqual(parsed.tag, "MailReader")
        self.assertTrue(parsed.message.startswith("openMessage imap:94058a"))
        self.assertEqual(parsed.timestamp, "07-05 18:07:20.817")

    def test_tolerates_space_padded_tag(self):
        # logcat pads the tag column, e.g. "Reader  :".
        line = "07-05 17:57:39.041 10261 10261 D Reader  : reader ready took=82ms html=true inline=0"
        parsed = bc.parse_logcat_line(line)
        self.assertEqual(parsed.tag, "Reader")
        self.assertEqual(parsed.message, "reader ready took=82ms html=true inline=0")

    def test_non_threadtime_returns_none(self):
        self.assertIsNone(bc.parse_logcat_line("--------- beginning of main"))
        self.assertIsNone(bc.parse_logcat_line(""))

    def test_is_perf_line(self):
        self.assertTrue(
            bc.is_perf_line("07-05 17:55:38.343 10261 10423 D ImapPerf: prefetch-body connect=857ms work=2150ms live=3")
        )
        self.assertFalse(
            bc.is_perf_line("07-05 15:00:06.896 15192 15217 I MailSyncer: sync all: 1 accounts")
        )
        self.assertFalse(bc.is_perf_line("garbage"))


class TestMessageParsers(unittest.TestCase):
    def test_imap_perf_generic_op(self):
        ev = bc.parse_imap_perf("prefetch-body connect=857ms work=2150ms live=3")
        self.assertIsInstance(ev, bc.ImapPerfOp)
        self.assertEqual((ev.op, ev.connect_ms, ev.work_ms, ev.live), ("prefetch-body", 857, 2150, 3))

    def test_imap_perf_backfill_page(self):
        ev = bc.parse_imap_perf("backfill-page connect=4366ms work=71989ms live=2")
        self.assertEqual((ev.op, ev.connect_ms, ev.work_ms, ev.live), ("backfill-page", 4366, 71989, 2))

    def test_imap_perf_body_fetch_op(self):
        ev = bc.parse_imap_perf("body-fetch connect=2624ms work=26211ms live=3")
        self.assertIsInstance(ev, bc.ImapPerfOp)
        self.assertEqual(ev.op, "body-fetch")

    def test_body_fetch_detail(self):
        ev = bc.parse_imap_perf(
            "body-fetch select=2566ms body=14253ms flag=2356ms rfc822=60457B chars=51610 att=0"
        )
        self.assertIsInstance(ev, bc.BodyFetch)
        self.assertEqual(ev.select_ms, 2566)
        self.assertEqual(ev.body_ms, 14253)
        self.assertEqual(ev.flag_ms, 2356)
        self.assertEqual(ev.rfc822_bytes, 60457)
        self.assertEqual(ev.chars, 51610)
        self.assertEqual(ev.att, 0)

    def test_body_fetch_throughput_matches_manual_table(self):
        # timing-tables.md reports 4.2 KB/s for A1 and 139.5 KB/s for O1.
        a1 = bc.parse_imap_perf(
            "body-fetch select=2566ms body=14253ms flag=2356ms rfc822=60457B chars=51610 att=0"
        )
        self.assertAlmostEqual(a1.body_kb_per_s, 4.24, places=2)
        o1 = bc.parse_imap_perf(
            "body-fetch select=185ms body=1355ms flag=78ms rfc822=189086B chars=82986 att=0"
        )
        self.assertAlmostEqual(o1.body_kb_per_s, 139.5, places=1)

    def test_open_message_uncached(self):
        ev = bc.parse_open_message("openMessage imap:94058a folder=INBOX fetchedBody=true took=31227ms")
        self.assertEqual(ev.account_ref, "imap:94058a")
        self.assertEqual(ev.folder, "INBOX")
        self.assertTrue(ev.fetched_body)
        self.assertEqual(ev.took_ms, 31227)

    def test_open_message_cached(self):
        ev = bc.parse_open_message("openMessage outlook:6b54d6 folder=INBOX fetchedBody=false took=4ms")
        self.assertEqual(ev.account_ref, "outlook:6b54d6")
        self.assertFalse(ev.fetched_body)
        self.assertEqual(ev.took_ms, 4)

    def test_reader_ready(self):
        ev = bc.parse_reader_ready("reader ready took=31617ms html=true inline=0")
        self.assertEqual(ev.took_ms, 31617)
        self.assertTrue(ev.html)
        self.assertEqual(ev.inline, 0)

    def test_backfill_progress(self):
        ev = bc.parse_backfill("backfill outlook:6b54d6 folder=INBOX pages=20 complete=false")
        self.assertIsInstance(ev, bc.BackfillProgress)
        self.assertEqual(ev.account_ref, "outlook:6b54d6")
        self.assertEqual(ev.pages, 20)
        self.assertFalse(ev.complete)

    def test_backfill_slice_start_and_done(self):
        start = bc.parse_backfill("backfill slice: maxBatches=20")
        self.assertIsInstance(start, bc.BackfillSliceStart)
        self.assertEqual(start.max_batches, 20)
        done = bc.parse_backfill("backfill slice done: moreWork=true")
        self.assertIsInstance(done, bc.BackfillSliceDone)
        self.assertTrue(done.more_work)

    def test_unparseable_messages_return_none(self):
        self.assertIsNone(bc.parse_imap_perf("nonsense here"))
        self.assertIsNone(bc.parse_open_message("openMessage missing fields"))
        self.assertIsNone(bc.parse_reader_ready("reader not ready"))
        self.assertIsNone(bc.parse_backfill("backfill mystery"))


class TestParseBreadcrumbDispatch(unittest.TestCase):
    def test_dispatch_attaches_source_line(self):
        line = "07-05 18:07:18.435 10261 15306 D ImapPerf: body-fetch connect=2624ms work=26211ms live=3"
        ev = bc.parse_breadcrumb(line)
        self.assertIsInstance(ev, bc.ImapPerfOp)
        self.assertIsNotNone(ev.line)
        self.assertEqual(ev.line.pid, 10261)
        self.assertEqual(ev.timestamp, "07-05 18:07:18.435")

    def test_dispatch_ignores_non_perf_tags(self):
        self.assertIsNone(
            bc.parse_breadcrumb("07-05 15:00:06.896 15192 15217 I MailSyncer: sync all: 1 accounts")
        )


class TestFixtureStream(unittest.TestCase):
    def setUp(self):
        self.lines = _load_fixture_lines()
        self.events = list(bc.iter_events(self.lines))

    def test_all_fixture_lines_are_perf_lines(self):
        self.assertEqual(sum(1 for line in self.lines if bc.is_perf_line(line)), len(self.lines))

    def test_every_line_parses(self):
        self.assertEqual(len(self.events), len(self.lines))

    def test_event_type_mix(self):
        kinds = [type(e).__name__ for e in self.events]
        # 3 prefetch/backfill-page ops + 2 body-fetch ops + 1 imap op = 6.
        self.assertEqual(kinds.count("ImapPerfOp"), 6)
        self.assertEqual(kinds.count("BodyFetch"), 2)
        self.assertEqual(kinds.count("OpenMessage"), 3)
        self.assertEqual(kinds.count("ReaderReady"), 3)
        self.assertEqual(kinds.count("BackfillProgress"), 1)
        self.assertEqual(kinds.count("BackfillSliceStart"), 1)
        self.assertEqual(kinds.count("BackfillSliceDone"), 1)


class TestCorrelateOpens(unittest.TestCase):
    """correlate_opens must reproduce the manual timing-tables rows exactly."""

    def setUp(self):
        events = list(bc.iter_events(_load_fixture_lines()))
        self.samples = bc.correlate_opens(events)

    def test_three_opens_detected(self):
        self.assertEqual(len(self.samples), 3)

    def test_cached_gmail_open(self):
        s = self.samples[0]
        self.assertEqual(s.account_ref, "imap:94058a")
        self.assertTrue(s.cached)
        self.assertEqual(s.took_ms, 21)
        self.assertIsNone(s.body_fetch)
        self.assertEqual(s.reader_ready.took_ms, 82)

    def test_gmail_A1_row_matches_table1(self):
        s = self.samples[1]
        self.assertEqual(s.account_ref, "imap:94058a")
        self.assertFalse(s.cached)
        self.assertEqual(s.took_ms, 31227)
        self.assertEqual(s.reader_ready.took_ms, 31617)
        self.assertEqual(s.rfc822_bytes, 60457)
        self.assertEqual(s.body_fetch.select_ms, 2566)
        self.assertEqual(s.body_fetch.body_ms, 14253)
        self.assertEqual(s.body_fetch.flag_ms, 2356)
        self.assertEqual(s.body_fetch_op.connect_ms, 2624)
        self.assertEqual(s.body_fetch_op.work_ms, 26211)
        self.assertEqual(s.body_fetch_op.live, 3)
        self.assertAlmostEqual(s.body_kb_per_s, 4.24, places=2)

    def test_outlook_O1_row_matches_table2(self):
        s = self.samples[2]
        self.assertEqual(s.account_ref, "outlook:6b54d6")
        self.assertFalse(s.cached)
        self.assertEqual(s.took_ms, 2934)
        self.assertEqual(s.reader_ready.took_ms, 2939)
        self.assertEqual(s.rfc822_bytes, 189086)
        self.assertEqual(s.body_fetch_op.connect_ms, 772)
        self.assertAlmostEqual(s.body_kb_per_s, 139.5, places=1)


if __name__ == "__main__":
    unittest.main()
