# SPDX-License-Identifier: GPL-3.0-or-later
"""Tests for the adb safety guardrails -- the harness must refuse dangerous commands."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import adb  # noqa: E402
from adb import Adb, AdbSafetyError, assert_safe  # noqa: E402

PKG = "org.libremail.app"


class TestAllowedCommands(unittest.TestCase):
    def _ok(self, args):
        try:
            assert_safe(PKG, args)
        except AdbSafetyError as exc:  # pragma: no cover - failure path
            self.fail(f"assert_safe wrongly rejected {args}: {exc}")

    def test_lifecycle_and_input_allowed(self):
        self._ok(["shell", "am", "force-stop", PKG])
        self._ok(["shell", "am", "start", "-W", "-n", f"{PKG}/org.libremail.MainActivity"])
        self._ok(["shell", "input", "tap", "672", "504"])
        self._ok(["shell", "input", "swipe", "672", "2000", "672", "900", "400"])
        self._ok(["shell", "input", "keyevent", "KEYCODE_BACK"])
        self._ok(["shell", "input", "keyevent", "KEYCODE_WAKEUP"])
        self._ok(["shell", "uiautomator", "dump", "/dev/tty"])
        self._ok(["shell", "svc", "power", "stayon", "true"])
        self._ok(["shell", "dumpsys", "gfxinfo", PKG])
        self._ok(["shell", "settings", "get", "global", "airplane_mode_on"])
        self._ok(["shell", "logcat", "-c"])
        self._ok(["logcat", "-b", "all", "-v", "threadtime"])
        self._ok(["devices"])
        self._ok(["get-state"])
        self._ok(["install", "-r", "app-debug.apk"])

    def test_sanctioned_cache_clear_allowed(self):
        self._ok(["shell", "run-as", PKG, "sh", "-c", "rm -rf cache/*"])
        self._ok(["shell", "run-as", PKG, "sh", "-c", "rm -rf cache"])
        self._ok(["shell", "run-as", PKG, "ls", "-la", "cache"])

    def test_input_swipe_not_confused_with_wipe(self):
        # 'swipe' contains the substring 'wipe'; it must still be allowed.
        self._ok(["shell", "input", "swipe", "0", "0", "0", "500", "300"])


class TestDeniedCommands(unittest.TestCase):
    def _deny(self, args):
        with self.assertRaises(AdbSafetyError):
            assert_safe(PKG, args)

    def test_top_level_allowlist(self):
        self._deny(["uninstall", PKG])
        self._deny(["root"])
        self._deny(["remount"])
        self._deny(["reboot"])
        self._deny(["disable-verity"])
        self._deny(["emu", "kill"])
        self._deny(["push", "x", "/data"])
        self._deny(["pull", "/data/data/org.libremail.app/databases/x"])

    def test_pm_clear_and_uninstall_denied(self):
        self._deny(["shell", "pm", "clear", PKG])
        self._deny(["shell", "pm", "uninstall", PKG])
        self._deny(["shell", "pm", "disable", PKG])

    def test_reboot_and_root_via_shell_denied(self):
        self._deny(["shell", "reboot"])
        self._deny(["shell", "svc", "power", "reboot"])
        self._deny(["shell", "su", "-c", "reboot"])

    def test_no_touching_private_dirs(self):
        self._deny(["shell", "run-as", PKG, "sh", "-c", "rm -rf databases"])
        self._deny(["shell", "run-as", PKG, "sh", "-c", "rm -rf shared_prefs"])
        self._deny(["shell", "run-as", PKG, "sh", "-c", "rm -rf datastore"])
        self._deny(["shell", "run-as", PKG, "cat", "databases/libremail.db"])
        self._deny(["shell", "run-as", PKG, "ls", "files/datastore"])

    def test_rm_outside_cache_denied(self):
        self._deny(["shell", "run-as", PKG, "sh", "-c", "rm -rf /sdcard/x"])
        self._deny(["shell", "run-as", PKG, "sh", "-c", "rm -rf cache/../databases"])
        self._deny(["shell", "rm", "-rf", "/data/local/tmp"])

    def test_output_redirect_denied(self):
        self._deny(["shell", "echo", "x", ">", "/sdcard/y"])

    def test_run_as_foreign_package_denied(self):
        self._deny(["shell", "run-as", "com.other.app", "ls", "cache"])

    def test_am_foreign_targets_denied(self):
        self._deny(["shell", "am", "force-stop", "com.other.app"])
        self._deny(["shell", "am", "start", "-n", "com.other.app/.Main"])

    def test_empty_command_denied(self):
        self._deny([])


class TestAdbWrapperBuildsSafeArgv(unittest.TestCase):
    """In dry-run the wrapper still validates and returns the argv it would run."""

    def setUp(self):
        self.adb = Adb(serial="SERIAL123", package=PKG, dry_run=True)

    def test_force_stop_argv(self):
        self.assertEqual(self.adb.force_stop().args, ["shell", "am", "force-stop", PKG])

    def test_start_activity_argv(self):
        args = self.adb.start_activity().args
        self.assertEqual(args[:5], ["shell", "am", "start", "-W", "-n"])
        self.assertTrue(args[5].startswith(PKG + "/"))

    def test_clear_cache_argv_is_sanctioned(self):
        self.assertEqual(
            self.adb.clear_cache().args,
            ["shell", "run-as", PKG, "sh", "-c", "rm -rf cache/*"],
        )

    def test_argv_includes_serial(self):
        full = self.adb._argv(["devices"])
        self.assertEqual(full, ["adb", "-s", "SERIAL123", "devices"])

    def test_wrapper_rejects_unsafe_low_level_call(self):
        with self.assertRaises(AdbSafetyError):
            self.adb.run(["shell", "pm", "clear", PKG])


class TestPureParsers(unittest.TestCase):
    def test_parse_devices(self):
        out = (
            "List of devices attached\n"
            "5C310DLCQ000G3\tdevice\n"
            "emulator-5554\tdevice\n"
            "0123456789\toffline\n"
        )
        self.assertEqual(adb.parse_devices(out), ["5C310DLCQ000G3", "emulator-5554"])

    def test_parse_devices_empty(self):
        self.assertEqual(adb.parse_devices("List of devices attached\n"), [])

    def test_parse_am_start(self):
        out = (
            "Starting: Intent { cmp=org.libremail.app/org.libremail.MainActivity }\n"
            "Status: ok\n"
            "LaunchState: COLD\n"
            "TotalTime: 384\n"
            "WaitTime: 386\n"
        )
        fields = adb.parse_am_start(out)
        self.assertEqual(fields["TotalTime"], 384)
        self.assertEqual(fields["WaitTime"], 386)

    def test_parse_am_start_missing(self):
        self.assertEqual(adb.parse_am_start("Status: ok\n"), {})


if __name__ == "__main__":
    unittest.main()
