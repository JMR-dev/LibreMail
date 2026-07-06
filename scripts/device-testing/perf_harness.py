#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""perf_harness.py -- LibreMail on-device performance-test harness (CLI entry point).

Replicates the manual on-device perf methodology (2026-07-05, Pixel 10 Pro XL) as a
repeatable, cross-platform, standard-library-only tool. Run one scenario at a time:

    python scripts/device-testing/perf_harness.py cold-open       [opts]
    python scripts/device-testing/perf_harness.py message-open    [opts]
    python scripts/device-testing/perf_harness.py back-nav        [opts]
    python scripts/device-testing/perf_harness.py prefetch-ab     [opts]
    python scripts/device-testing/perf_harness.py cross-provider  [opts]

Common options: ``--serial`` (auto-detected if exactly one device), ``--count/-n``,
``--out``, ``--package``, ``--component``, ``--adb``, and ``--dry-run`` (print the exact
command plan without touching device state -- use this to review a run before it happens).

Device safety is enforced by :mod:`adb` (allow-list of adb subcommands + deny-list on shell
commands; the only sanctioned mutation is clearing LibreMail's own ``cache/``). See the
README for the full guarantees and the required device state.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import os
import sys
import time
from typing import List, Optional

# Flat-layout imports: this file's directory is on sys.path[0] when run as a script, and the
# tests inject it explicitly. (The dir name contains a hyphen, so it is not an importable
# package -- hence flat modules rather than `python -m`.)
import breadcrumbs
import report
import scenarios
from adb import Adb, DEFAULT_COMPONENT, DEFAULT_PACKAGE

SCENARIOS = ("cold-open", "message-open", "back-nav", "prefetch-ab", "cross-provider")
_DEFAULT_COUNTS = {
    "cold-open": 5,
    "message-open": 6,
    "back-nav": 6,
    "prefetch-ab": 3,
    "cross-provider": 8,
}


def _make_logger(log_path: Optional[str]):
    handle = open(log_path, "a", encoding="utf-8") if log_path else None

    def log(msg: str) -> None:
        stamp = _dt.datetime.now().strftime("%H:%M:%S")
        line = f"[{stamp}] {msg}"
        print(line, flush=True)
        if handle:
            handle.write(line + "\n")
            handle.flush()

    return log, handle


def resolve_serial(adb_path: str, requested: Optional[str]) -> Optional[str]:
    """Return the serial to use, auto-detecting when exactly one device is attached."""
    probe = Adb(serial=None, adb_path=adb_path)
    serials = probe.devices()
    if requested:
        if serials and requested not in serials:
            print(
                f"warning: requested serial {requested!r} not in attached devices {serials}",
                file=sys.stderr,
            )
        return requested
    if len(serials) == 1:
        return serials[0]
    if not serials:
        raise SystemExit("no devices attached; connect one or pass --serial")
    raise SystemExit(f"multiple devices attached {serials}; pass --serial to choose one")


def _run_dir(out_root: str, scenario: str) -> str:
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = os.path.join(out_root, f"{stamp}-{scenario}")
    os.makedirs(path, exist_ok=True)
    return path


def _write_filtered_extract(raw_path: str, extract_path: str) -> int:
    """Write the ImapPerf/MailReader/Reader/MailBackfiller subset of the raw log; return count."""
    if not os.path.exists(raw_path):
        return 0
    count = 0
    with open(raw_path, "r", encoding="utf-8", errors="replace") as src, open(
        extract_path, "w", encoding="utf-8", newline="\n"
    ) as dst:
        for line in src:
            if breadcrumbs.is_perf_line(line):
                dst.write(line if line.endswith("\n") else line + "\n")
                count += 1
    return count


def _render_sections(scenario: str, results, adb: Adb, count: int) -> List[str]:
    if scenario == "cold-open":
        return [report.render_cold_open(results)]
    if scenario == "message-open":
        return [report.render_message_open("Message open (uncached)", results)]
    if scenario == "back-nav":
        return [report.render_back_nav(results)]
    if scenario == "prefetch-ab":
        return [
            report.render_message_open(
                "Condition A - Fetch all on Wi-Fi (prefetch ON)", results["conditionA"]
            ),
            report.render_message_open(
                "Condition B - Always on-demand (prefetch OFF)", results["conditionB"]
            ),
            _ab_comparison(results),
        ]
    if scenario == "cross-provider":
        sections = []
        for ref, rows in sorted(results.items()):
            sections.append(report.render_message_open(f"Provider {ref}", rows))
        if not sections:
            sections.append("## Cross-provider\n\nNo opens captured.\n")
        return sections
    return []


def _ab_comparison(results: dict) -> str:
    def median_took(rows):
        vals = [r.took_ms for r in rows if not r.skipped and not r.cached and r.took_ms]
        return report.aggregate(vals).median

    a = median_took(results["conditionA"])
    b = median_took(results["conditionB"])
    return (
        "## Prefetch A/B comparison\n\n"
        f"- Condition A (prefetch ON) median openMessage: {report._ms(a)} ms\n"
        f"- Condition B (prefetch OFF) median openMessage: {report._ms(b)} ms\n"
    )


def _run_scenario(scenario: str, adb: Adb, tailer, args, log):
    package, component, count = args.package, args.component, args.count
    if scenario == "cold-open":
        return scenarios.cold_open(adb, component, count, log)
    if scenario == "message-open":
        return scenarios.message_open(adb, package, tailer, count, log)
    if scenario == "back-nav":
        return scenarios.back_nav(adb, package, count, log)
    if scenario == "prefetch-ab":
        return scenarios.prefetch_ab(adb, package, component, tailer, count, log)
    if scenario == "cross-provider":
        return scenarios.cross_provider(adb, package, tailer, count, log)
    raise SystemExit(f"unknown scenario {scenario!r}")


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="perf_harness.py",
        description="LibreMail on-device performance-test harness (stdlib-only).",
    )
    p.add_argument("scenario", choices=SCENARIOS, help="which scenario to run")
    p.add_argument("--serial", help="device serial (auto-detected if exactly one attached)")
    p.add_argument("--package", default=DEFAULT_PACKAGE, help="target app package")
    p.add_argument("--component", default=DEFAULT_COMPONENT, help="launcher component")
    p.add_argument("--adb", default="adb", dest="adb_path", help="path to the adb executable")
    p.add_argument(
        "-n", "--count", type=int, default=None,
        help="samples/runs (per condition for prefetch-ab); scenario-specific default",
    )
    p.add_argument(
        "--out", default=os.path.join(os.getcwd(), "device-perf-runs"),
        help="output root; a timestamped subdir is created per run",
    )
    p.add_argument(
        "--dry-run", action="store_true",
        help="print the command plan without changing device state",
    )
    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_arg_parser().parse_args(argv)
    if args.count is None:
        args.count = _DEFAULT_COUNTS[args.scenario]

    serial = None if args.dry_run else resolve_serial(args.adb_path, args.serial)
    if args.dry_run and args.serial:
        serial = args.serial

    run_dir = _run_dir(args.out, args.scenario)
    driver_log_path = os.path.join(run_dir, "driver.log")
    log, log_handle = _make_logger(driver_log_path)
    raw_path = os.path.join(run_dir, "session-raw.log")

    log(f"scenario={args.scenario} serial={serial} count={args.count} dry_run={args.dry_run}")
    log(f"run dir: {run_dir}")

    adb = Adb(
        serial=serial,
        package=args.package,
        adb_path=args.adb_path,
        dry_run=args.dry_run,
        logger=log,
    )

    logcat_proc = None
    raw_handle = None
    tailer: scenarios.LogTailer = scenarios._NullTailer()
    try:
        if not args.dry_run:
            adb.clear_logcat()
            # Binary handle: the logcat child writes raw bytes to this fd.
            raw_handle = open(raw_path, "wb")
            logcat_proc = adb.start_logcat(raw_handle)
            tailer = scenarios.LogTailer(raw_path)
            scenarios.ensure_awake(adb, log)
            time.sleep(1.0)

        results = _run_scenario(args.scenario, adb, tailer, args, log)
        sections = _render_sections(args.scenario, results, adb, args.count)
    finally:
        Adb.stop_logcat(logcat_proc)
        if raw_handle:
            raw_handle.close()
        if not args.dry_run:
            adb.stay_on(False)  # restore default screen-timeout behaviour
        if log_handle:
            log_handle.flush()

    metadata = {
        "scenario": args.scenario,
        "timestamp": _dt.datetime.now().isoformat(timespec="seconds"),
        "serial": serial or "(dry-run)",
        "package": args.package,
        "count": args.count,
    }
    document = report.build_document(metadata, sections)
    tables_path = os.path.join(run_dir, "timing-tables.md")
    with open(tables_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(document)

    extract_count = 0
    if not args.dry_run:
        extract_count = _write_filtered_extract(
            raw_path, os.path.join(run_dir, "perf-extract.log")
        )
    log(f"wrote {tables_path} (filtered {extract_count} breadcrumb lines)")
    if log_handle:
        log_handle.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
