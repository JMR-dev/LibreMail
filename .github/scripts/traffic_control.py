#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""CI traffic-controller: priority-based runner orchestration for LibreMail's
`ci.yml`. Extracted out of the old inline-bash `traffic-control` step into a
Python module so the decision logic is developer-legible and, above all, unit-
testable (see `test_traffic_control.py`).

DESIGN: pure decision CORE + thin gh-I/O SHELL
----------------------------------------------
The decisions ("who do we cancel?", "do we proceed or wait?") are pure functions
over plain `PullRequest` snapshots — no network, no clock, no subprocess — so they
can be exercised exhaustively in unit tests. The SHELL (`run_live`) is the only part
that touches `gh`: it gathers the snapshot, applies the cancellations, and runs the
bounded hold-back poll loop. Feed the core a snapshot JSON (`--dry-run`) to see its
decisions with zero network.

ORDER OF OPERATIONS (issue #342)
--------------------------------
1. Effective priority orders everything: the lowest-numbered `P0`-`P9` label present
   (P0 = highest), default `P5` if none. A `broken` OR `draft` PR is effectively P10
   (bottom, below P9), overriding any P0-P9 label.
2. PASS 1 - preemption: **only a P0 (emergency) preempts.** A P0 cancels the
   in-progress / queued CI runs of ALL strictly-lower OTHER open PRs to reclaim their
   runners. P1-P9 never bump a lower run mid-flight.
3. PASS 2 - bounded hold-back: a non-P0 PR yields (cancels nothing) to any strictly-
   higher-priority OTHER PR that has an active/queued run, and — among its OWN
   priority level — to any PR ordered ahead of it (running-first, then oldest by
   `createdAt`). It proceeds the moment it is at the front, or when the wait budget
   elapses (a PR never blocks itself).

SAFETY INVARIANTS (preserved from the original step)
----------------------------------------------------
* never cancel a run on `main` / a push event — the shell's `gh run list` query
  filters `--event pull_request` and drops `headBranch == main`, so only PR-event
  runs ever reach the core;
* never cancel THIS PR's own run — skipped by PR number AND by run id;
* never cancel an equal-or-higher-priority PR — only strictly-lower (prio > self).

This is deliberately NOT a merge gate: every gh call is guarded, the shell always
exits 0, and the ci.yml step stays `continue-on-error`, so a hiccup (API error,
missing permission, fork PR) can never fail CI.

Pure standard library, cross-platform (the primary dev box is Windows, where the old
bash + `jq` pipeline had no clean equivalent).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field

# ── Priority model ───────────────────────────────────────────────────────────
BROKEN_LABEL = "broken"
DEFAULT_PRIORITY = 5           # a PR with no P0-P9 label
BOTTOM_PRIORITY = 10           # broken OR draft — below P9
TOP_PRIORITY = 0               # P0, the only priority that preempts
_P_LABEL = re.compile(r"^P([0-9])$")   # single digit only, matching the old jq `^P[0-9]$`

# ── Run-status model (normalised from gh's raw run statuses) ─────────────────
RUNNING = "running"            # gh status in_progress
QUEUED = "queued"              # gh status queued / waiting / requested / pending
NONE = "none"                  # no active run (completed or absent)
ACTIVE = frozenset({RUNNING, QUEUED})
# For aggregating a branch's overall status from its runs: running beats queued
# beats none (most-active wins). NB: the same-level ORDER (see _ordering_key) is a
# coarser two-bucket split — in-flight (running) vs everything-else-by-age.
_STATUS_RANK = {RUNNING: 0, QUEUED: 1, NONE: 2}
# createdAt sentinel so a PR with an unknown timestamp sorts LAST (never wrongly
# "oldest"/front, so it yields rather than preempts another PR's front slot).
_FAR_FUTURE = "9999-12-31T23:59:59Z"


@dataclass(frozen=True)
class PullRequest:
    """A snapshot of one open PR. The pure decision core consumes only these — no
    network. `run_ids` are the PR's active (non-completed) CI run ids, already
    filtered to pull_request events on a non-main head by the shell that built them."""

    number: int
    labels: tuple[str, ...] = ()
    is_draft: bool = False
    created_at: str = ""
    run_status: str = NONE
    run_ids: tuple[int, ...] = ()

    @classmethod
    def from_json(cls, obj: dict) -> "PullRequest":
        """Build from a snapshot dict. `labels` may be a list of names or of gh's
        label objects (`{"name": ...}`)."""
        raw_labels = obj.get("labels") or []
        names: list[str] = []
        for lab in raw_labels:
            if isinstance(lab, dict):
                name = lab.get("name")
            else:
                name = lab
            if name:
                names.append(str(name))
        status = (obj.get("runStatus") or obj.get("run_status") or NONE).lower()
        if status not in (RUNNING, QUEUED, NONE):
            status = NONE
        raw_ids = obj.get("runIds") or obj.get("run_ids") or ()
        return cls(
            number=int(obj["number"]),
            labels=tuple(names),
            is_draft=bool(obj.get("isDraft") or obj.get("is_draft")
                          or obj.get("draft") or False),
            created_at=str(obj.get("createdAt") or obj.get("created_at") or ""),
            run_status=status,
            run_ids=tuple(int(r) for r in raw_ids),
        )


@dataclass(frozen=True)
class Blocker:
    """A PR that THIS PR must yield to in PASS 2 (purely informational for logging)."""

    number: int
    priority: int
    kind: str          # "higher-priority" | "same-level-ahead"


@dataclass(frozen=True)
class Decision:
    """The full point-in-time decision for THIS PR (used by --dry-run and tests)."""

    self_number: int
    self_priority: int
    cancel_run_ids: tuple[int, ...] = ()
    blockers: tuple[Blocker, ...] = ()
    proceed: bool = True


# ── Pure decision core (no network / clock / subprocess) ─────────────────────
def effective_priority(pr: PullRequest) -> int:
    """Effective priority: `broken` OR `draft` => 10 (bottom, overriding any P0-P9);
    else the lowest-numbered P0-P9 label present; else the default P5."""
    if pr.is_draft or BROKEN_LABEL in pr.labels:
        return BOTTOM_PRIORITY
    nums = [int(m.group(1)) for name in pr.labels if (m := _P_LABEL.match(name))]
    return min(nums) if nums else DEFAULT_PRIORITY


def priority_label(prio: int) -> str:
    """Human-readable priority for logs."""
    if prio >= BOTTOM_PRIORITY:
        return f"P{BOTTOM_PRIORITY} (broken/draft — bottom, below P9)"
    return f"P{prio}"


def _ordering_key(pr: PullRequest) -> tuple[int, str, int]:
    """Same-level ordering (issue #342 rule 3): an in-flight (RUNNING) run keeps its
    place at the front — a same-level peer never reorders it — then, among the PRs
    still waiting to start (QUEUED or no run yet), OLDEST createdAt first (ascending),
    then PR number as a stable final tiebreak so the order is fully deterministic."""
    in_flight = 0 if pr.run_status == RUNNING else 1
    return (in_flight, pr.created_at or _FAR_FUTURE, pr.number)


def runs_to_cancel(
    this_pr: PullRequest,
    all_prs: list[PullRequest],
    *,
    self_run_id: int | None = None,
) -> list[int]:
    """PASS 1. Run ids to cancel — **empty unless THIS PR is P0**. A P0 preempts the
    active (running/queued) runs of every strictly-lower OTHER PR. Invariants: never
    cancel self (by number or run id), never cancel an equal-or-higher-priority PR."""
    if effective_priority(this_pr) != TOP_PRIORITY:
        return []                                   # only P0 preempts; P1-P9 never bump
    to_cancel: list[int] = []
    seen: set[int] = set()
    for pr in all_prs:
        if pr.number == this_pr.number:
            continue                                # never cancel self
        if effective_priority(pr) <= TOP_PRIORITY:
            continue                                # only strictly-lower (skip other P0s)
        if pr.run_status not in ACTIVE:
            continue                                # nothing running/queued to cancel
        for rid in pr.run_ids:
            if self_run_id is not None and rid == self_run_id:
                continue                            # never cancel our own run
            if rid in seen:
                continue
            seen.add(rid)
            to_cancel.append(rid)
    return to_cancel


def wait_blockers(this_pr: PullRequest, all_prs: list[PullRequest]) -> list[Blocker]:
    """PASS 2. The PRs THIS PR must yield to right now (empty => proceed). P0 never
    yields. Otherwise yield to (a) any strictly-higher-priority OTHER PR with an
    active/queued run, and (b) any SAME-priority PR ordered ahead of THIS PR
    (running-first, then oldest createdAt)."""
    self_prio = effective_priority(this_pr)
    if self_prio == TOP_PRIORITY:
        return []                                   # P0 outranks everything — never wait

    others = [pr for pr in all_prs if pr.number != this_pr.number]
    blockers: list[Blocker] = []

    # (a) strictly-higher-priority PRs that actually have an active/queued run.
    for pr in others:
        p = effective_priority(pr)
        if p < self_prio and pr.run_status in ACTIVE:
            blockers.append(Blocker(pr.number, p, "higher-priority"))

    # (b) same-level ordering: THIS PR proceeds only when it is at the front.
    same_level = [pr for pr in others if effective_priority(pr) == self_prio]
    same_level.append(this_pr)                      # this_pr appears exactly once
    for pr in sorted(same_level, key=_ordering_key):
        if pr.number == this_pr.number:
            break                                   # reached self => nobody ahead remains
        blockers.append(Blocker(pr.number, self_prio, "same-level-ahead"))

    return blockers


def decide(
    this_pr: PullRequest,
    all_prs: list[PullRequest],
    *,
    self_run_id: int | None = None,
) -> Decision:
    """Convenience: the full point-in-time decision (both passes) for THIS PR."""
    cancels = runs_to_cancel(this_pr, all_prs, self_run_id=self_run_id)
    blockers = wait_blockers(this_pr, all_prs)
    return Decision(
        self_number=this_pr.number,
        self_priority=effective_priority(this_pr),
        cancel_run_ids=tuple(cancels),
        blockers=tuple(blockers),
        proceed=not blockers,
    )


# ── gh I/O shell (the only part that touches the network) ────────────────────
def _log(msg: str) -> None:
    print(msg, flush=True)


def _gh_json(args: list[str]) -> list | dict | None:
    """Run `gh <args> --json ...` and parse stdout as JSON. Returns None (never
    raises) on any failure — the caller fails open."""
    try:
        proc = subprocess.run(
            ["gh", *args],
            capture_output=True,
            text=True,
            check=False,
        )
    except (OSError, ValueError) as exc:
        _log(f"::warning::gh invocation failed ({' '.join(args[:2])}): {exc}")
        return None
    if proc.returncode != 0:
        _log(f"::warning::gh exited {proc.returncode} ({' '.join(args[:2])}): "
             f"{proc.stderr.strip()}")
        return None
    try:
        return json.loads(proc.stdout or "null")
    except json.JSONDecodeError as exc:
        _log(f"::warning::could not parse gh JSON ({' '.join(args[:2])}): {exc}")
        return None


def _normalise_status(raw: str) -> str:
    """Map a gh run status onto our RUNNING / QUEUED / NONE model."""
    if raw == "in_progress":
        return RUNNING
    if raw == "completed":
        return NONE
    return QUEUED                                   # queued / waiting / requested / pending


def _runs_by_head(limit: int = 300) -> dict[str, dict]:
    """One bulk `gh run list` -> {headBranch: {"status", "ids"}} for active PR-event
    runs. Enforces the 'never cancel main/push' invariant at the source: only
    `event == pull_request`, non-`main`, non-completed runs are kept. Active runs are
    the most recent, so `limit` most-recent runs comfortably covers them."""
    rows = _gh_json([
        "run", "list", "--workflow", "ci.yml", "--event", "pull_request",
        "--limit", str(limit),
        "--json", "databaseId,status,headBranch,event",
    ])
    by_head: dict[str, dict] = {}
    for row in rows or []:
        if row.get("event") != "pull_request":
            continue
        head = row.get("headBranch")
        if not head or head == "main":
            continue
        if row.get("status") == "completed":
            continue
        entry = by_head.setdefault(head, {"status": NONE, "ids": []})
        entry["ids"].append(int(row["databaseId"]))
        status = _normalise_status(row.get("status", ""))
        # running beats queued beats none for the branch's aggregate status.
        if _STATUS_RANK[status] < _STATUS_RANK[entry["status"]]:
            entry["status"] = status
    return by_head


def gather_snapshot(self_pr_number: int) -> tuple[PullRequest | None, list[PullRequest]]:
    """Build (this_pr, all_prs) from live gh data. this_pr is forced to RUNNING —
    by definition our own run is in progress while this job executes."""
    prs = _gh_json([
        "pr", "list", "--state", "open", "--limit", "300",
        "--json", "number,headRefName,labels,isDraft,createdAt",
    ])
    if prs is None:
        return None, []
    runs = _runs_by_head()
    all_prs: list[PullRequest] = []
    this_pr: PullRequest | None = None
    for obj in prs:
        head = obj.get("headRefName") or ""
        run_info = runs.get(head, {"status": NONE, "ids": []})
        number = int(obj["number"])
        is_self = number == self_pr_number
        pr = PullRequest.from_json({
            **obj,
            # self is definitionally running (this job is in progress).
            "runStatus": RUNNING if is_self else run_info["status"],
            "runIds": run_info["ids"],
        })
        all_prs.append(pr)
        if is_self:
            this_pr = pr
    return this_pr, all_prs


def _cancel_run(run_id: int) -> bool:
    try:
        proc = subprocess.run(
            ["gh", "run", "cancel", str(run_id)],
            capture_output=True, text=True, check=False,
        )
    except (OSError, ValueError) as exc:
        _log(f"::warning::could not cancel run {run_id}: {exc}")
        return False
    if proc.returncode == 0:
        return True
    _log(f"::warning::could not cancel run {run_id} — likely already finished. "
         f"{proc.stderr.strip()}")
    return False


def _positive_int(env_name: str, default: int) -> int:
    raw = os.environ.get(env_name, "")
    return int(raw) if raw.isdigit() and int(raw) > 0 else default


def run_live() -> int:
    """The gh-driven shell: gather, PASS 1 (cancel), PASS 2 (bounded hold-back). Always
    returns 0 — the traffic-controller must never fail CI."""
    event = os.environ.get("GITHUB_EVENT_NAME", "")
    self_raw = os.environ.get("SELF_PR", "")
    if event != "pull_request" or not self_raw.isdigit():
        _log("Not a pull_request event (or no PR number) — nothing to do.")
        return 0
    self_number = int(self_raw)
    self_run_id = int(os.environ["GITHUB_RUN_ID"]) if os.environ.get(
        "GITHUB_RUN_ID", "").isdigit() else None

    this_pr, all_prs = gather_snapshot(self_number)
    if this_pr is None:
        _log("::warning::Could not resolve THIS PR from the open-PR list — skipping.")
        return 0

    self_prio = effective_priority(this_pr)
    _log(f"This PR #{self_number} effective priority: {priority_label(self_prio)} "
         "(P0 = highest/emergency, P9 = lowest, broken/draft = bottom).")

    # ── PASS 1: PREEMPTION (only a P0 self) ──────────────────────────────────
    to_cancel = runs_to_cancel(this_pr, all_prs, self_run_id=self_run_id)
    if not to_cancel:
        if self_prio == TOP_PRIORITY:
            _log("P0 emergency — no strictly-lower active runs to cancel.")
        else:
            _log("Not P0 — no preemption (P1-P9 never cancel a lower run mid-flight).")
    else:
        cancelled = 0
        for rid in to_cancel:
            if _cancel_run(rid):
                _log(f"    cancelled run {rid} (freed its runner).")
                cancelled += 1
        _log(f"P0 preemption complete — cancelled {cancelled}/{len(to_cancel)} run(s).")

    # ── PASS 2: BOUNDED HOLD-BACK (yield to higher / same-level-ahead) ────────
    if self_prio == TOP_PRIORITY:
        _log("P0 emergency — not yielding; proceeding immediately.")
        return 0

    budget = _positive_int("HOLD_BACK_BUDGET_SECONDS", 180)
    poll = _positive_int("HOLD_BACK_POLL_SECONDS", 15)
    deadline = time.monotonic() + budget
    _log(f"{priority_label(self_prio)} — holding back up to {budget}s for higher / "
         "earlier same-level PRs (no cancellation).")

    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            _log("Hold-back budget elapsed — proceeding; higher-priority PRs got their "
                 "head start.")
            break
        # Refresh OTHER PRs so newly-opened higher-priority PRs are seen mid-wait;
        # THIS PR's own identity/priority stays fixed (matching the original).
        _, fresh = gather_snapshot(self_number)
        if not fresh:
            _log("::warning::Could not refresh open PRs — proceeding.")
            break
        blockers = wait_blockers(this_pr, fresh)
        if not blockers:
            _log("No higher-priority or earlier same-level PR is ahead — proceeding.")
            break
        tags = " ".join(f"#{b.number}(P{b.priority},{b.kind})" for b in blockers)
        sleep_s = min(poll, int(remaining)) if remaining >= 1 else 0
        _log(f"Yielding to: {tags} — re-checking in {sleep_s}s "
             f"({int(remaining)}s budget left).")
        if sleep_s > 0:
            time.sleep(sleep_s)

    _log("Hold-back complete — this PR's heavy jobs may now start.")
    return 0


# ── --dry-run: feed the pure core a snapshot JSON, print its decisions ───────
def _load_snapshot(text: str) -> tuple[PullRequest, list[PullRequest], int | None]:
    data = json.loads(text)
    all_prs = [PullRequest.from_json(o) for o in data.get("prs", [])]
    self_number = int(data["self"])
    self_run_id = data.get("self_run_id")
    self_run_id = int(self_run_id) if self_run_id is not None else None
    this_pr = next((p for p in all_prs if p.number == self_number), None)
    if this_pr is None:
        raise ValueError(f"self #{self_number} not present in prs[]")
    return this_pr, all_prs, self_run_id


def run_dry(text: str) -> int:
    this_pr, all_prs, self_run_id = _load_snapshot(text)
    dec = decide(this_pr, all_prs, self_run_id=self_run_id)
    _log(f"This PR #{dec.self_number} effective priority: "
         f"{priority_label(dec.self_priority)}")
    if dec.self_priority == TOP_PRIORITY:
        _log(f"PASS 1 (preemption): P0 — cancel run ids: "
             f"{list(dec.cancel_run_ids) or '(none active)'}")
    else:
        _log("PASS 1 (preemption): not P0 — no cancellations.")
    if dec.proceed:
        _log("PASS 2 (hold-back): PROCEED — no blockers.")
    else:
        tags = ", ".join(f"#{b.number}(P{b.priority}, {b.kind})" for b in dec.blockers)
        _log(f"PASS 2 (hold-back): WAIT — yielding to: {tags}")
    return 0


def main(argv: list[str] | None = None) -> int:
    # Emit UTF-8 regardless of the host console so the log typography is stable on
    # the UTF-8 CI runners (and never raises on a legacy Windows code page).
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dry-run", action="store_true",
        help="read a snapshot JSON (from --input or stdin), print decisions, no network.")
    parser.add_argument(
        "--input", help="snapshot JSON file for --dry-run (default: stdin).")
    args = parser.parse_args(argv)

    if args.dry_run:
        text = (open(args.input, encoding="utf-8").read() if args.input
                else sys.stdin.read())
        return run_dry(text)
    return run_live()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:                        # never let the controller fail CI
        _log(f"::warning::traffic_control crashed, proceeding fail-open: {exc}")
        sys.exit(0)
