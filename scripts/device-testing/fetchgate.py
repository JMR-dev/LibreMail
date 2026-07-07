#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""fetchgate.py -- the debug FETCH_GATE pause-hook helpers.

Thin, testable wrapper around the debug-only ``FetchGateReceiver`` broadcast hook
(issues #393/#395) that lets the perf harness pause/resume the app's *proactive* body-fetch
activities (``backfill`` + ``prefetch``) so a genuinely uncached message-open can be measured.
Everything goes through the guarded :class:`adb.Adb` wrapper, so the device-safety guardrails
apply unchanged (the broadcast targets our own package/component; nothing destructive).

The broadcast the harness sends (delivered *ordered*, targeted by ``-n`` so it needs no
``<intent-filter>``)::

    adb shell am broadcast -a org.libremail.debug.FETCH_GATE \
      -n org.libremail.app/org.libremail.debug.FetchGateReceiver \
      --es action <pause|resume|query> --es scope backfill,prefetch

Because ``am broadcast`` delivers ordered, the receiver returns the resulting gate state as
result data, which ``am`` prints, e.g.::

    Broadcast completed: result=0, data="paused=[backfill,prefetch]"

:func:`parse_broadcast_result` turns that into a typed :class:`BroadcastResult` for a
synchronous, race-free read-back. **This hook only exists in a debug build** -- it is declared
solely in ``app/src/debug`` and R8-stripped from release -- so the cold-fetch scenario needs a
debug APK installed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, FrozenSet, Optional

# Wire constants -- mirror FetchGateReceiver / FetchScope in the app source.
FETCH_GATE_ACTION = "org.libremail.debug.FETCH_GATE"
FETCH_GATE_RECEIVER = "org.libremail.debug.FetchGateReceiver"

ACTION_PAUSE = "pause"
ACTION_RESUME = "resume"
ACTION_QUERY = "query"

# The two proactive scopes the harness pauses to keep bodies uncached (FetchScope wire names).
SCOPE_BACKFILL = "backfill"
SCOPE_PREFETCH = "prefetch"
DEFAULT_SCOPE = f"{SCOPE_BACKFILL},{SCOPE_PREFETCH}"


# --------------------------------------------------------------------------- #
# Read-back parsing (pure; unit-tested)
# --------------------------------------------------------------------------- #
# `am broadcast` prints e.g. `Broadcast completed: result=0, data="paused=[backfill,prefetch]"`.
# Parsed in two steps so a trailing `, extras: ...` (absent here, but possible) can't be
# swallowed into the data payload: match the result code, then the quoted data separately.
_RESULT_RE = re.compile(r"Broadcast completed:\s*result=(?P<code>-?\d+)")
_DATA_RE = re.compile(r'data="(?P<data>[^"]*)"')
# The receiver's result-data payload: `paused=[<comma-list>]`.
_PAUSED_RE = re.compile(r"paused=\[(?P<scopes>[^\]]*)\]")


@dataclass(frozen=True)
class BroadcastResult:
    """The parsed outcome of one FETCH_GATE ``am broadcast`` read-back.

    * ``result_code`` -- the ordered-broadcast result code (``0`` from the receiver), or
      ``None`` if the ``Broadcast completed:`` line was absent (e.g. dry-run / no read-back).
    * ``data`` -- the raw result-data payload (``paused=[backfill,prefetch]``) or ``None``.
    * ``paused`` -- the scope wire-names parsed out of ``data`` (``frozenset``; empty when the
      gate is clear or unparseable).
    """

    result_code: Optional[int]
    data: Optional[str]
    paused: FrozenSet[str]

    @property
    def read_back(self) -> bool:
        """True iff a real ``Broadcast completed: ... data=...`` read-back was parsed."""
        return self.data is not None

    def is_paused(self, scope: str) -> bool:
        return scope in self.paused


def parse_paused_scopes(data: Optional[str]) -> FrozenSet[str]:
    """Parse ``paused=[backfill,prefetch]`` -> ``{"backfill", "prefetch"}`` (empty if none)."""
    if not data:
        return frozenset()
    m = _PAUSED_RE.search(data)
    if not m:
        return frozenset()
    inner = m.group("scopes").strip()
    if not inner:
        return frozenset()
    return frozenset(tok.strip() for tok in inner.split(",") if tok.strip())


def parse_broadcast_result(output: str) -> BroadcastResult:
    """Parse ``am broadcast`` stdout into a :class:`BroadcastResult`.

    Tolerant of surrounding lines (the ``Broadcasting: Intent {...}`` echo) and of a missing
    ``data=`` (a non-read-back send); returns an all-empty result when no completion line is
    present (e.g. dry-run stdout is empty).
    """
    if not output:
        return BroadcastResult(result_code=None, data=None, paused=frozenset())
    code: Optional[int] = None
    data: Optional[str] = None
    for line in output.splitlines():
        m = _RESULT_RE.search(line)
        if not m:
            continue
        code = int(m.group("code"))
        dm = _DATA_RE.search(line)
        if dm:
            data = dm.group("data")
        break
    return BroadcastResult(result_code=code, data=data, paused=parse_paused_scopes(data))


# --------------------------------------------------------------------------- #
# The pause-hook helper (drives the guarded adb wrapper)
# --------------------------------------------------------------------------- #
def _noop(_msg: str) -> None:
    pass


class FetchGate:
    """Pause/resume/query the debug FETCH_GATE through a guarded :class:`adb.Adb`.

    Construct with the harness's ``Adb`` (its ``package`` fixes the broadcast component and is
    enforced by the safety guard). Each call sends the broadcast and returns the parsed
    :class:`BroadcastResult` read-back. In dry-run the underlying ``adb`` prints the command
    plan and returns empty stdout, so the result is an all-empty (no read-back) record.
    """

    def __init__(
        self,
        adb,
        receiver: str = FETCH_GATE_RECEIVER,
        action: str = FETCH_GATE_ACTION,
        log: Optional[Callable[[str], None]] = None,
    ) -> None:
        self.adb = adb
        self.action = action
        # `-n <package>/<receiver>` (assert_safe requires the component package == adb.package).
        self.component = f"{adb.package}/{receiver}"
        self._log = log or _noop

    def _send(self, gate_action: str, scope: str) -> BroadcastResult:
        res = self.adb.broadcast(
            self.action, self.component, {"action": gate_action, "scope": scope}
        )
        parsed = parse_broadcast_result(res.stdout)
        shown = parsed.data if parsed.read_back else "(no read-back)"
        self._log(f"fetch-gate {gate_action} scope={scope} -> {shown}")
        return parsed

    def pause(self, scope: str = DEFAULT_SCOPE) -> BroadcastResult:
        """Pause the given proactive-fetch scope(s); returns the read-back gate state."""
        return self._send(ACTION_PAUSE, scope)

    def resume(self, scope: str = DEFAULT_SCOPE) -> BroadcastResult:
        """Resume (clear) the given scope(s); returns the read-back gate state."""
        return self._send(ACTION_RESUME, scope)

    def query(self, scope: str = DEFAULT_SCOPE) -> BroadcastResult:
        """Query the current gate state without changing it; returns the read-back."""
        return self._send(ACTION_QUERY, scope)
