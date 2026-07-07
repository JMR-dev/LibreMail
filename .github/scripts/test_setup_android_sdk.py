#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Unit tests for the pure helpers of setup_android_sdk.py (no network, no SDK).

Covers the bits whose correctness is load-bearing for the hardening in #389:
the package-id -> purge-path mapping (a wrong mapping would purge the wrong dir),
the size/SHA-256 integrity gate (verify -> reject), the pinned-constant
self-consistency, the backoff schedule, sdkmanager discovery, and that extraction
restores the executable bit that sdkmanager needs. The full download/install path
is exercised by CI itself."""

from __future__ import annotations

import hashlib
import os
import stat
import tempfile
import unittest
import zipfile

import setup_android_sdk as sdk


class PackageDirTests(unittest.TestCase):
    def test_semicolon_ids_map_to_nested_dirs(self):
        root = os.path.join("opt", "sdk")
        self.assertEqual(
            sdk.package_dir(root, "platforms;android-37.0"),
            os.path.join(root, "platforms", "android-37.0"),
        )
        self.assertEqual(
            sdk.package_dir(root, "build-tools;37.0.0"),
            os.path.join(root, "build-tools", "37.0.0"),
        )
        self.assertEqual(
            sdk.package_dir(root, "system-images;android-37.0;google_apis_ps16k;x86_64"),
            os.path.join(root, "system-images", "android-37.0", "google_apis_ps16k", "x86_64"),
        )

    def test_flat_ids_map_to_single_dir(self):
        root = os.path.join("opt", "sdk")
        self.assertEqual(sdk.package_dir(root, "emulator"), os.path.join(root, "emulator"))
        self.assertEqual(
            sdk.package_dir(root, "platform-tools"), os.path.join(root, "platform-tools")
        )

    def test_purge_target_stays_under_root(self):
        # The purge path must never escape the SDK root (no absolute/`..` package ids).
        root = os.path.abspath(os.path.join("opt", "sdk"))
        target = os.path.abspath(sdk.package_dir(root, "platforms;android-37.0"))
        self.assertTrue(target.startswith(root + os.sep))


class VerifyDownloadTests(unittest.TestCase):
    def _write(self, data: bytes) -> str:
        fd, path = tempfile.mkstemp()
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))
        return path

    def test_accepts_matching_size_and_hash(self):
        data = b"correct-cmdline-tools-bytes"
        path = self._write(data)
        ok, detail = sdk.verify_download(path, len(data), hashlib.sha256(data).hexdigest())
        self.assertTrue(ok, detail)
        self.assertEqual(detail, "ok")

    def test_rejects_wrong_size_before_hashing(self):
        data = b"truncated"
        path = self._write(data)
        ok, detail = sdk.verify_download(path, len(data) + 1, hashlib.sha256(data).hexdigest())
        self.assertFalse(ok)
        self.assertIn("size", detail)

    def test_rejects_corrupt_bytes_with_right_size(self):
        good = b"aaaaaaaa"
        corrupt = b"aaaaaaab"  # same length, different content (silent corruption)
        path = self._write(corrupt)
        ok, detail = sdk.verify_download(path, len(good), hashlib.sha256(good).hexdigest())
        self.assertFalse(ok)
        self.assertIn("sha256", detail)

    def test_rejects_missing_file(self):
        ok, detail = sdk.verify_download(
            os.path.join(tempfile.gettempdir(), "does-not-exist-clt.zip"), 1, "0" * 64
        )
        self.assertFalse(ok)


class PinnedConstantsTests(unittest.TestCase):
    def test_url_embeds_the_pinned_build_number(self):
        self.assertIn(sdk.CLT_VERSION_LONG, sdk.CLT_URL)
        self.assertTrue(sdk.CLT_URL.startswith("https://"))
        self.assertTrue(sdk.CLT_URL.endswith("_latest.zip"))

    def test_sha256_is_a_full_hex_digest(self):
        self.assertEqual(len(sdk.CLT_SHA256), 64)
        int(sdk.CLT_SHA256, 16)  # raises if not hex
        self.assertEqual(sdk.CLT_SHA256, sdk.CLT_SHA256.lower())

    def test_size_is_positive(self):
        self.assertGreater(sdk.CLT_SIZE, 0)


class BackoffTests(unittest.TestCase):
    def test_backoff_is_linear_and_increasing(self):
        seq = [sdk.backoff_seconds(a) for a in range(1, sdk.MAX_ATTEMPTS + 1)]
        self.assertEqual(seq, [10, 20, 30, 40][: sdk.MAX_ATTEMPTS])
        self.assertEqual(seq, sorted(seq))


class SdkRootTests(unittest.TestCase):
    def test_prefers_android_sdk_root_over_home(self):
        with _env(ANDROID_SDK_ROOT="/a/sdk-root", ANDROID_HOME="/b/home"):
            self.assertEqual(sdk.sdk_root(), "/a/sdk-root")

    def test_falls_back_to_android_home(self):
        with _env(ANDROID_SDK_ROOT=None, ANDROID_HOME="/b/home"):
            self.assertEqual(sdk.sdk_root(), "/b/home")


class FindSdkManagerTests(unittest.TestCase):
    def test_prefers_pinned_revision_dir(self):
        with tempfile.TemporaryDirectory() as root:
            pinned = os.path.join(root, "cmdline-tools", sdk.CLT_VERSION_SHORT, "bin")
            latest = os.path.join(root, "cmdline-tools", "latest", "bin")
            for d in (pinned, latest):
                os.makedirs(d)
                open(os.path.join(d, "sdkmanager"), "w").close()
            self.assertEqual(
                sdk.find_sdkmanager(root),
                os.path.join(pinned, "sdkmanager"),
            )


class ExtractPermsTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "posix", "unix exec bit only meaningful on POSIX")
    def test_executable_bit_is_restored(self):
        with tempfile.TemporaryDirectory() as work:
            zip_path = os.path.join(work, "clt.zip")
            with zipfile.ZipFile(zip_path, "w") as zf:
                info = zipfile.ZipInfo("cmdline-tools/bin/sdkmanager")
                info.external_attr = 0o755 << 16  # -rwxr-xr-x, as Google's zip stores it
                zf.writestr(info, "#!/bin/sh\n")
            out = os.path.join(work, "out")
            sdk._extract_preserving_perms(zip_path, out)
            mode = os.stat(os.path.join(out, "cmdline-tools", "bin", "sdkmanager")).st_mode
            self.assertTrue(mode & stat.S_IXUSR, "sdkmanager must be executable after extract")


class _env:
    """Context manager to set/clear env vars for a test, restoring them after."""

    def __init__(self, **values):
        self._values = values
        self._saved = {}

    def __enter__(self):
        for key, value in self._values.items():
            self._saved[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return self

    def __exit__(self, *exc):
        for key, previous in self._saved.items():
            if previous is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = previous
        return False


if __name__ == "__main__":
    unittest.main()
