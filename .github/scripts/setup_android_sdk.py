#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Hardened Android SDK setup for CI (issue #389).

The dominant merge-blocking flake was the **Set up Android SDK** step
(`android-actions/setup-android`) dying *before* the emulator ever starts:

    Wrong version in preinstalled sdkmanager
    Warning: ... preparing SDK package Android Emulator: Error reading Zip
    content from a SeekableByteChannel.
    Error: The process '.../sdkmanager' failed with exit code 1

Two root causes, both a corrupt/truncated download that a bare `sdkmanager`
turns into an un-retried exit 1:

  * the action's own **unverified** cmdline-tools re-download (its default
    cmdline-tools version rarely matches the runner image's preinstalled one, so
    it logs "Wrong version in preinstalled sdkmanager" and re-fetches with *no*
    checksum), and
  * the action's default ``packages: tools platform-tools`` install (the "SDK
    Tools" corrupt zip seen on a #388 preview shard) plus the emulator/platform
    package installs.

This module hardens both with **verify -> reject -> retry**, never trusting
sdkmanager's exit code alone:

  ``bootstrap``  Download the *pinned* Android command-line tools zip, verify it
                 against a pinned size + SHA-256, and install it to
                 ``$ANDROID_SDK_ROOT/cmdline-tools/<rev>`` -- the exact path
                 setup-android probes first, so the action reuses our verified
                 tree and never does its own unverified "Wrong version"
                 re-download. A size/hash mismatch (corrupt OR wrong version)
                 => delete the bad zip + any half-extracted dir => re-download
                 clean. Only a verified tree is ever left in place, so the
                 success-gated cache can never bake in a corrupt SDK.

  ``install``    Run ``sdkmanager --install <packages>`` with retry + backoff.
                 "Error reading Zip content from a SeekableByteChannel" is a
                 corrupt package zip, so on failure each requested package's dir
                 (and sdkmanager's temp/intermediate dirs) is PURGED before the
                 retry -- forcing a fresh re-download instead of a re-read of the
                 corrupt file.

stdlib only (urllib/hashlib/zipfile/...), cross-platform, per the repo's "prefer
Python for dev/CI-helper scripts" rule. The pure helpers are unit-tested in
``test_setup_android_sdk.py`` (run by the ``traffic-control-tests`` job); the
full download/install path is validated by CI itself.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

# --- Pinned Android command-line tools (revision 20.0) --------------------
# android-actions/setup-android v4.0.1 defaults to this same build (its
# getVersionShort() maps "14742923" -> "20.0"). We provision it OURSELVES,
# integrity-checked, into the path the action looks for first
# ($ANDROID_SDK_ROOT/cmdline-tools/20.0), so the action finds it, skips its own
# unverified download, and never prints "Wrong version in preinstalled
# sdkmanager".
#
# CLT_SIZE + the SHA-1 are Google's published values for this immutable,
# build-numbered zip (repository2-3.xml). CLT_SHA256 was computed locally from
# bytes that matched BOTH of Google's published values, so it is an authoritative
# integrity pin. A build-numbered URL is immutable, so these never drift; bumping
# the tools means bumping all four constants together.
CLT_VERSION_LONG = "14742923"
CLT_VERSION_SHORT = "20.0"
CLT_URL = (
    "https://dl.google.com/android/repository/"
    f"commandlinetools-linux-{CLT_VERSION_LONG}_latest.zip"
)
CLT_SIZE = 172789259
CLT_SHA256 = "04453066b540409d975c676d781da1477479dde3761310f1a7eb92a1dfb15af7"

# Total tries (1 initial + retries). Backoff is linear: 10s, 20s, 30s ...
MAX_ATTEMPTS = 4


def log(msg: str) -> None:
    print(msg, flush=True)


def warn(msg: str) -> None:
    print(f"::warning::{msg}", flush=True)


def error(msg: str) -> None:
    print(f"::error::{msg}", flush=True)


def backoff_seconds(attempt: int) -> int:
    """Linear backoff before the next attempt: 10s after attempt 1, 20s after 2..."""
    return 10 * attempt


def sdk_root() -> str:
    """The Android SDK root. GitHub-hosted runners preset ANDROID_SDK_ROOT /
    ANDROID_HOME to /usr/local/lib/android/sdk; fall back to the SDK's default."""
    root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not root:
        root = os.path.join(os.path.expanduser("~"), ".android", "sdk")
    return root


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_download(path, expected_size, expected_sha256):
    """(ok, detail) for a downloaded file: size first (cheap), then SHA-256.
    A mismatch means a corrupt/truncated download OR the wrong version -- both
    must be rejected and re-fetched."""
    if not os.path.exists(path):
        return False, "download missing"
    actual_size = os.path.getsize(path)
    if actual_size != expected_size:
        return False, f"size {actual_size} != expected {expected_size}"
    actual_sha = sha256_of(path)
    if actual_sha != expected_sha256:
        return False, f"sha256 {actual_sha} != expected {expected_sha256}"
    return True, "ok"


def package_dir(root: str, package: str) -> str:
    """On-disk dir for an sdkmanager package id. sdkmanager lays packages out by
    turning the ';' separators into path separators, e.g.
    'platforms;android-37.0' -> <root>/platforms/android-37.0, so this is exactly
    the tree to purge to force a corrupt package to re-download."""
    return os.path.join(root, *package.split(";"))


def _rm(path: str) -> None:
    """Best-effort recursive delete of a file or dir (reject a bad download)."""
    if os.path.islink(path) or os.path.isfile(path):
        try:
            os.remove(path)
        except FileNotFoundError:
            pass
    elif os.path.isdir(path):
        shutil.rmtree(path, ignore_errors=True)


def _extract_preserving_perms(zip_path: str, target_dir: str) -> None:
    """Extract a zip, restoring the unix permission bits stored in each entry's
    external attributes. ZipFile.extractall drops the executable bit, which would
    leave bin/sdkmanager non-executable and break the action's `sdkmanager
    --licenses`; Google's zip is unix-built, so external_attr carries the +x."""
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            extracted = zf.extract(info, target_dir)
            mode = (info.external_attr >> 16) & 0o7777
            if mode:
                os.chmod(extracted, mode)


class _RejectAndRetry(Exception):
    """Internal signal: discard this attempt's download and retry from scratch."""


def bootstrap() -> int:
    """Ensure $ANDROID_SDK_ROOT/cmdline-tools/<rev> is a verified install."""
    root = sdk_root()
    dest = os.path.join(root, "cmdline-tools", CLT_VERSION_SHORT)
    sdkmanager = os.path.join(dest, "bin", "sdkmanager")
    if os.path.exists(sdkmanager):
        # Cache hit (or already provisioned): the cache is populated only after a
        # passing integrity check, so a present tree is trusted -> no re-download.
        log(f"cmdline-tools {CLT_VERSION_SHORT} already present at {dest} "
            "(cache hit) -- skipping verified download")
        return 0

    tools_parent = os.path.join(root, "cmdline-tools")
    os.makedirs(tools_parent, exist_ok=True)
    for attempt in range(1, MAX_ATTEMPTS + 1):
        log(f"::group::Download + verify cmdline-tools {CLT_VERSION_SHORT} "
            f"(attempt {attempt}/{MAX_ATTEMPTS})")
        tmp_zip = os.path.join(tempfile.gettempdir(), f"clt-{CLT_VERSION_LONG}.zip")
        # Extract on the SAME filesystem as `dest` so the final move is an atomic
        # rename that preserves the restored +x bit on bin/sdkmanager.
        tmp_extract = tempfile.mkdtemp(prefix=".clt-extract-", dir=tools_parent)
        _rm(tmp_zip)
        try:
            log(f"Downloading {CLT_URL}")
            urllib.request.urlretrieve(CLT_URL, tmp_zip)  # noqa: S310 (pinned https)
            ok, detail = verify_download(tmp_zip, CLT_SIZE, CLT_SHA256)
            if not ok:
                warn(f"cmdline-tools integrity check failed: {detail} -- "
                     "rejecting the bad download and retrying clean")
                raise _RejectAndRetry()
            log(f"Integrity OK (size {CLT_SIZE}, sha256 {CLT_SHA256})")
            _extract_preserving_perms(tmp_zip, tmp_extract)
            unpacked = os.path.join(tmp_extract, "cmdline-tools")
            if not os.path.isdir(unpacked):
                warn("extracted zip has no top-level cmdline-tools/ dir -- retrying")
                raise _RejectAndRetry()
            _rm(dest)  # drop any half-extracted leftover before moving the good tree
            shutil.move(unpacked, dest)
            # Mirror the action: touch repositories.cfg so sdkmanager is happy.
            open(os.path.join(root, "repositories.cfg"), "a", encoding="utf-8").close()
            if os.path.exists(sdkmanager):
                log(f"Installed verified cmdline-tools to {dest}")
                return 0
            warn("sdkmanager missing after extract -- retrying")
        except _RejectAndRetry:
            pass
        except Exception as exc:  # noqa: BLE001 - any transient error is retryable
            warn(f"cmdline-tools bootstrap attempt {attempt} failed: {exc}")
        finally:
            _rm(tmp_zip)
            _rm(tmp_extract)
            log("::endgroup::")
        if attempt < MAX_ATTEMPTS:
            time.sleep(backoff_seconds(attempt))
    error(f"Failed to provision verified cmdline-tools after {MAX_ATTEMPTS} attempts")
    return 1


def find_sdkmanager(root: str):
    """Locate sdkmanager: our pinned rev first, then the action's `latest`, then PATH."""
    candidates = [
        os.path.join(root, "cmdline-tools", CLT_VERSION_SHORT, "bin", "sdkmanager"),
        os.path.join(root, "cmdline-tools", "latest", "bin", "sdkmanager"),
    ]
    for candidate in candidates:
        if os.path.exists(candidate):
            return candidate
    return shutil.which("sdkmanager")


def install(packages) -> int:
    """`sdkmanager --install <packages>` with retry + purge-on-corrupt-zip."""
    root = sdk_root()
    sdkmanager = find_sdkmanager(root)
    if not sdkmanager:
        error("sdkmanager not found -- run the cmdline-tools bootstrap step first")
        return 1
    # Feed 'y' repeatedly in case any license needs accepting (setup-android's
    # --licenses runs first, but this keeps the step self-contained).
    accept = ("y\n" * 32).encode()
    for attempt in range(1, MAX_ATTEMPTS + 1):
        log(f"::group::sdkmanager --install {' '.join(packages)} "
            f"(attempt {attempt}/{MAX_ATTEMPTS})")
        result = subprocess.run([sdkmanager, "--install", *packages], input=accept)
        log("::endgroup::")
        if result.returncode == 0:
            log(f"Installed SDK packages: {' '.join(packages)}")
            return 0
        warn(f"sdkmanager attempt {attempt} failed (exit {result.returncode}) -- "
             "purging partial/corrupt packages before retry")
        # REJECT: a corrupt package zip must be re-downloaded, not re-read. Purge
        # each requested package's dir + sdkmanager's temp/intermediate dirs so
        # the retry starts clean.
        for pkg in packages:
            _rm(package_dir(root, pkg))
        _rm(os.path.join(root, ".temp"))
        _rm(os.path.join(root, ".downloadIntermediates"))
        if attempt < MAX_ATTEMPTS:
            time.sleep(backoff_seconds(attempt))
    error(f"sdkmanager failed to install {list(packages)} after {MAX_ATTEMPTS} attempts")
    log("--- sdkmanager --list_installed ---")
    subprocess.run([sdkmanager, "--list_installed"])
    return 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Hardened Android SDK setup for CI (issue #389)."
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser(
        "bootstrap",
        help="Download + SHA-256-verify the pinned Android command-line tools.",
    )
    installer = sub.add_parser(
        "install",
        help="sdkmanager --install with retry + purge-on-corrupt-zip.",
    )
    installer.add_argument("packages", nargs="+", help="sdkmanager package ids")
    return parser


def main(argv) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "bootstrap":
        return bootstrap()
    if args.command == "install":
        return install(args.packages)
    return 2  # pragma: no cover - argparse requires a subcommand


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
