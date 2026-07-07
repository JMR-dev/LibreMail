#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""scenarios.py -- the on-device performance scenarios.

Each scenario drives LibreMail through the guarded :class:`adb.Adb` wrapper, times the
behaviour primarily from the on-device breadcrumbs (with uiautomator as a fallback ready
signal), and returns render-ready samples for :mod:`report`.

Timing philosophy (from the manual run): message-open and reader-ready times come from the
``MailReader`` / ``Reader`` / ``ImapPerf`` breadcrumbs -- adb UI polling only tells us *when*
the content is ready so we can move on, and back-nav is explicitly dump-latency-bound.

Safety: every device call goes through :class:`adb.Adb`, so its allow/deny guardrails apply.
Every uiautomator/input step is guarded against the keyguard and against a foreign app being
in the foreground; a sample taken against either is skipped, not measured.

``--dry-run`` executes one representative pass per scenario -- issuing the canonical command
sequence (so the plan is auditable) without looping on a live UI.
"""

from __future__ import annotations

import os
import time
from typing import Callable, List, Optional

import breadcrumbs
import fetchgate
import uidump
from adb import Adb, parse_am_start
from report import BackNavSample, ColdOpenSample, ReaderOpenRow

# Message-open can stall 30-75 s behind the spinner (Gmail throttle), so allow generous
# headroom before giving up on a single open.
OPEN_TIMEOUT_S = 150.0
POLL_INTERVAL_S = 2.0
SETTLE_S = 1.5

# Cold-fetch A/B: waiting for a human-driven sign-in, then for header sync, then for the
# pre-armed halt's confirming breadcrumb. Sign-in is manual (OAuth can't be automated), so
# it gets the most headroom.
SIGN_IN_TIMEOUT_S = 300.0
GATE_CONFIRM_TIMEOUT_S = 120.0
HEADER_SYNC_TIMEOUT_S = 180.0

# Fetch-policy option labels (from res/values/strings.xml) used to drive the A/B toggle.
FETCH_WIFI_LABEL = "Fetch all on Wi-Fi"       # prefetch ON  (Condition A / WIFI_ONLY)
FETCH_ON_DEMAND_LABEL = "Always on-demand"    # prefetch OFF (Condition B / ON_DEMAND)


# --------------------------------------------------------------------------- #
# Live-logcat tailing (per-sample breadcrumb extraction)
# --------------------------------------------------------------------------- #
class LogTailer:
    """Reads newly-appended text from the growing session logcat file since the last mark."""

    def __init__(self, path: str) -> None:
        self.path = path
        self._offset = 0

    def mark(self) -> None:
        """Set the read cursor to the current end of file."""
        self._offset = os.path.getsize(self.path) if os.path.exists(self.path) else 0

    def read_new(self) -> str:
        if not os.path.exists(self.path):
            return ""
        with open(self.path, "r", encoding="utf-8", errors="replace") as fh:
            fh.seek(self._offset)
            data = fh.read()
            self._offset = fh.tell()
        return data


class _NullTailer(LogTailer):
    """A tailer that yields nothing -- used in dry-run so scenarios need no live log."""

    def __init__(self) -> None:  # noqa: D401 - see base
        super().__init__(path="")

    def mark(self) -> None:
        pass

    def read_new(self) -> str:
        return ""


# --------------------------------------------------------------------------- #
# UI helpers (all guarded)
# --------------------------------------------------------------------------- #
def dump_ui(adb: Adb) -> Optional[uidump.UiNode]:
    """Dump + parse the current UI; ``None`` in dry-run or on an unparseable dump."""
    if adb.dry_run:
        return None
    xml = adb.uiautomator_dump()
    if not xml or "<hierarchy" not in xml:
        return None
    try:
        return uidump.parse_dump(xml)
    except Exception:  # pragma: no cover - malformed dump under load
        return None


def ensure_awake(adb: Adb, log: Callable[[str], None]) -> None:
    """Keep the screen on and awake for the run (restored by the caller afterwards)."""
    adb.stay_on(True)
    adb.wake()


def guard_ready(adb: Adb, package: str, log: Callable[[str], None]) -> Optional[uidump.UiNode]:
    """Return the current UI iff it is our app and not the keyguard; else try to recover.

    Returns ``None`` if, after a wake attempt, the sample is still against the lockscreen or
    a foreign app -- the caller must skip that sample rather than measure garbage.
    """
    root = dump_ui(adb)
    if root is None:
        return None
    if uidump.is_lockscreen(root):
        log("keyguard detected; waking and re-checking")
        adb.wake()
        time.sleep(SETTLE_S)
        root = dump_ui(adb)
        if root is None or uidump.is_lockscreen(root):
            log("still on keyguard after wake; skipping sample")
            return None
    if not uidump.is_app_foreground(root, package):
        log(f"foreground is {uidump.foreground_package(root)!r}, not {package!r}; skipping")
        return None
    return root


def goto_mailbox(adb: Adb, package: str, log: Callable[[str], None]) -> Optional[uidump.UiNode]:
    """Ensure the mailbox list is showing (press Back out of the reader if needed)."""
    root = guard_ready(adb, package, log)
    if root is None:
        return None
    if uidump.is_reader(root, package):
        adb.input_keyevent("KEYCODE_BACK")
        time.sleep(SETTLE_S)
        root = guard_ready(adb, package, log)
    return root


def wait_for_reader_ready(adb: Adb, package: str, log: Callable[[str], None]) -> bool:
    """Poll until the reader has loaded its body (no spinner) or timeout. True if loaded."""
    deadline = time.monotonic() + OPEN_TIMEOUT_S
    while time.monotonic() < deadline:
        time.sleep(POLL_INTERVAL_S)
        root = dump_ui(adb)
        if root is None:
            continue
        if uidump.is_lockscreen(root):
            log("keyguard appeared during open; sample is invalid")
            return False
        if uidump.is_reader(root, package) and not uidump.has_progress_bar(root):
            return True
    log("open timed out")
    return False


# --------------------------------------------------------------------------- #
# Scenario 1: cold open
# --------------------------------------------------------------------------- #
def cold_open(
    adb: Adb,
    component: str,
    runs: int,
    log: Callable[[str], None],
    settle_s: float = 2.0,
) -> List[ColdOpenSample]:
    """Force-stop + clear cache + ``am start -W`` x ``runs``; parse TotalTime/WaitTime."""
    samples: List[ColdOpenSample] = []
    for run in range(1, runs + 1):
        adb.force_stop()
        adb.clear_cache()
        adb.settle(settle_s)
        out = adb.start_activity(component=component, wait=True).stdout
        fields = parse_am_start(out)
        note = "" if fields else ("dry-run" if adb.dry_run else "no am-start timing parsed")
        samples.append(
            ColdOpenSample(
                run=run,
                total_time_ms=fields.get("TotalTime"),
                wait_time_ms=fields.get("WaitTime"),
                note=note,
            )
        )
        log(f"cold-open run {run}: {fields or note}")
        adb.settle(settle_s)
    return samples


# --------------------------------------------------------------------------- #
# Scenario 2: message open (uncached)
# --------------------------------------------------------------------------- #
def _row_from_events(index: int, label: str, events: List[breadcrumbs.Event]) -> ReaderOpenRow:
    """Build a reader-open row from the breadcrumbs captured during one open."""
    opens = breadcrumbs.correlate_opens(events)
    if not opens:
        return ReaderOpenRow(index=index, label=label, skipped=True, reason="no breadcrumb")
    sample = opens[-1]
    return ReaderOpenRow.from_open_sample(sample, index=index, label=label)


def open_one_message(
    adb: Adb,
    package: str,
    tailer: LogTailer,
    index: int,
    already_opened: set,
    log: Callable[[str], None],
) -> Optional[ReaderOpenRow]:
    """Open the next not-yet-opened, uncached message; return its timed row (or ``None``)."""
    root = goto_mailbox(adb, package, log)
    if root is None:
        return ReaderOpenRow(index=index, label="?", skipped=True, reason="not on mailbox")

    rows = uidump.find_message_rows(root, package)
    candidates = [r for r in rows if not r.cached and r.label not in already_opened]
    if not candidates:
        # Scroll to reveal more of the list, then re-scan once.
        adb.input_swipe(672, 2000, 672, 900, 400)
        time.sleep(SETTLE_S)
        root = guard_ready(adb, package, log)
        if root is None:
            return None
        rows = uidump.find_message_rows(root, package)
        candidates = [r for r in rows if not r.cached and r.label not in already_opened]
    if not candidates:
        log("no more uncached messages visible")
        return None

    target = candidates[0]
    already_opened.add(target.label)
    log(f"opening row#{target.index} {target.label!r} at {target.center}")

    tailer.mark()
    adb.input_tap(*target.center)
    loaded = wait_for_reader_ready(adb, package, log)
    events = list(breadcrumbs.iter_events(tailer.read_new().splitlines()))
    row = _row_from_events(index, target.label, events)
    if not loaded and not row.skipped:
        row.reason = "ui ready-signal timed out (breadcrumb used)"
    elif not loaded:
        row.skipped = True
        row.reason = "open timed out"
    # Return to the mailbox for the next sample.
    adb.input_keyevent("KEYCODE_BACK")
    time.sleep(SETTLE_S)
    return row


def message_open(
    adb: Adb,
    package: str,
    tailer: LogTailer,
    count: int,
    log: Callable[[str], None],
) -> List[ReaderOpenRow]:
    """Open up to ``count`` distinct uncached messages one at a time."""
    if adb.dry_run:
        _dry_run_open_demo(adb, log)
        return [ReaderOpenRow(index=1, label="<dry-run>", skipped=True, reason="dry-run")]

    rows: List[ReaderOpenRow] = []
    opened: set = set()
    for i in range(1, count + 1):
        row = open_one_message(adb, package, tailer, i, opened, log)
        if row is None:
            break
        rows.append(row)
    return rows


def _dry_run_open_demo(adb: Adb, log: Callable[[str], None]) -> None:
    """Issue the canonical message-open command shape once (dry-run only)."""
    adb.uiautomator_dump()
    adb.input_tap(672, 504)          # tap a representative message row centre
    adb.input_keyevent("KEYCODE_BACK")


# --------------------------------------------------------------------------- #
# Scenario 3: back navigation
# --------------------------------------------------------------------------- #
def back_nav(
    adb: Adb,
    package: str,
    count: int,
    log: Callable[[str], None],
) -> List[BackNavSample]:
    """Time reader -> mailbox back transitions (dump-latency-bound; see report caveat)."""
    if adb.dry_run:
        adb.uiautomator_dump()
        adb.input_keyevent("KEYCODE_BACK")
        return [BackNavSample(index=1, back_ms=None, note="dry-run")]

    samples: List[BackNavSample] = []
    opened: set = set()
    for i in range(1, count + 1):
        # Get into the reader by opening any message.
        root = goto_mailbox(adb, package, log)
        if root is None:
            samples.append(BackNavSample(index=i, back_ms=None, note="not on mailbox"))
            continue
        rows = uidump.find_message_rows(root, package)
        if not rows:
            samples.append(BackNavSample(index=i, back_ms=None, note="no rows"))
            continue
        adb.input_tap(*rows[0].center)
        wait_for_reader_ready(adb, package, log)
        # Now time the back transition to the mailbox.
        start = time.monotonic()
        adb.input_keyevent("KEYCODE_BACK")
        reached = _wait_until(
            lambda: _is_mailbox(adb, package), timeout_s=30.0
        )
        elapsed_ms = round((time.monotonic() - start) * 1000)
        samples.append(
            BackNavSample(
                index=i,
                back_ms=elapsed_ms if reached else None,
                note="" if reached else "did not reach mailbox",
            )
        )
    return samples


def _is_mailbox(adb: Adb, package: str) -> bool:
    root = dump_ui(adb)
    return root is not None and uidump.is_app_foreground(root, package) and not uidump.is_reader(
        root, package
    )


def _wait_until(predicate: Callable[[], bool], timeout_s: float) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.2)
    return False


# --------------------------------------------------------------------------- #
# Scenario 4: prefetch A/B (fetch policy toggle)
# --------------------------------------------------------------------------- #
def set_fetch_policy(
    adb: Adb,
    package: str,
    option_label: str,
    log: Callable[[str], None],
) -> bool:
    """Navigate Settings and select a fetch-policy option by its visible label.

    NOTE: no uiautomator dump of the settings screen was captured in the manual run, so the
    settings-screen navigation is by on-screen text and needs live verification (documented
    in the README). Returns True if the option was found and tapped.
    """
    if adb.dry_run:
        adb.uiautomator_dump()
        adb.input_tap(1014, 2728)   # bottom-nav "Settings"
        adb.uiautomator_dump()
        adb.input_tap(672, 1400)    # representative fetch-policy option
        adb.input_keyevent("KEYCODE_BACK")
        return True

    root = goto_mailbox(adb, package, log)
    if root is None:
        return False
    settings_label = uidump.find_by_text(root, "Settings")
    if settings_label is None:
        log("no Settings entry found in bottom nav")
        return False
    anchor = settings_label.first_clickable_ancestor() or settings_label
    if anchor.center:
        adb.input_tap(*anchor.center)
    time.sleep(SETTLE_S)

    # Find the fetch-policy option, scrolling the settings list if necessary.
    for _ in range(6):
        sroot = guard_ready(adb, package, log)
        if sroot is None:
            return False
        option = uidump.find_by_text(sroot, option_label)
        if option is not None:
            anchor = option.first_clickable_ancestor() or option
            if anchor.center:
                adb.input_tap(*anchor.center)
                log(f"selected fetch policy {option_label!r}")
                time.sleep(SETTLE_S)
                adb.input_keyevent("KEYCODE_BACK")  # back to mailbox
                time.sleep(SETTLE_S)
                return True
        adb.input_swipe(672, 2000, 672, 900, 400)
        time.sleep(SETTLE_S)
    log(f"fetch-policy option {option_label!r} not found")
    return False


def prefetch_ab(
    adb: Adb,
    package: str,
    component: str,
    tailer: LogTailer,
    count: int,
    log: Callable[[str], None],
) -> dict:
    """Run message-open under prefetch ON (Wi-Fi) vs OFF (on-demand), cache cleared between."""
    result = {"conditionA": [], "conditionB": []}

    log("Condition A: fetch policy = Fetch all on Wi-Fi (prefetch ON)")
    set_fetch_policy(adb, package, FETCH_WIFI_LABEL, log)
    _reset_for_uncached(adb, component, log)
    result["conditionA"] = message_open(adb, package, tailer, count, log)

    log("Condition B: fetch policy = Always on-demand (prefetch OFF)")
    set_fetch_policy(adb, package, FETCH_ON_DEMAND_LABEL, log)
    _reset_for_uncached(adb, component, log)
    result["conditionB"] = message_open(adb, package, tailer, count, log)
    return result


def _reset_for_uncached(adb: Adb, component: str, log: Callable[[str], None]) -> None:
    """Clear the cache (and restart) so subsequent opens are uncached network fetches."""
    adb.force_stop()
    adb.clear_cache()
    adb.settle(1.0)
    adb.start_activity(component=component, wait=True)
    adb.settle(3.0)


# --------------------------------------------------------------------------- #
# Scenario 5: cross-provider (optional)
# --------------------------------------------------------------------------- #
def cross_provider(
    adb: Adb,
    package: str,
    tailer: LogTailer,
    count: int,
    log: Callable[[str], None],
) -> dict:
    """Open ``count`` messages from the (unified) inbox and bucket the rows by account_ref.

    The breadcrumbs carry the account reference (``imap:...`` vs ``outlook:...``), so a
    single mixed-inbox pass yields the per-provider comparison without account switching.
    """
    rows = message_open(adb, package, tailer, count, log)
    buckets: dict = {}
    for row in rows:
        key = row.account_ref or "unknown"
        buckets.setdefault(key, []).append(row)
    return buckets


# --------------------------------------------------------------------------- #
# Scenario 6: cold-fetch pause-hook A/B (issue #405)
# --------------------------------------------------------------------------- #
# The proven flow (2026-07-06): pre-arm the FETCH_GATE halt BEFORE sign-in so proactive body
# fetch is gated the instant sync starts; detect sign-in; confirm the halt took effect; let
# headers sync (bodies stay uncached); measure genuine COLD opens; resume the gate; measure
# WARM (cached) re-opens of the same messages; report the cold-vs-warm delta, the connect=0ms
# connection-reuse proof, and any server-side throttle signature. Needs a **debug build** --
# the pause hook (#393/#395) is compiled only into src/debug and R8-stripped from release.


def wait_for_open_breadcrumb(
    tailer: LogTailer, timeout_s: float, log: Callable[[str], None]
) -> List[breadcrumbs.Event]:
    """Poll the tail until a ``MailReader openMessage`` breadcrumb lands; return every event.

    An ``openMessage`` marker is the authoritative "the reader open finished" signal -- far
    more reliable than UI polling for a spinner under load (issue #392). All perf events seen
    (the ``ImapPerf``/``body-fetch`` lines precede the marker) are returned so the caller can
    correlate the full open without re-reading -- draining them here would lose them.
    """
    deadline = time.monotonic() + timeout_s
    collected: List[breadcrumbs.Event] = []
    while True:
        for line in tailer.read_new().splitlines():
            event = breadcrumbs.parse_breadcrumb(line)
            if event is not None:
                collected.append(event)
        if any(isinstance(e, breadcrumbs.OpenMessage) for e in collected):
            return collected
        if time.monotonic() >= deadline:
            log("openMessage breadcrumb not seen before timeout")
            return collected
        time.sleep(POLL_INTERVAL_S)


def wait_for_sign_in(
    tailer: LogTailer, timeout_s: float, log: Callable[[str], None]
) -> Optional[int]:
    """Tail the log until the ``MailSyncer: sync all: N accounts`` breadcrumb; return N.

    Sign-in is manual on the device (OAuth can't be automated), so this simply waits for the
    first sync pass a fresh sign-in kicks off. ``None`` on timeout.
    """
    deadline = time.monotonic() + timeout_s
    while True:
        for line in tailer.read_new().splitlines():
            accounts = breadcrumbs.match_sync_all(line)
            if accounts is not None:
                log(f"sign-in detected: sync all: {accounts} account(s)")
                return accounts
        if time.monotonic() >= deadline:
            log("sign-in not detected within timeout")
            return None
        time.sleep(POLL_INTERVAL_S)


def wait_for_gate_skip(
    tailer: LogTailer, timeout_s: float, log: Callable[[str], None]
) -> bool:
    """Tail until the ``prefetch skipped: fetch-gate paused`` breadcrumb proves the halt held."""
    deadline = time.monotonic() + timeout_s
    while True:
        for line in tailer.read_new().splitlines():
            if breadcrumbs.is_fetch_gate_skip(line):
                log("fetch-gate halt confirmed: prefetch skipped")
                return True
        if time.monotonic() >= deadline:
            log("fetch-gate skip breadcrumb not seen (halt unconfirmed)")
            return False
        time.sleep(POLL_INTERVAL_S)


def wait_for_header_sync(
    adb: Adb, package: str, timeout_s: float, log: Callable[[str], None]
) -> bool:
    """Poll the mailbox until at least one uncached message row is visible (headers synced)."""
    deadline = time.monotonic() + timeout_s
    while True:
        root = goto_mailbox(adb, package, log)
        if root is not None:
            rows = uidump.find_message_rows(root, package)
            uncached = [r for r in rows if not r.cached]
            if uncached:
                log(f"header sync ready: {len(uncached)} uncached row(s) visible")
                return True
        if time.monotonic() >= deadline:
            log("header sync not confirmed within timeout")
            return False
        time.sleep(POLL_INTERVAL_S)


def _open_target(
    adb: Adb,
    package: str,
    tailer: LogTailer,
    index: int,
    label: str,
    center: Optional[tuple],
    log: Callable[[str], None],
) -> ReaderOpenRow:
    """Tap a known message row, time the open from the ``openMessage`` breadcrumb, go back."""
    if center is None:
        return ReaderOpenRow(index=index, label=label, skipped=True, reason="no tap target")
    tailer.mark()
    log(f"opening #{index} {label!r} at {center}")
    adb.input_tap(*center)
    events = wait_for_open_breadcrumb(tailer, OPEN_TIMEOUT_S, log)
    row = _row_from_events(index, label, events)
    if not any(isinstance(e, breadcrumbs.OpenMessage) for e in events) and not row.skipped:
        row.skipped = True
        row.reason = "no openMessage breadcrumb"
    adb.input_keyevent("KEYCODE_BACK")
    time.sleep(SETTLE_S)
    return row


def _next_uncached(
    adb: Adb, package: str, already: set, log: Callable[[str], None]
) -> Optional[uidump.MessageRow]:
    """The next not-yet-opened uncached mailbox row (scrolling once to reveal more)."""
    root = goto_mailbox(adb, package, log)
    if root is None:
        return None
    rows = uidump.find_message_rows(root, package)
    cands = [r for r in rows if not r.cached and r.label not in already]
    if cands:
        return cands[0]
    adb.input_swipe(672, 2000, 672, 900, 400)
    time.sleep(SETTLE_S)
    root = guard_ready(adb, package, log)
    if root is None:
        return None
    cands = [
        r
        for r in uidump.find_message_rows(root, package)
        if not r.cached and r.label not in already
    ]
    return cands[0] if cands else None


def measure_cold_opens(
    adb: Adb,
    package: str,
    tailer: LogTailer,
    count: int,
    log: Callable[[str], None],
) -> tuple:
    """Open up to ``count`` distinct uncached messages; return ``(rows, [(label, center)])``.

    The ``(label, center)`` list identifies exactly which messages to re-open for the warm
    phase.
    """
    rows: List[ReaderOpenRow] = []
    opened: List[tuple] = []
    already: set = set()
    for i in range(1, count + 1):
        target = _next_uncached(adb, package, already, log)
        if target is None:
            log("no more uncached messages to open (cold phase)")
            break
        already.add(target.label)
        rows.append(_open_target(adb, package, tailer, i, target.label, target.center, log))
        opened.append((target.label, target.center))
    return rows, opened


def measure_warm_opens(
    adb: Adb,
    package: str,
    tailer: LogTailer,
    opened: List[tuple],
    log: Callable[[str], None],
) -> List[ReaderOpenRow]:
    """Re-open each message from :func:`measure_cold_opens`; bodies are now cached (warm)."""
    rows: List[ReaderOpenRow] = []
    for i, (label, center) in enumerate(opened, start=1):
        # Re-find the row by label for a fresh centre (the list may have shifted); fall back
        # to the cold-phase centre.
        tgt_center = center
        root = goto_mailbox(adb, package, log)
        if root is not None:
            for r in uidump.find_message_rows(root, package):
                if r.label == label:
                    tgt_center = r.center
                    break
        rows.append(_open_target(adb, package, tailer, i, label, tgt_center, log))
    return rows


def _gate_shown(state) -> str:
    """The read-back payload for a log line (duck-typed on the fetchgate BroadcastResult)."""
    if state is not None and getattr(state, "read_back", False):
        return state.data
    return "(no read-back)"


def cold_fetch_ab(
    adb: Adb,
    package: str,
    component: str,
    tailer: LogTailer,
    gate: "fetchgate.FetchGate",
    count: int,
    log: Callable[[str], None],
    sign_in_timeout_s: float = SIGN_IN_TIMEOUT_S,
    gate_confirm_timeout_s: float = GATE_CONFIRM_TIMEOUT_S,
    header_sync_timeout_s: float = HEADER_SYNC_TIMEOUT_S,
) -> dict:
    """Cold-fetch pause-hook A/B: pre-arm halt, sign-in, cold opens, resume, warm opens.

    ALWAYS clears the gate at the end (``finally``) so a device is never left with proactive
    fetch paused, even on error.
    """
    results: dict = {
        "gate_prearm": None,
        "sign_in_accounts": None,
        "gate_confirmed": False,
        "header_ready": False,
        "cold": [],
        "warm": [],
        "gate_resume": None,
        "gate_restored": None,
    }
    try:
        # 1. Pre-arm the halt BEFORE sign-in so proactive fetch is gated as soon as sync starts.
        log(f"pre-arming fetch-gate halt (pause {fetchgate.DEFAULT_SCOPE})")
        results["gate_prearm"] = gate.pause(fetchgate.DEFAULT_SCOPE)
        log(f"pre-armed halt read-back: {_gate_shown(results['gate_prearm'])}")

        if adb.dry_run:
            _dry_run_cold_fetch_demo(adb, gate, log)
            results["cold"] = [
                ReaderOpenRow(index=1, label="<dry-run cold>", skipped=True, reason="dry-run")
            ]
            results["warm"] = [
                ReaderOpenRow(index=1, label="<dry-run warm>", skipped=True, reason="dry-run")
            ]
            return results

        # 2. Detect the manual sign-in via the sync-start breadcrumb.
        log("waiting for sign-in (add an account on the device now)")
        results["sign_in_accounts"] = wait_for_sign_in(tailer, sign_in_timeout_s, log)
        # 3. Confirm the pre-armed halt actually took effect.
        results["gate_confirmed"] = wait_for_gate_skip(tailer, gate_confirm_timeout_s, log)
        # 4. Wait for header sync so uncached rows exist to open.
        results["header_ready"] = wait_for_header_sync(
            adb, package, header_sync_timeout_s, log
        )
        # 5. Measure COLD opens -- bodies uncached because prefetch is gated.
        log("measuring cold opens (uncached bodies)")
        cold_rows, opened = measure_cold_opens(adb, package, tailer, count, log)
        results["cold"] = cold_rows
        # 6. Resume (restore normal fetch), then measure WARM re-opens of the same messages.
        log(f"resuming fetch-gate before warm phase (resume {fetchgate.DEFAULT_SCOPE})")
        results["gate_resume"] = gate.resume(fetchgate.DEFAULT_SCOPE)
        log("measuring warm opens (cached re-open of the same messages)")
        results["warm"] = measure_warm_opens(adb, package, tailer, opened, log)
    finally:
        # Restore semantics: ALWAYS clear the gate, even on error -- never leave fetch paused.
        results["gate_restored"] = gate.resume(fetchgate.DEFAULT_SCOPE)
        log(f"fetch-gate restored (cleared): {_gate_shown(results['gate_restored'])}")
    return results


def _dry_run_cold_fetch_demo(
    adb: Adb, gate: "fetchgate.FetchGate", log: Callable[[str], None]
) -> None:
    """Issue the canonical cold-fetch A/B command shape once (dry-run only).

    The pre-arm ``pause`` and the final restoring ``resume`` are issued by :func:`cold_fetch_ab`
    itself (the latter in its ``finally``); this fills in a representative cold open, the
    warm-phase ``resume``, a warm re-open, and a state ``query`` so the whole plan is auditable.
    """
    adb.uiautomator_dump()
    adb.input_tap(672, 504)  # cold-open a representative message row
    adb.input_keyevent("KEYCODE_BACK")
    gate.resume(fetchgate.DEFAULT_SCOPE)  # restore normal fetch before the warm phase
    adb.uiautomator_dump()
    adb.input_tap(672, 504)  # warm re-open (now cached)
    adb.input_keyevent("KEYCODE_BACK")
    gate.query(fetchgate.DEFAULT_SCOPE)  # confirm the gate state
