#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""breadcrumbs.py -- pure parser for LibreMail on-device perf breadcrumbs.

This module is the *core* of the device-testing harness and is intentionally free of any
I/O, ``adb``, or device dependency so it can be unit-tested against the saved logcat
captures from the manual perf run (see ``tests/test_breadcrumbs.py`` and
``tests/fixtures/perf-extract-sample.log``).

It parses two things:

1. A raw ``adb logcat -v threadtime`` line into its fields (:func:`parse_logcat_line`).
2. The message payload of the four perf tags into typed events
   (:func:`parse_breadcrumb` and the per-tag helpers).

Breadcrumb formats (verbatim from the app source, all PII-free):

* ``ImapPerf:  <op> connect=<ms>ms work=<ms>ms live=<N>``
      -- ``ImapClient`` generic per-operation timing; ``op`` is a short label
      (``prefetch-body``, ``backfill-page``, ``body-fetch``, ``imap`` ...).
* ``ImapPerf:  body-fetch select=<ms>ms body=<ms>ms flag=<ms>ms rfc822=<n>B chars=<n> att=<n>``
      -- ``ImapClient`` detailed body-fetch phase split (emitted just before the generic
      ``body-fetch connect=.. work=.. live=..`` line for the same fetch).
* ``MailReader: openMessage <acctRef> folder=<label> fetchedBody=<bool> took=<ms>ms``
      -- ``MailRepositoryImpl`` reader-open; ``fetchedBody=true`` means a real network body
      fetch happened (i.e. the message was *not* cached).
* ``Reader:     reader ready took=<ms>ms html=<bool> inline=<n>``
      -- ``ReaderViewModel`` spinner-to-content time.
* ``MailBackfiller: backfill <acctRef> folder=<label> pages=<n> complete=<bool>``
* ``MailBackfiller: backfill slice: maxBatches=<n>``
* ``MailBackfiller: backfill slice done: moreWork=<bool>``

The threadtime tag column is space-padded by logcat (e.g. ``Reader  :``); the parser
tolerates that and reports the trimmed tag (``Reader``).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable, Iterator, List, Optional, Union

# Tags that make up the filtered perf extract (mirrors the manual
# `grep -E 'ImapPerf|MailReader|Reader|MailBackfiller'`). Matched on the *tag* column so a
# stray mention of one of these words inside another tag's message is not miscounted.
PERF_TAGS = ("ImapPerf", "MailReader", "Reader", "MailBackfiller")


# --------------------------------------------------------------------------- #
# Raw logcat line (``-v threadtime``)
# --------------------------------------------------------------------------- #
_THREADTIME_RE = re.compile(
    r"^(?P<date>\d{2}-\d{2})\s+"
    r"(?P<time>\d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"(?P<pid>\d+)\s+"
    r"(?P<tid>\d+)\s+"
    r"(?P<level>[VDIWEFS])\s+"
    r"(?P<tag>[^:]+?)\s*:\s?"
    r"(?P<message>.*)$"
)


@dataclass(frozen=True)
class LogLine:
    """A single ``adb logcat -v threadtime`` line, split into fields."""

    date: str
    time: str
    pid: int
    tid: int
    level: str
    tag: str
    message: str
    raw: str

    @property
    def timestamp(self) -> str:
        """`MM-DD HH:MM:SS.mmm` -- the wall-clock stamp (no year in logcat)."""
        return f"{self.date} {self.time}"


def parse_logcat_line(line: str) -> Optional[LogLine]:
    """Parse one threadtime logcat line; return ``None`` if it is not one."""
    m = _THREADTIME_RE.match(line.rstrip("\r\n"))
    if not m:
        return None
    return LogLine(
        date=m.group("date"),
        time=m.group("time"),
        pid=int(m.group("pid")),
        tid=int(m.group("tid")),
        level=m.group("level"),
        tag=m.group("tag").strip(),
        message=m.group("message").strip(),
        raw=line.rstrip("\r\n"),
    )


def is_perf_line(line: str) -> bool:
    """True if ``line`` is a threadtime line whose tag is one of :data:`PERF_TAGS`."""
    parsed = parse_logcat_line(line)
    return parsed is not None and parsed.tag in PERF_TAGS


# --------------------------------------------------------------------------- #
# Typed breadcrumb events
# --------------------------------------------------------------------------- #
class Breadcrumb:
    """Base type: every event optionally carries its source :class:`LogLine`.

    A plain (non-dataclass) base so each subclass can declare its own fields with ``line``
    last -- this keeps the generated ``__init__`` valid on Python 3.7+ without the
    3.10-only ``kw_only`` field option (a base dataclass field with a default would force a
    "non-default argument follows default argument" error in the subclasses).
    """

    line: Optional[LogLine] = None

    @property
    def timestamp(self) -> Optional[str]:
        return self.line.timestamp if self.line else None


@dataclass
class ImapPerfOp(Breadcrumb):
    """``ImapPerf: <op> connect=<ms>ms work=<ms>ms live=<N>``."""

    op: str
    connect_ms: int
    work_ms: int
    live: int
    line: Optional[LogLine] = None


@dataclass
class BodyFetch(Breadcrumb):
    """``ImapPerf: body-fetch select=.. body=.. flag=.. rfc822=..B chars=.. att=..``."""

    select_ms: int
    body_ms: int
    flag_ms: int
    rfc822_bytes: int
    chars: int
    att: int
    line: Optional[LogLine] = None

    @property
    def body_kb_per_s(self) -> Optional[float]:
        """Body-download throughput in decimal KB/s; ``None`` if body_ms==0.

        ``bytes/ms`` is already KB/s (``bytes/ms * 1000 ms/s / 1000 B/KB``), so this mirrors
        the manual ``timing-tables.md`` "body KB/s" column exactly (e.g. 60457/14253 = 4.2).
        """
        if self.body_ms <= 0:
            return None
        return self.rfc822_bytes / self.body_ms


@dataclass
class OpenMessage(Breadcrumb):
    """``MailReader: openMessage <ref> folder=<label> fetchedBody=<bool> took=<ms>ms``."""

    account_ref: str
    folder: str
    fetched_body: bool
    took_ms: int
    line: Optional[LogLine] = None


@dataclass
class ReaderReady(Breadcrumb):
    """``Reader: reader ready took=<ms>ms html=<bool> inline=<n>``."""

    took_ms: int
    html: bool
    inline: int
    line: Optional[LogLine] = None


@dataclass
class BackfillProgress(Breadcrumb):
    """``MailBackfiller: backfill <ref> folder=<label> pages=<n> complete=<bool>``."""

    account_ref: str
    folder: str
    pages: int
    complete: bool
    line: Optional[LogLine] = None


@dataclass
class BackfillSliceStart(Breadcrumb):
    """``MailBackfiller: backfill slice: maxBatches=<n>``."""

    max_batches: int
    line: Optional[LogLine] = None


@dataclass
class BackfillSliceDone(Breadcrumb):
    """``MailBackfiller: backfill slice done: moreWork=<bool>``."""

    more_work: bool
    line: Optional[LogLine] = None


Event = Union[
    ImapPerfOp,
    BodyFetch,
    OpenMessage,
    ReaderReady,
    BackfillProgress,
    BackfillSliceStart,
    BackfillSliceDone,
]


# --------------------------------------------------------------------------- #
# Message-payload parsers (pure; operate on the trimmed message string)
# --------------------------------------------------------------------------- #
def _to_bool(text: str) -> bool:
    return text == "true"


_IMAP_OP_RE = re.compile(
    r"^(?P<op>\S+)\s+connect=(?P<connect>\d+)ms\s+work=(?P<work>\d+)ms\s+live=(?P<live>\d+)$"
)
_BODY_FETCH_RE = re.compile(
    r"^body-fetch\s+select=(?P<select>\d+)ms\s+body=(?P<body>\d+)ms\s+flag=(?P<flag>\d+)ms\s+"
    r"rfc822=(?P<bytes>\d+)B\s+chars=(?P<chars>\d+)\s+att=(?P<att>\d+)$"
)
_OPEN_MSG_RE = re.compile(
    r"^openMessage\s+(?P<ref>\S+)\s+folder=(?P<folder>\S+)\s+"
    r"fetchedBody=(?P<fetched>true|false)\s+took=(?P<took>\d+)ms$"
)
_READER_READY_RE = re.compile(
    r"^reader ready took=(?P<took>\d+)ms\s+html=(?P<html>true|false)\s+inline=(?P<inline>\d+)$"
)
_BACKFILL_PROGRESS_RE = re.compile(
    r"^backfill\s+(?P<ref>\S+)\s+folder=(?P<folder>\S+)\s+pages=(?P<pages>\d+)\s+"
    r"complete=(?P<complete>true|false)$"
)
_BACKFILL_SLICE_START_RE = re.compile(r"^backfill slice:\s+maxBatches=(?P<n>\d+)$")
_BACKFILL_SLICE_DONE_RE = re.compile(r"^backfill slice done:\s+moreWork=(?P<more>true|false)$")


def parse_imap_perf(message: str) -> Optional[Union[ImapPerfOp, BodyFetch]]:
    """Parse an ``ImapPerf`` message (either the detailed body-fetch or the generic op)."""
    m = _BODY_FETCH_RE.match(message)
    if m:
        return BodyFetch(
            select_ms=int(m.group("select")),
            body_ms=int(m.group("body")),
            flag_ms=int(m.group("flag")),
            rfc822_bytes=int(m.group("bytes")),
            chars=int(m.group("chars")),
            att=int(m.group("att")),
        )
    m = _IMAP_OP_RE.match(message)
    if m:
        return ImapPerfOp(
            op=m.group("op"),
            connect_ms=int(m.group("connect")),
            work_ms=int(m.group("work")),
            live=int(m.group("live")),
        )
    return None


def parse_open_message(message: str) -> Optional[OpenMessage]:
    """Parse a ``MailReader`` ``openMessage`` message."""
    m = _OPEN_MSG_RE.match(message)
    if not m:
        return None
    return OpenMessage(
        account_ref=m.group("ref"),
        folder=m.group("folder"),
        fetched_body=_to_bool(m.group("fetched")),
        took_ms=int(m.group("took")),
    )


def parse_reader_ready(message: str) -> Optional[ReaderReady]:
    """Parse a ``Reader`` ``reader ready`` message."""
    m = _READER_READY_RE.match(message)
    if not m:
        return None
    return ReaderReady(
        took_ms=int(m.group("took")),
        html=_to_bool(m.group("html")),
        inline=int(m.group("inline")),
    )


def parse_backfill(
    message: str,
) -> Optional[Union[BackfillProgress, BackfillSliceStart, BackfillSliceDone]]:
    """Parse any of the three ``MailBackfiller`` messages."""
    m = _BACKFILL_PROGRESS_RE.match(message)
    if m:
        return BackfillProgress(
            account_ref=m.group("ref"),
            folder=m.group("folder"),
            pages=int(m.group("pages")),
            complete=_to_bool(m.group("complete")),
        )
    m = _BACKFILL_SLICE_START_RE.match(message)
    if m:
        return BackfillSliceStart(max_batches=int(m.group("n")))
    m = _BACKFILL_SLICE_DONE_RE.match(message)
    if m:
        return BackfillSliceDone(more_work=_to_bool(m.group("more")))
    return None


# Dispatch table keyed by the trimmed logcat tag.
_TAG_PARSERS = {
    "ImapPerf": parse_imap_perf,
    "MailReader": parse_open_message,
    "Reader": parse_reader_ready,
    "MailBackfiller": parse_backfill,
}


def parse_breadcrumb(line: str) -> Optional[Event]:
    """Parse a full threadtime logcat line into a typed :data:`Event` (or ``None``).

    The returned event carries its source :class:`LogLine` on ``.line`` so callers can
    order and time-box events. Lines that are not perf breadcrumbs return ``None``.
    """
    log = parse_logcat_line(line)
    if log is None:
        return None
    parser = _TAG_PARSERS.get(log.tag)
    if parser is None:
        return None
    event = parser(log.message)
    if event is None:
        return None
    event.line = log
    return event


def iter_events(lines: Iterable[str]) -> Iterator[Event]:
    """Yield every parseable breadcrumb :data:`Event` from an iterable of logcat lines."""
    for line in lines:
        event = parse_breadcrumb(line)
        if event is not None:
            yield event


# --------------------------------------------------------------------------- #
# Correlation -- group the breadcrumbs of one message-open together
# --------------------------------------------------------------------------- #
@dataclass
class OpenSample:
    """One reader-open, correlating the breadcrumbs the app emits for a single open.

    Mirrors a row of the manual ``timing-tables.md``: the ``openMessage`` marker plus the
    ``body-fetch`` detail/op that preceded it and the ``reader ready`` that followed.
    """

    open_message: OpenMessage
    body_fetch: Optional[BodyFetch] = None
    body_fetch_op: Optional[ImapPerfOp] = None
    reader_ready: Optional[ReaderReady] = None

    @property
    def account_ref(self) -> str:
        return self.open_message.account_ref

    @property
    def cached(self) -> bool:
        """A cached open did not fetch the body over the network."""
        return not self.open_message.fetched_body

    @property
    def took_ms(self) -> int:
        return self.open_message.took_ms

    @property
    def rfc822_bytes(self) -> Optional[int]:
        return self.body_fetch.rfc822_bytes if self.body_fetch else None

    @property
    def body_kb_per_s(self) -> Optional[float]:
        return self.body_fetch.body_kb_per_s if self.body_fetch else None


def correlate_opens(events: Iterable[Event]) -> List[OpenSample]:
    """Group a flat, time-ordered event stream into :class:`OpenSample` records.

    Strategy (matches how the breadcrumbs interleave in a real capture): keep the most
    recent ``body-fetch`` detail and generic ``body-fetch`` op seen; when an
    ``openMessage`` marker arrives, attach those (consuming them so they are not reused);
    the first ``reader ready`` after the marker attaches to it.
    """
    samples: List[OpenSample] = []
    pending_detail: Optional[BodyFetch] = None
    pending_op: Optional[ImapPerfOp] = None
    open_awaiting_reader: Optional[OpenSample] = None

    for event in events:
        if isinstance(event, BodyFetch):
            pending_detail = event
        elif isinstance(event, ImapPerfOp) and event.op == "body-fetch":
            pending_op = event
        elif isinstance(event, OpenMessage):
            sample = OpenSample(open_message=event)
            if event.fetched_body:
                sample.body_fetch = pending_detail
                sample.body_fetch_op = pending_op
            pending_detail = None
            pending_op = None
            samples.append(sample)
            open_awaiting_reader = sample
        elif isinstance(event, ReaderReady):
            if open_awaiting_reader is not None and open_awaiting_reader.reader_ready is None:
                open_awaiting_reader.reader_ready = event
                open_awaiting_reader = None

    return samples
