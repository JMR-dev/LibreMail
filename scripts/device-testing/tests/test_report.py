# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for aggregation and the timing-tables markdown renderers."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import breadcrumbs as bc  # noqa: E402
import report  # noqa: E402

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "perf-extract-sample.log")


class TestAggregate(unittest.TestCase):
    def test_basic_stats(self):
        agg = report.aggregate([10, 20, 30])
        self.assertEqual(agg.n, 3)
        self.assertEqual(agg.mean, 20)
        self.assertEqual(agg.median, 20)
        self.assertEqual((agg.minimum, agg.maximum), (10, 30))

    def test_reproduces_manual_gmail_median(self):
        # timing-tables.md Condition A openMessage values -> median ~32.1 s.
        agg = report.aggregate([31227, 32059, 31721, 32148, 32949, 39728])
        self.assertEqual(agg.median, 32103.5)

    def test_empty(self):
        agg = report.aggregate([])
        self.assertEqual(agg.n, 0)
        self.assertIsNone(agg.mean)
        self.assertIsNone(agg.median)


class TestMdTable(unittest.TestCase):
    def test_table_shape(self):
        table = report.md_table(["a", "b"], [[1, 2], [3, None]])
        lines = table.splitlines()
        self.assertEqual(lines[0], "| a | b |")
        self.assertEqual(lines[1], "| --- | --- |")
        self.assertEqual(lines[2], "| 1 | 2 |")
        self.assertEqual(lines[3], "| 3 |  |")  # None renders empty


class TestColdOpen(unittest.TestCase):
    def test_render_includes_mean_row(self):
        samples = [
            report.ColdOpenSample(run=1, total_time_ms=523, wait_time_ms=526),
            report.ColdOpenSample(run=2, total_time_ms=384, wait_time_ms=386),
        ]
        md = report.render_cold_open(samples)
        self.assertIn("523", md)
        self.assertIn("**mean**", md)
        self.assertIn("454", md)  # mean total = 453.5 -> 454


class TestMessageOpen(unittest.TestCase):
    def setUp(self):
        with open(FIXTURE, encoding="utf-8") as fh:
            events = list(bc.iter_events(fh.read().splitlines()))
        self.samples = bc.correlate_opens(events)

    def test_from_open_sample_maps_fields(self):
        row = report.ReaderOpenRow.from_open_sample(self.samples[1], index=1, label="A1")
        self.assertEqual(row.took_ms, 31227)
        self.assertEqual(row.reader_ready_ms, 31617)
        self.assertEqual(row.rfc822_bytes, 60457)
        self.assertEqual(row.connect_ms, 2624)
        self.assertEqual(row.select_ms, 2566)
        self.assertFalse(row.cached)

    def test_render_message_open_table_and_aggregate(self):
        rows = [
            report.ReaderOpenRow.from_open_sample(s, index=i, label=f"m{i}")
            for i, s in enumerate(self.samples, start=1)
        ]
        md = report.render_message_open("Message open (uncached)", rows)
        self.assertIn("31227 ms", md)
        self.assertIn("2934 ms", md)
        self.assertIn("60457", md)
        self.assertIn("4.2", md)          # Gmail throughput
        self.assertIn("139.5", md)        # Outlook throughput
        self.assertIn("n=2", md)          # two uncached opens among the three

    def test_skipped_row_renders(self):
        rows = [report.ReaderOpenRow(index=1, label="x", skipped=True, reason="keyguard")]
        md = report.render_message_open("t", rows)
        self.assertIn("SKIPPED: keyguard", md)


class TestBackNav(unittest.TestCase):
    def test_caveat_present(self):
        samples = [report.BackNavSample(index=1, back_ms=3484)]
        md = report.render_back_nav(samples)
        self.assertIn("Caveat", md)
        self.assertIn("3484 ms", md)


class TestDocument(unittest.TestCase):
    def test_build_document(self):
        doc = report.build_document({"scenario": "cold-open"}, ["## Section\n\nbody\n"])
        self.assertIn("# LibreMail device perf run", doc)
        self.assertIn("**scenario:** cold-open", doc)
        self.assertIn("## Section", doc)


if __name__ == "__main__":
    unittest.main()
