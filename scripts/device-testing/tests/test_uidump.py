# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for the uiautomator-dump parser and screen/keyguard recognition.

Fixtures are cleaned copies of the manual run's real dumps: the reader (``ui_reader.xml``),
a deskclock alarm (``ui_alarm.xml``) and the keyguard (``ui_lockscreen.xml``) are the
foreign-screen negatives the harness must skip; ``ui_mailbox.xml`` is a compact, structurally
faithful mailbox list.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import uidump  # noqa: E402

FIX = os.path.join(os.path.dirname(__file__), "fixtures")
APP = "org.libremail.app"


def load(name):
    with open(os.path.join(FIX, name), encoding="utf-8") as fh:
        return uidump.parse_dump(fh.read())


class TestBounds(unittest.TestCase):
    def test_parse_bounds(self):
        self.assertEqual(uidump.parse_bounds("[0,392][1344,617]"), (0, 392, 1344, 617))

    def test_parse_bounds_none(self):
        self.assertIsNone(uidump.parse_bounds(""))
        self.assertIsNone(uidump.parse_bounds("not-bounds"))


class TestMailbox(unittest.TestCase):
    def setUp(self):
        self.root = load("ui_mailbox.xml")

    def test_recognised_as_app_not_lockscreen(self):
        self.assertEqual(uidump.foreground_package(self.root), APP)
        self.assertFalse(uidump.is_lockscreen(self.root))
        self.assertTrue(uidump.is_app_foreground(self.root, APP))
        self.assertFalse(uidump.is_reader(self.root, APP))

    def test_finds_three_message_rows(self):
        rows = uidump.find_message_rows(self.root, APP)
        self.assertEqual(len(rows), 3)

    def test_row_centers_and_cached_flags(self):
        rows = uidump.find_message_rows(self.root, APP)
        self.assertEqual(rows[0].center, (672, 504))
        self.assertFalse(rows[0].cached)
        self.assertEqual(rows[1].center, (672, 732))
        self.assertFalse(rows[1].cached)
        # Row 2 carries the "Available offline" content-desc -> cached.
        self.assertEqual(rows[2].center, (672, 988))
        self.assertTrue(rows[2].cached)

    def test_row_label_drops_monogram(self):
        rows = uidump.find_message_rows(self.root, APP)
        self.assertIn("github-actions[bot]", rows[0].label)
        self.assertNotIn(" G ", rows[0].label)  # single-letter avatar filtered out

    def test_settings_entry_tappable(self):
        node = uidump.find_by_text(self.root, "Settings")
        self.assertIsNotNone(node)
        anchor = node.first_clickable_ancestor()
        self.assertIsNotNone(anchor)
        self.assertEqual(anchor.center, (1014, 2728))

    def test_top_bar_content_descs(self):
        self.assertIsNotNone(uidump.find_by_content_desc(self.root, "Show folders"))
        self.assertIsNotNone(uidump.find_by_content_desc(self.root, "Search"))


class TestReader(unittest.TestCase):
    def setUp(self):
        self.root = load("ui_reader.xml")

    def test_recognised_as_reader(self):
        self.assertTrue(uidump.is_app_foreground(self.root, APP))
        self.assertTrue(uidump.is_reader(self.root, APP))

    def test_progress_bar_signals_loading(self):
        self.assertTrue(uidump.has_progress_bar(self.root))

    def test_no_message_rows_in_reader(self):
        self.assertEqual(uidump.find_message_rows(self.root, APP), [])


class TestForeignScreensAreGuarded(unittest.TestCase):
    def test_lockscreen_detected(self):
        root = load("ui_lockscreen.xml")
        self.assertTrue(uidump.is_lockscreen(root))
        self.assertFalse(uidump.is_app_foreground(root, APP))
        self.assertFalse(uidump.is_reader(root, APP))

    def test_alarm_is_foreign_app(self):
        root = load("ui_alarm.xml")
        self.assertEqual(uidump.foreground_package(root), "com.google.android.deskclock")
        self.assertFalse(uidump.is_app_foreground(root, APP))
        self.assertFalse(uidump.is_lockscreen(root))
        self.assertFalse(uidump.is_reader(root, APP))


if __name__ == "__main__":
    unittest.main()
