#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""report.py -- aggregate scenario samples and render ``timing-tables.md``.

Pure and testable: given the samples a scenario collected, it produces the same
per-scenario markdown tables + aggregates as the hand-written ``timing-tables.md`` from the
manual run. No device or I/O dependency beyond writing the final file.
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass
from typing import List, Optional, Sequence

from breadcrumbs import OpenSample

# The floor below which adb uiautomator-dump timings cannot resolve an in-app transition
# (see perf_summary.md: nav/back were dominated by the ~2.5-3 s dump latency).
UIAUTOMATOR_LATENCY_FLOOR_MS = 3000

# A cold IMAP ``work`` time at/above this reads as a server-side throttle signature (the manual
# Gmail run saw 26-72 s of work vs Outlook's sub-second control).
THROTTLE_WORK_MS = 10000


# --------------------------------------------------------------------------- #
# Stats + markdown helpers
# --------------------------------------------------------------------------- #
@dataclass
class Aggregate:
    n: int
    mean: Optional[float]
    median: Optional[float]
    minimum: Optional[int]
    maximum: Optional[int]


def aggregate(values: Sequence[float]) -> Aggregate:
    """Summarise a list of numbers; safe on an empty list."""
    vals = [v for v in values if v is not None]
    if not vals:
        return Aggregate(0, None, None, None, None)
    return Aggregate(
        n=len(vals),
        mean=statistics.fmean(vals),
        median=statistics.median(vals),
        minimum=min(vals),
        maximum=max(vals),
    )


def md_table(headers: Sequence[str], rows: Sequence[Sequence[object]]) -> str:
    """Render a GitHub-flavoured markdown table."""
    head = "| " + " | ".join(str(h) for h in headers) + " |"
    sep = "| " + " | ".join("---" for _ in headers) + " |"
    body = [
        "| " + " | ".join("" if c is None else str(c) for c in row) + " |" for row in rows
    ]
    return "\n".join([head, sep, *body])


def _ms(value: Optional[float]) -> str:
    return "" if value is None else f"{round(value)}"


# --------------------------------------------------------------------------- #
# Cold open
# --------------------------------------------------------------------------- #
@dataclass
class ColdOpenSample:
    run: int
    total_time_ms: Optional[int]
    wait_time_ms: Optional[int]
    note: str = ""


def render_cold_open(samples: Sequence[ColdOpenSample]) -> str:
    rows: List[Sequence[object]] = []
    for s in samples:
        rows.append([s.run, _ms(s.total_time_ms), _ms(s.wait_time_ms), s.note])
    total_agg = aggregate([s.total_time_ms for s in samples if s.total_time_ms is not None])
    wait_agg = aggregate([s.wait_time_ms for s in samples if s.wait_time_ms is not None])
    rows.append(
        [
            "**mean**",
            f"**{_ms(total_agg.mean)}**",
            f"**{_ms(wait_agg.mean)}**",
            f"median {_ms(total_agg.median)} / {_ms(wait_agg.median)}",
        ]
    )
    table = md_table(["Run", "TotalTime (ms)", "WaitTime (ms)", "note"], rows)
    return (
        "## Cold open (am start -W, cache cleared each run)\n\n"
        + table
        + "\n\nColdest run is the first post-clear launch (class-load/JIT); steady state is lower.\n"
    )


# --------------------------------------------------------------------------- #
# Message open (reader body-load)
# --------------------------------------------------------------------------- #
@dataclass
class ReaderOpenRow:
    """A flattened, render-ready reader-open row (mirrors a timing-tables.md line)."""

    index: int
    label: str
    account_ref: str = ""
    cached: bool = False
    took_ms: Optional[int] = None
    reader_ready_ms: Optional[int] = None
    connect_ms: Optional[int] = None
    work_ms: Optional[int] = None
    select_ms: Optional[int] = None
    body_ms: Optional[int] = None
    flag_ms: Optional[int] = None
    live: Optional[int] = None
    rfc822_bytes: Optional[int] = None
    body_kb_per_s: Optional[float] = None
    skipped: bool = False
    reason: str = ""

    @classmethod
    def from_open_sample(
        cls, sample: OpenSample, index: int, label: str
    ) -> "ReaderOpenRow":
        bf = sample.body_fetch
        op = sample.body_fetch_op
        return cls(
            index=index,
            label=label,
            account_ref=sample.account_ref,
            cached=sample.cached,
            took_ms=sample.took_ms,
            reader_ready_ms=sample.reader_ready.took_ms if sample.reader_ready else None,
            connect_ms=op.connect_ms if op else None,
            work_ms=op.work_ms if op else None,
            select_ms=bf.select_ms if bf else None,
            body_ms=bf.body_ms if bf else None,
            flag_ms=bf.flag_ms if bf else None,
            live=op.live if op else None,
            rfc822_bytes=bf.rfc822_bytes if bf else None,
            body_kb_per_s=sample.body_kb_per_s,
        )


_OPEN_HEADERS = [
    "#",
    "message",
    "cached",
    "rfc822 B",
    "openMessage took",
    "reader ready",
    "connect",
    "work",
    "select",
    "body-dl",
    "flag",
    "live",
    "body KB/s",
]


def _open_row_cells(row: ReaderOpenRow) -> Sequence[object]:
    if row.skipped:
        return [
            row.index,
            row.label,
            "-",
            f"SKIPPED: {row.reason}",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
        ]
    kbps = "" if row.body_kb_per_s is None else f"{row.body_kb_per_s:.1f}"
    return [
        row.index,
        row.label,
        "yes" if row.cached else "no",
        row.rfc822_bytes if row.rfc822_bytes is not None else "",
        _fmt_ms(row.took_ms),
        _fmt_ms(row.reader_ready_ms),
        _ms(row.connect_ms),
        _ms(row.work_ms),
        _ms(row.select_ms),
        _ms(row.body_ms),
        _ms(row.flag_ms),
        _ms(row.live),
        kbps,
    ]


def _fmt_ms(value: Optional[int]) -> str:
    return "" if value is None else f"{value} ms"


def render_message_open(title: str, rows: Sequence[ReaderOpenRow]) -> str:
    table = md_table(_OPEN_HEADERS, [_open_row_cells(r) for r in rows])
    uncached = [r for r in rows if not r.skipped and not r.cached and r.took_ms is not None]
    agg = aggregate([r.took_ms for r in uncached])
    lines = [f"## {title}", "", table, ""]
    if agg.n:
        lines.append(
            f"Uncached opens: n={agg.n}, openMessage median "
            f"{_ms(agg.median)} ms, mean {_ms(agg.mean)} ms, "
            f"range {agg.minimum}-{agg.maximum} ms."
        )
    skipped = [r for r in rows if r.skipped]
    if skipped:
        lines.append(f"Skipped samples: {len(skipped)} (see rows above).")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- #
# Back navigation
# --------------------------------------------------------------------------- #
@dataclass
class BackNavSample:
    index: int
    back_ms: Optional[int]
    note: str = ""


def render_back_nav(samples: Sequence[BackNavSample]) -> str:
    rows = [[s.index, _fmt_ms(s.back_ms), s.note] for s in samples]
    agg = aggregate([s.back_ms for s in samples if s.back_ms is not None])
    table = md_table(["#", "back (reader->mailbox)", "note"], rows)
    caveat = (
        f"\n\n**Caveat:** these are dominated by the ~{UIAUTOMATOR_LATENCY_FLOOR_MS} ms "
        "uiautomator-dump latency floor; true in-app back is sub-second and not precisely "
        "measurable via adb UI polling under load (see perf_summary.md)."
    )
    summary = "" if not agg.n else f"\n\nBack: n={agg.n}, mean {_ms(agg.mean)} ms, median {_ms(agg.median)} ms."
    return "## Reader -> mailbox (back)\n\n" + table + summary + caveat + "\n"


# --------------------------------------------------------------------------- #
# Cold-fetch pause-hook A/B (issue #405)
# --------------------------------------------------------------------------- #
def _gate_data(state) -> str:
    """The read-back payload of a fetchgate ``BroadcastResult`` (duck-typed), for display."""
    if state is None:
        return "(none)"
    data = getattr(state, "data", None)
    return data if data else "(no read-back)"


def _uncached_took(rows: Sequence["ReaderOpenRow"]) -> List[int]:
    return [r.took_ms for r in rows if not r.skipped and r.took_ms is not None]


def render_gate_summary(results: dict) -> str:
    """Render the FETCH_GATE control summary: pre-arm, sign-in, halt-confirm, restore."""
    accounts = results.get("sign_in_accounts")
    sign_in = f"yes ({accounts} account(s))" if accounts is not None else "no / not detected"
    lines = [
        "## Fetch-gate A/B control",
        "",
        f"- Pre-armed halt read-back: `{_gate_data(results.get('gate_prearm'))}`",
        f"- Sign-in detected (sync all breadcrumb): {sign_in}",
        "- Halt confirmed (prefetch-skipped breadcrumb): "
        + ("yes" if results.get("gate_confirmed") else "no"),
        "- Header sync ready: " + ("yes" if results.get("header_ready") else "no"),
        f"- Gate restored/cleared read-back: `{_gate_data(results.get('gate_restored'))}`",
        "",
        "> The pause hook is **debug-build-only** (#393/#395): the scenario needs a debug APK.",
        "",
    ]
    return "\n".join(lines)


def render_cold_warm_comparison(
    cold: Sequence["ReaderOpenRow"], warm: Sequence["ReaderOpenRow"]
) -> str:
    """Render the cold-vs-warm delta, the connect=0ms reuse proof, and any throttle signature."""
    cold_agg = aggregate(_uncached_took(cold))
    warm_agg = aggregate(_uncached_took(warm))
    lines = ["## Cold vs warm (A/B)", ""]
    lines.append(
        f"- Cold median openMessage: {_ms(cold_agg.median)} ms (n={cold_agg.n})"
    )
    lines.append(
        f"- Warm median openMessage: {_ms(warm_agg.median)} ms (n={warm_agg.n})"
    )
    if cold_agg.median and warm_agg.median:
        lines.append(
            f"- Speedup (cold/warm median): {cold_agg.median / warm_agg.median:.1f}x"
        )

    # connect=0ms connection-reuse proof (from the cold opens' ImapPerf op).
    connects = [r.connect_ms for r in cold if not r.skipped and r.connect_ms is not None]
    if connects:
        zero = [c for c in connects if c == 0]
        proof = "reuse active" if zero else "no reuse observed"
        lines.append(
            f"- Connection reuse: connect=0ms on {len(zero)}/{len(connects)} "
            f"cold opens ({proof})"
        )

    # Throttle signature -- a very high cold IMAP work time.
    works = [r.work_ms for r in cold if not r.skipped and r.work_ms is not None]
    if works:
        peak = max(works)
        note = " -- server-side throttle signature" if peak >= THROTTLE_WORK_MS else ""
        lines.append(f"- Peak cold IMAP work: {peak} ms{note}")
    lines.append("")
    return "\n".join(lines)


def render_cold_fetch_ab(results: dict) -> str:
    """Assemble the whole cold-fetch A/B section: control summary, cold/warm tables, A/B delta."""
    cold = results.get("cold", [])
    warm = results.get("warm", [])
    parts = [
        render_gate_summary(results),
        render_message_open("Cold opens (fetch-gate paused, uncached bodies)", cold),
        render_message_open("Warm opens (gate resumed, cached re-open)", warm),
        render_cold_warm_comparison(cold, warm),
    ]
    return "\n".join(parts)


# --------------------------------------------------------------------------- #
# Document assembly
# --------------------------------------------------------------------------- #
def render_header(metadata: dict) -> str:
    lines = ["# LibreMail device perf run", ""]
    for key, value in metadata.items():
        lines.append(f"- **{key}:** {value}")
    lines.append("")
    return "\n".join(lines)


def build_document(metadata: dict, sections: Sequence[str]) -> str:
    parts = [render_header(metadata), *sections]
    return "\n".join(parts).rstrip() + "\n"
