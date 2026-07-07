#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""adb.py -- a deliberately narrow, safety-guarded wrapper around ``adb``.

Every device interaction in the harness goes through :class:`Adb`. The wrapper enforces
two independent layers so a dangerous command *cannot be constructed*, even by a buggy
caller:

* a TOP-LEVEL ALLOW-LIST of adb subcommands (``devices``, ``get-state``, ``install``,
  ``shell``, ``logcat``, ``wait-for-device``) -- anything else raises :class:`AdbSafetyError`;
* a DENY-LIST + assertions applied to every ``shell`` command: no ``pm clear`` / ``pm
  uninstall``, no ``reboot`` / ``remount`` / ``root`` / ``disable-verity`` / factory reset,
  no touching the app's ``databases/`` / ``files/`` / ``shared_prefs/`` / ``datastore/``,
  no output redirects, and the ONLY sanctioned destructive operation is clearing the app's
  own ``cache/`` (an exact-match allow-list of ``run-as <pkg> sh -c 'rm -rf cache/*'``).

``am force-stop`` / ``am start`` and ``run-as`` are constrained to the target package
(default ``org.libremail.app``). All checks run in ``--dry-run`` mode too, so the guardrails
are unit-testable without a device.

Scope: this harness operates ONLY on LibreMail plus read-only system-log/settings/dumpsys
collection. It never writes app data other than clearing the cache.
"""

from __future__ import annotations

import re
import subprocess
import time
from dataclasses import dataclass
from typing import List, Optional, Sequence

DEFAULT_PACKAGE = "org.libremail.app"
DEFAULT_COMPONENT = "org.libremail.app/org.libremail.MainActivity"

# uiautomator's default public scratch file. The harness dumps the view hierarchy to this
# file and ``cat``s it back (a file-based dump) rather than dumping to ``/dev/tty``: the tty
# path interleaves the "dumped to" status banner with the XML and is unreliable across
# devices/hosts (issue #392). It is public external storage -- never app-private data.
UI_DUMP_DEVICE_PATH = "/sdcard/window_dump.xml"

# adb subcommands the harness is ever allowed to invoke.
_ALLOWED_SUBCOMMANDS = frozenset(
    {"devices", "get-state", "install", "shell", "logcat", "wait-for-device", "start-server"}
)

# Substrings that must never appear anywhere in a shell command line.
_DENY_SUBSTRINGS = (
    "pm clear",
    "pm uninstall",
    "pm disable",
    "pm hide",
    "disable-verity",
    "set-verity",
    "remount",
    "reboot",
    "bootloader",
    "fastboot",
    "factory",
    "wipe-data",
    "wipe_data",
    "mkfs",
    "format ",
    # App-private dirs we must never read or write (cache/ is the sole exception, handled
    # separately). Banning the segment names outright is defence-in-depth for this harness,
    # which never has a legitimate reason to touch them.
    "databases",
    "shared_prefs",
    "datastore",
    "/files",
    "files/",
)

# Verbs that mutate the filesystem. Detected as whole *words* anywhere in the shell command
# (they can be buried inside a ``sh -c '<payload>'`` token); permitted ONLY for the exact
# sanctioned cache-clear (see _is_sanctioned_cache_clear).
_DESTRUCTIVE_VERBS = ("rm", "rmdir", "mv", "dd", "truncate", "shred", "unlink", "mkfs", "chmod", "chown")
_DESTRUCTIVE_RE = re.compile(r"\b(?:" + "|".join(_DESTRUCTIVE_VERBS) + r")\b")

# The exact ``sh -c`` payloads permitted for the cache clear -- nothing else.
_CACHE_CLEAR_PAYLOADS = ("rm -rf cache/*", "rm -rf cache")


class AdbSafetyError(RuntimeError):
    """Raised when a command would violate the harness's device-safety guardrails."""


class AdbError(RuntimeError):
    """Raised when an adb command fails (non-zero exit) and the caller wanted a check."""


@dataclass
class AdbResult:
    """Outcome of one adb invocation."""

    args: List[str]
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0


def _shell_command_string(args: Sequence[str]) -> str:
    """Join the tokens *after* ``shell`` into a single inspection string."""
    return " ".join(args[1:]) if len(args) > 1 else ""


def _is_sanctioned_cache_clear(package: str, args: Sequence[str]) -> bool:
    """True iff ``args`` is exactly ``shell run-as <package> sh -c '<cache-clear>'``."""
    return (
        len(args) == 6
        and args[0] == "shell"
        and args[1] == "run-as"
        and args[2] == package
        and args[3] == "sh"
        and args[4] == "-c"
        and args[5] in _CACHE_CLEAR_PAYLOADS
    )


def assert_safe(package: str, args: Sequence[str]) -> None:
    """Raise :class:`AdbSafetyError` if ``args`` (an adb argv, sans ``adb``/``-s``) is unsafe.

    Pure and side-effect-free so the guardrails can be unit-tested exhaustively.
    """
    if not args:
        raise AdbSafetyError("empty adb command")

    subcommand = args[0]
    if subcommand not in _ALLOWED_SUBCOMMANDS:
        raise AdbSafetyError(f"adb subcommand {subcommand!r} is not on the allow-list")

    # 'install' may only take known-safe flags plus a path; it is never 'uninstall'.
    if subcommand == "install":
        for tok in args[1:]:
            if tok.startswith("-") and tok not in ("-r", "-t", "-g", "-d", "-i"):
                raise AdbSafetyError(f"install flag {tok!r} is not allowed")
        return

    if subcommand != "shell":
        # devices / get-state / logcat / wait-for-device / start-server take no risky args.
        return

    sanctioned_cache_clear = _is_sanctioned_cache_clear(package, args)
    sh = _shell_command_string(args)
    lowered = sh.lower()

    for bad in _DENY_SUBSTRINGS:
        if bad in lowered:
            raise AdbSafetyError(f"shell command contains forbidden text {bad!r}: {sh!r}")

    if ">" in sh:
        raise AdbSafetyError(f"shell output redirection is not allowed: {sh!r}")

    tokens = list(args[1:])
    if _DESTRUCTIVE_RE.search(sh) and not sanctioned_cache_clear:
        raise AdbSafetyError(
            "the only permitted destructive shell command is clearing the app cache; "
            f"refusing: {sh!r}"
        )

    # run-as must target our package and (unless the sanctioned cache clear) be a read.
    if "run-as" in tokens:
        idx = tokens.index("run-as")
        target = tokens[idx + 1] if idx + 1 < len(tokens) else None
        if target != package:
            raise AdbSafetyError(f"run-as may only target {package!r}, got {target!r}: {sh!r}")

    # am force-stop / am start may only reference our package/component.
    if "am" in tokens:
        if "force-stop" in tokens:
            i = tokens.index("force-stop")
            target = tokens[i + 1] if i + 1 < len(tokens) else None
            if target != package:
                raise AdbSafetyError(f"am force-stop must target {package!r}: {sh!r}")
        if "-n" in tokens:  # component: <package>/<activity>
            i = tokens.index("-n")
            component = tokens[i + 1] if i + 1 < len(tokens) else ""
            comp_pkg = component.split("/", 1)[0]
            if comp_pkg != package:
                raise AdbSafetyError(f"am start component must be in {package!r}: {sh!r}")
        for pkg_flag in ("-p", "--package"):
            if pkg_flag in tokens:
                i = tokens.index(pkg_flag)
                target = tokens[i + 1] if i + 1 < len(tokens) else None
                if target != package:
                    raise AdbSafetyError(f"am {pkg_flag} must be {package!r}: {sh!r}")


class Adb:
    """Guarded adb wrapper bound to a single device serial and target package."""

    def __init__(
        self,
        serial: Optional[str] = None,
        package: str = DEFAULT_PACKAGE,
        adb_path: str = "adb",
        dry_run: bool = False,
        default_timeout: float = 60.0,
        logger=None,
    ) -> None:
        self.serial = serial
        self.package = package
        self.adb_path = adb_path
        self.dry_run = dry_run
        self.default_timeout = default_timeout
        self._log = logger or (lambda msg: None)

    # -- core ---------------------------------------------------------------- #
    def _argv(self, args: Sequence[str]) -> List[str]:
        prefix = [self.adb_path]
        if self.serial:
            prefix += ["-s", self.serial]
        return prefix + list(args)

    def run(
        self,
        args: Sequence[str],
        check: bool = False,
        timeout: Optional[float] = None,
    ) -> AdbResult:
        """Validate and execute an adb command (``args`` excludes ``adb`` and ``-s <serial>``)."""
        assert_safe(self.package, args)
        argv = self._argv(args)
        printable = " ".join(argv)
        if self.dry_run:
            self._log(f"[dry-run] {printable}")
            return AdbResult(list(args), 0, "", "")
        self._log(f"$ {printable}")
        completed = subprocess.run(
            argv,
            capture_output=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout if timeout is not None else self.default_timeout,
        )
        result = AdbResult(list(args), completed.returncode, completed.stdout, completed.stderr)
        if check and not result.ok:
            raise AdbError(f"adb failed ({result.returncode}): {printable}\n{result.stderr}")
        return result

    # -- device discovery ---------------------------------------------------- #
    def devices(self) -> List[str]:
        """Return the serials of attached, ready devices."""
        out = self.run(["devices"]).stdout
        return parse_devices(out)

    def get_state(self) -> str:
        return self.run(["get-state"]).stdout.strip()

    # -- lifecycle (LibreMail only) ------------------------------------------ #
    def force_stop(self) -> AdbResult:
        return self.run(["shell", "am", "force-stop", self.package])

    def start_activity(
        self, component: str = DEFAULT_COMPONENT, wait: bool = True
    ) -> AdbResult:
        args = ["shell", "am", "start"]
        if wait:
            args.append("-W")
        args += ["-n", component]
        return self.run(args)

    def broadcast(
        self, action: str, component: str, extras: Optional[dict] = None
    ) -> AdbResult:
        """Send an ``am broadcast`` to ``component`` (constrained to our package by the guard).

        ``extras`` are sent as string extras (``--es <key> <value>``). Used for the debug
        FETCH_GATE pause/resume/query hook (see :mod:`fetchgate`): ``am broadcast`` delivers
        ordered, so the receiver echoes its result data back on stdout for a race-free
        read-back. The ``-n`` component's package must equal our target package or the
        :func:`assert_safe` guard refuses the command.
        """
        args = ["shell", "am", "broadcast", "-a", action, "-n", component]
        for key, value in (extras or {}).items():
            args += ["--es", str(key), str(value)]
        return self.run(args)

    def clear_cache(self) -> AdbResult:
        """Clear ONLY the app's ``cache/`` via run-as. The sole sanctioned mutation."""
        return self.run(["shell", "run-as", self.package, "sh", "-c", "rm -rf cache/*"])

    def list_cache(self) -> AdbResult:
        """Read-only listing of the app cache dir (for verification/logging)."""
        return self.run(["shell", "run-as", self.package, "ls", "-la", "cache"])

    # -- input / UI ---------------------------------------------------------- #
    def input_tap(self, x: int, y: int) -> AdbResult:
        return self.run(["shell", "input", "tap", str(x), str(y)])

    def input_swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> AdbResult:
        return self.run(
            ["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms)]
        )

    def input_keyevent(self, keycode: str) -> AdbResult:
        return self.run(["shell", "input", "keyevent", str(keycode)])

    def uiautomator_dump(self) -> str:
        """Return the current window's uiautomator XML via a portable file-based dump.

        Dumps to an on-device scratch file (``uiautomator dump <path>``) and ``cat``s it back
        rather than dumping to ``/dev/tty``: the tty path interleaves the "dumped to" status
        banner with the XML and is unreliable across devices/hosts (issue #392). The path is
        uiautomator's own public default (:data:`UI_DUMP_DEVICE_PATH`), never app storage.
        """
        self.run(["shell", "uiautomator", "dump", UI_DUMP_DEVICE_PATH], timeout=90)
        out = self.run(["shell", "cat", UI_DUMP_DEVICE_PATH], timeout=30).stdout
        return _extract_xml(out)

    # -- screen / keyguard --------------------------------------------------- #
    def stay_on(self, on: bool = True) -> AdbResult:
        return self.run(["shell", "svc", "power", "stayon", "true" if on else "false"])

    def wake(self) -> AdbResult:
        return self.input_keyevent("KEYCODE_WAKEUP")

    def dumpsys(self, service: str, *extra: str) -> AdbResult:
        return self.run(["shell", "dumpsys", service, *extra])

    def settings_get(self, namespace: str, key: str) -> str:
        return self.run(["shell", "settings", "get", namespace, key]).stdout.strip()

    # -- logcat -------------------------------------------------------------- #
    def clear_logcat(self) -> AdbResult:
        return self.run(["shell", "logcat", "-c"])

    def start_logcat(self, out_file) -> Optional[subprocess.Popen]:
        """Start streaming the full logcat to an already-open file handle.

        Returns the :class:`subprocess.Popen` (or ``None`` in dry-run). Stop it with
        :meth:`stop_logcat`.
        """
        args = ["logcat", "-b", "all", "-v", "threadtime"]
        assert_safe(self.package, args)
        argv = self._argv(args)
        if self.dry_run:
            self._log(f"[dry-run] {' '.join(argv)} > <session-raw.log>")
            return None
        self._log(f"$ {' '.join(argv)} > <session-raw.log>")
        return subprocess.Popen(argv, stdout=out_file, stderr=subprocess.DEVNULL)

    def settle(self, seconds: float) -> None:
        """Sleep to let device state settle -- a no-op in dry-run so previews are instant."""
        if not self.dry_run and seconds > 0:
            time.sleep(seconds)

    @staticmethod
    def stop_logcat(proc: Optional[subprocess.Popen]) -> None:
        if proc is None:
            return
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()


# --------------------------------------------------------------------------- #
# Pure parsers / helpers (unit-tested)
# --------------------------------------------------------------------------- #
def parse_devices(output: str) -> List[str]:
    """Parse ``adb devices`` output into a list of ready serials (state ``device``)."""
    serials: List[str] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


_AM_FIELD_RE = re.compile(r"^\s*(TotalTime|WaitTime|ThisTime):\s*(\d+)\s*$")


def parse_am_start(output: str) -> dict:
    """Extract ``TotalTime`` / ``WaitTime`` / ``ThisTime`` (ms) from ``am start -W`` output."""
    result: dict = {}
    for line in output.splitlines():
        m = _AM_FIELD_RE.match(line)
        if m:
            result[m.group(1)] = int(m.group(2))
    return result


def _extract_xml(raw: str) -> str:
    """Pull the ``<?xml ...</hierarchy>`` payload out of a uiautomator dump's ``cat`` output.

    Tolerant of a leading ``UI hierchary dumped to: <path>`` banner or other noise around the
    XML payload (the file-based ``cat``; historically the ``/dev/tty`` output too)."""
    start = raw.find("<?xml")
    if start == -1:
        start = raw.find("<hierarchy")
    end = raw.rfind("</hierarchy>")
    if start == -1 or end == -1:
        return raw.strip()
    return raw[start : end + len("</hierarchy>")]
