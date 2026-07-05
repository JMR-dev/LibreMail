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

TWO MODES
---------
* ``--mode orchestrate`` (default; unchanged behaviour): the in-run `traffic-control`
  job of `ci.yml`. Orders runner ACCESS for the PR whose run is already executing —
  PASS 1 preemption + PASS 2 hold-back (below). This is `run_live`.
* ``--mode trigger`` (issue #349): the *scheduler* (companion `ci-trigger.yml`, run
  after each auto-update and on a cron backstop). It OWNS CI *triggering*: it
  (re-)triggers CI for the highest-priority PR(s) whose head SHA has absent/stale
  required checks — a few at a time (an inflight cap), in the SAME priority order —
  via a `workflow_dispatch`. This is `run_trigger` / the pure `select_triggers`.

WHY --mode trigger EXISTS (issue #349): `autoupdate.yml` now updates PR branches with the
built-in GITHUB_TOKEN instead of a PAT, so an update push no longer auto-retriggers CI
(GitHub's anti-recursion rule) — killing the merge-cascade that cancelled every open PR's
run on every merge. The cost is that a freshly-updated PR's required checks go stale/absent
on its NEW head SHA, so this scheduler deliberately (re-)triggers them in priority order (a
poor-man's merge queue). A `workflow_dispatch` is EXEMPT from the anti-recursion rule, so
even the GITHUB_TOKEN's dispatch DOES start the run — no PAT needed (the workflow grants its
token `actions: write`). FAIL-OPEN, structurally: `ci.yml` KEEPS its `on: pull_request`
trigger, so any human push — and a brand-new PR — always gets CI regardless of this
scheduler; the scheduler only fills the gap left by GITHUB_TOKEN auto-updates and can never
leave a PR un-triggerable. Fork PRs (no token/secret access) are skipped by the scheduler and
left to `on: pull_request`, so they are never wedged either.

ORDER OF OPERATIONS (issue #342)
--------------------------------
1. Effective priority orders everything: the lowest-numbered `P0`-`P9` label present
   (P0 = highest), default `P5` if none. A `broken` OR `draft` PR is effectively P10
   (bottom, below P9), overriding any P0-P9 label.
2. PASS 1 - preemption: a strictly-lower OTHER PR's in-progress / queued run is
   cancelled iff THIS PR is P0 (an emergency reclaims ALL lower runners) OR the target
   is broken/draft (a wasted run any higher-priority PR may reclaim). P1-P9 never bump
   a *normal* lower run mid-flight — only a P0 does that.
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
# Run conclusions that count as a FINAL VERDICT on a head SHA (issue #349, --mode trigger).
# A SHA with one of these is NOT re-triggered: success = green, failure/timeout/etc. = the
# author's to fix — auto-retriggering a real failure would waste runners and could loop.
# Everything else a completed run can report (cancelled / skipped / stale / startup_failure /
# null) is treated as "no verdict", so a SHA whose only runs are those — or that has no run at
# all (absent checks after a GITHUB_TOKEN auto-update) — is NEEDY and gets (re-)triggered.
VERDICT_CONCLUSIONS = frozenset(
    {"success", "failure", "timed_out", "action_required", "neutral"}
)


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
    """PASS 1. Run ids to cancel. A strictly-lower OTHER PR's active (running/queued)
    run is cancelled iff keeping it running is wasteful, i.e. EITHER:
      * THIS PR is P0 — an emergency reclaims every strictly-lower runner now; OR
      * the target is broken/draft (effective priority 10) — its run can't merge /
        isn't merge-ready, so ANY higher-priority PR may reclaim its runner.
    P1-P9 never cancel a *normal* strictly-lower run — they yield in PASS 2 instead.
    Invariants: never cancel self (by number or run id), never cancel an
    equal-or-higher-priority PR (only strictly-lower, prio > self)."""
    self_prio = effective_priority(this_pr)
    to_cancel: list[int] = []
    seen: set[int] = set()
    for pr in all_prs:
        if pr.number == this_pr.number:
            continue                                # never cancel self
        target_prio = effective_priority(pr)
        if target_prio <= self_prio:
            continue                                # only strictly-lower (skip equal-or-higher)
        if pr.run_status not in ACTIVE:
            continue                                # nothing running/queued to cancel
        # Strictly lower: preemptible iff we're P0 OR the target is broken/draft
        # (a bottom, priority-10, wasted run that any higher PR may reclaim).
        if self_prio != TOP_PRIORITY and target_prio < BOTTOM_PRIORITY:
            continue                                # P1-P9 don't bump a *normal* lower run
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


# ── Pure TRIGGER-decision core (issue #349, --mode trigger) ──────────────────
def classify_sha_runs(runs: list[dict]) -> tuple[str, tuple[int, ...], bool]:
    """PURE. Summarise the CI runs on ONE head SHA. Returns (run_status, active_run_ids,
    needy):
      * run_status: RUNNING if any run is in progress, else QUEUED if any is queued/pending,
        else NONE;
      * active_run_ids: databaseIds of the non-completed (running/queued) runs;
      * needy: True iff the SHA has NO active run AND NO run with a final VERDICT — i.e. its
        required checks are absent/stale (a fresh SHA after a GITHUB_TOKEN auto-update) or
        only cancelled/infra-aborted, so the PR cannot merge until CI is (re-)triggered on
        that SHA. A success/failure/timeout verdict is NOT needy (green, or the author's to
        fix — never auto-retried)."""
    status = NONE
    active_ids: list[int] = []
    has_verdict = False
    for r in runs:
        raw = (r.get("status") or "").lower()
        if raw == "completed":
            if (r.get("conclusion") or "").lower() in VERDICT_CONCLUSIONS:
                has_verdict = True
            continue
        norm = _normalise_status(raw)          # in_progress -> running; else queued
        if _STATUS_RANK[norm] < _STATUS_RANK[status]:
            status = norm
        rid = r.get("databaseId")
        if rid is not None:
            active_ids.append(int(rid))
    needy = not active_ids and not has_verdict
    return status, tuple(active_ids), needy


def _trigger_order_key(pr: PullRequest) -> tuple[int, str, int]:
    """Trigger ordering: highest priority first (lowest effective-priority number), then
    OLDEST createdAt first (the longest-waiting PR at a level goes first — the same-level
    fairness / anti-starvation rule), then PR number as a stable final tiebreak."""
    return (effective_priority(pr), pr.created_at or _FAR_FUTURE, pr.number)


@dataclass(frozen=True)
class TriggerDecision:
    """PURE output of `select_triggers`: which PR(s) the scheduler should (re-)trigger CI
    for right now, in order, plus any strictly-lower runs a P0 emergency preempts to free a
    runner. Exercised by `--mode trigger --dry-run` and the unit tests."""

    trigger_numbers: tuple[int, ...] = ()
    cancel_run_ids: tuple[int, ...] = ()
    inflight_numbers: tuple[int, ...] = ()
    needy_numbers: tuple[int, ...] = ()
    skipped_fork_numbers: tuple[int, ...] = ()
    slots: int = 0
    max_inflight: int = 0


def select_triggers(
    all_prs: list[PullRequest],
    needy: "set[int] | frozenset[int]",
    *,
    max_inflight: int,
    forks: "set[int] | frozenset[int]" = frozenset(),
) -> TriggerDecision:
    """PURE. Choose the PR(s) to (re-)trigger CI for now — a poor-man's merge queue over the
    existing priority model. No network / clock / subprocess, so it is exhaustively unit-
    tested (see TestSelectTriggers / TestTriggerStarvation).

    * inflight = PRs already running/queued on their head SHA — they occupy the cap.
    * candidates = NEEDY PRs (absent/stale checks on their head SHA) that are not already
      running and are not forks (forks have no token/secret access — see `run_trigger`).
    * order = effective priority, then oldest createdAt, then number (`_trigger_order_key`).
    * P0 = EMERGENCY: always triggered, BYPASSING the cap, and it PREEMPTS its strictly-lower
      OTHER runs (reusing `runs_to_cancel`) so a runner frees for it immediately.
    * P1–P10 fill only the remaining ``slots = max_inflight - len(inflight)``; the rest wait
      for a later pass.

    STARVATION is bounded, not by aging but structurally: triggering a PR gives its head SHA
    a run, so it LEAVES the needy set; between merges the needy set only shrinks, and the
    scheduler re-runs on every auto-update plus a cron backstop, so every eligible PR is
    triggered within a bounded number of passes (proved by TestTriggerStarvation). Ordering
    is still by priority, so higher-priority PRs are simply served first, never exclusively
    forever (a served PR stops being needy until its next push/auto-update)."""
    cap = max(1, max_inflight)
    inflight = [p for p in all_prs if p.run_status in ACTIVE]
    candidates = [
        p for p in all_prs
        if p.number in needy and p.run_status not in ACTIVE and p.number not in forks
    ]
    ordered = sorted(candidates, key=_trigger_order_key)
    emergencies = [p for p in ordered if effective_priority(p) == TOP_PRIORITY]
    normal = [p for p in ordered if effective_priority(p) != TOP_PRIORITY]
    slots = max(0, cap - len(inflight))
    chosen = emergencies + normal[:slots]      # P0 bypasses the cap; P1–P10 fill free slots

    cancel_ids: list[int] = []
    seen: set[int] = set()
    for emergency in emergencies:              # P0 preempts its strictly-lower active runs
        for rid in runs_to_cancel(emergency, all_prs):
            if rid not in seen:
                seen.add(rid)
                cancel_ids.append(rid)

    return TriggerDecision(
        trigger_numbers=tuple(p.number for p in chosen),
        cancel_run_ids=tuple(cancel_ids),
        inflight_numbers=tuple(sorted(p.number for p in inflight)),
        needy_numbers=tuple(p.number for p in ordered),
        skipped_fork_numbers=tuple(sorted(n for n in needy if n in forks)),
        slots=slots,
        max_inflight=cap,
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

    # ── PASS 1: PREEMPTION (P0 reclaims all lower; anyone reclaims broken/draft) ──
    to_cancel = runs_to_cancel(this_pr, all_prs, self_run_id=self_run_id)
    if not to_cancel:
        if self_prio == TOP_PRIORITY:
            _log("P0 emergency — no strictly-lower active runs to cancel.")
        else:
            _log("No preemptible runs (P1-P9 only reclaim broken/draft lower runs; "
                 "none active).")
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
    if dec.cancel_run_ids:
        why = ("P0 emergency (reclaims all strictly-lower)"
               if dec.self_priority == TOP_PRIORITY
               else "reclaiming broken/draft lower runs")
        _log(f"PASS 1 (preemption): {why} — cancel run ids: "
             f"{list(dec.cancel_run_ids)}")
    else:
        _log("PASS 1 (preemption): nothing to cancel.")
    if dec.proceed:
        _log("PASS 2 (hold-back): PROCEED — no blockers.")
    else:
        tags = ", ".join(f"#{b.number}(P{b.priority}, {b.kind})" for b in dec.blockers)
        _log(f"PASS 2 (hold-back): WAIT — yielding to: {tags}")
    return 0


# ── --mode trigger: the PAT-free scheduler shell (issue #349) ────────────────
def _gh_ok(args: list[str]) -> bool:
    """Run `gh <args>` for its side effect (no JSON parse). Returns True on exit 0; never
    raises — the scheduler fails open on any I/O error."""
    try:
        proc = subprocess.run(["gh", *args], capture_output=True, text=True, check=False)
    except (OSError, ValueError) as exc:
        _log(f"::warning::gh invocation failed ({' '.join(args[:2])}): {exc}")
        return False
    if proc.returncode != 0:
        _log(f"::warning::gh exited {proc.returncode} ({' '.join(args[:3])}): "
             f"{proc.stderr.strip()}")
        return False
    return True


def _ci_workflow_file() -> str:
    return os.environ.get("CI_WORKFLOW_FILE", "ci.yml")


def gather_trigger_snapshot() -> tuple[list[PullRequest], set[int], set[int], dict[int, dict]]:
    """Build (all_prs, needy, forks, meta) from live gh data for the trigger scheduler.
      * all_prs: PullRequest snapshots whose run_status / run_ids reflect the runs on each
        PR's CURRENT head SHA (so 'inflight' means a live run on the mergeable SHA, never a
        stale one on a superseded SHA);
      * needy: PR numbers whose head SHA has absent/stale checks (must be (re-)triggered);
      * forks: cross-repository PR numbers — no token/secret access, so NOT token-triggerable;
      * meta: number -> {headRefName, headRefOid} for the dispatch I/O.
    Returns empty structures (never raises) if gh can't be reached — the caller fails open."""
    prs = _gh_json([
        "pr", "list", "--state", "open", "--limit", "300",
        "--json", "number,headRefName,headRefOid,isCrossRepository,labels,isDraft,createdAt",
    ])
    if prs is None:
        return [], set(), set(), {}
    runs = _gh_json([
        "run", "list", "--workflow", _ci_workflow_file(), "--limit", "300",
        "--json", "databaseId,status,conclusion,headSha,headBranch,event",
    ]) or []
    by_sha: dict[str, list[dict]] = {}
    for row in runs:
        sha = row.get("headSha")
        if sha:
            by_sha.setdefault(sha, []).append(row)

    all_prs: list[PullRequest] = []
    needy: set[int] = set()
    forks: set[int] = set()
    meta: dict[int, dict] = {}
    for obj in prs:
        number = int(obj["number"])
        sha = obj.get("headRefOid") or ""
        status, run_ids, is_needy = classify_sha_runs(by_sha.get(sha, []))
        all_prs.append(PullRequest.from_json(
            {**obj, "runStatus": status, "runIds": list(run_ids)}))
        meta[number] = {
            "headRefName": obj.get("headRefName") or "",
            "headRefOid": sha,
        }
        if obj.get("isCrossRepository"):
            forks.add(number)
        if is_needy:
            needy.add(number)
    return all_prs, needy, forks, meta


def _dispatch_ci(pr_number: int, head_ref: str, head_sha: str) -> bool:
    """Trigger `ci.yml` for one PR via a `workflow_dispatch` on the PR's head branch. A
    workflow_dispatch is exempt from GitHub's anti-recursion rule, so even the built-in
    GITHUB_TOKEN's dispatch DOES start a run — no PAT required (the workflow grants its token
    `actions: write`). Running on the head branch puts the run's checks on the PR head SHA, so
    they satisfy branch protection's required checks."""
    if not head_ref:
        _log(f"::warning::PR #{pr_number} has no head branch — cannot dispatch; skipping.")
        return False
    ok = _gh_ok([
        "workflow", "run", _ci_workflow_file(), "--ref", head_ref,
        "-f", f"pr={pr_number}",
        "-f", f"head_sha={head_sha}",
        "-f", "reason=traffic-controller",
    ])
    if ok:
        short = head_sha[:8] if head_sha else "?"
        _log(f"    triggered CI for #{pr_number} on {head_ref} (head {short}).")
    return ok


def run_trigger() -> int:
    """The scheduler shell (companion `ci-trigger.yml`): pick the highest-priority needy
    PR(s) within the inflight cap and (re-)trigger their CI via workflow_dispatch; a P0
    emergency additionally preempts its strictly-lower runs. ALWAYS returns 0 — the scheduler
    must never wedge CI, and structurally it cannot: `ci.yml` keeps `on: pull_request`, so any
    human push (and a brand-new PR) still gets CI independently of this scheduler."""
    max_inflight = _positive_int("MAX_INFLIGHT_RUNS", 2)
    all_prs, needy, forks, meta = gather_trigger_snapshot()
    if not all_prs:
        _log("No open PRs (or could not list them) — nothing to trigger.")
        return 0

    dec = select_triggers(all_prs, needy, max_inflight=max_inflight, forks=forks)
    _log(f"Open PRs: {len(all_prs)} | needy (absent/stale checks): {list(dec.needy_numbers)} "
         f"| inflight: {list(dec.inflight_numbers)} | cap {dec.max_inflight}, "
         f"free slots {dec.slots}.")
    if dec.skipped_fork_numbers:
        _log(f"Fork PR(s) needing CI left to `on: pull_request` (no token access — not "
             f"wedged): {list(dec.skipped_fork_numbers)}.")

    for rid in dec.cancel_run_ids:                  # P0 emergency preemption
        if _cancel_run(rid):
            _log(f"    P0 preemption: cancelled lower run {rid} (freed its runner).")

    if not dec.trigger_numbers:
        _log("Nothing to trigger this pass (no needy PR fits a free slot).")
        return 0
    triggered = 0
    for number in dec.trigger_numbers:
        info = meta.get(number, {})
        if _dispatch_ci(number, info.get("headRefName", ""), info.get("headRefOid", "")):
            triggered += 1
    _log(f"Trigger pass complete — dispatched {triggered}/{len(dec.trigger_numbers)} "
         "run(s) in priority order.")
    return 0


def _load_trigger_snapshot(
    text: str,
) -> tuple[list[PullRequest], set[int], set[int], int]:
    data = json.loads(text)
    all_prs = [PullRequest.from_json(o) for o in data.get("prs", [])]
    needy = {int(n) for n in data.get("needy", [])}
    forks = {int(n) for n in data.get("forks", [])}
    max_inflight = int(data.get("max_inflight", 2))
    return all_prs, needy, forks, max_inflight


def run_trigger_dry(text: str) -> int:
    all_prs, needy, forks, max_inflight = _load_trigger_snapshot(text)
    dec = select_triggers(all_prs, needy, max_inflight=max_inflight, forks=forks)
    _log(f"Trigger decision (cap {dec.max_inflight}, free slots {dec.slots}):")
    _log(f"  inflight (occupying the cap): {list(dec.inflight_numbers)}")
    _log(f"  needy candidates (priority order): {list(dec.needy_numbers)}")
    if dec.skipped_fork_numbers:
        _log(f"  skipped forks (no token access): {list(dec.skipped_fork_numbers)}")
    if dec.cancel_run_ids:
        _log(f"  P0 preemption — cancel run ids: {list(dec.cancel_run_ids)}")
    _log(f"  => TRIGGER (in priority order): {list(dec.trigger_numbers)}")
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
        "--mode", choices=("orchestrate", "trigger"), default="orchestrate",
        help="orchestrate (default): in-run runner-priority for the executing PR "
             "(unchanged). trigger: the scheduler that (re-)triggers CI by priority "
             "(issue #349).")
    parser.add_argument(
        "--dry-run", action="store_true",
        help="read a snapshot JSON (from --input or stdin), print decisions, no network.")
    parser.add_argument(
        "--input", help="snapshot JSON file for --dry-run (default: stdin).")
    args = parser.parse_args(argv)

    if args.dry_run:
        text = (open(args.input, encoding="utf-8").read() if args.input
                else sys.stdin.read())
        return run_trigger_dry(text) if args.mode == "trigger" else run_dry(text)
    return run_trigger() if args.mode == "trigger" else run_live()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:                        # never let the controller fail CI
        _log(f"::warning::traffic_control crashed, proceeding fail-open: {exc}")
        sys.exit(0)
