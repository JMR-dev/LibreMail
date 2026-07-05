#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Unit tests for the pure decision core of traffic_control.py (no network).

Covers: priority resolution (P-label / broken / draft / default P5), PASS 1
preemption (P0 reclaims all strictly-lower; ANY higher PR reclaims a broken/draft
lower run; P1-P9 never bump a *normal* lower run; self / main / equal-or-higher
never cancelled), PASS 2 hold-back (yield to strictly-higher with an active run;
same-level running-first then oldest-first), and a few end-to-end decision
scenarios.

Also covers the --mode trigger scheduler core (issue #349): head-SHA run
classification (absent/cancelled => needy; success/failure => not needy),
select_triggers (priority order, oldest-first fairness, inflight cap, fork skip,
P0 bypasses-cap-and-preempts), and a liveness/anti-starvation simulation proving
every eligible PR is triggered within a bounded number of passes."""

from __future__ import annotations

import json
import unittest

import traffic_control as tc
from traffic_control import PullRequest


def pr(number, labels=(), *, draft=False, created_at="", status=tc.NONE, run_ids=()):
    """Terse PullRequest builder for tests."""
    return PullRequest(
        number=number,
        labels=tuple(labels),
        is_draft=draft,
        created_at=created_at,
        run_status=status,
        run_ids=tuple(run_ids),
    )


class EffectivePriorityTests(unittest.TestCase):
    def test_no_labels_defaults_to_p5(self):
        self.assertEqual(tc.effective_priority(pr(1)), 5)

    def test_non_priority_labels_ignored_default_p5(self):
        self.assertEqual(tc.effective_priority(pr(1, ["bug", "enhancement"])), 5)

    def test_single_p_label(self):
        self.assertEqual(tc.effective_priority(pr(1, ["P3"])), 3)
        self.assertEqual(tc.effective_priority(pr(1, ["P0"])), 0)

    def test_lowest_numbered_p_label_wins(self):
        self.assertEqual(tc.effective_priority(pr(1, ["P4", "P1", "P7"])), 1)

    def test_broken_is_bottom_p10_overriding_p0(self):
        self.assertEqual(tc.effective_priority(pr(1, ["broken", "P0"])), 10)

    def test_draft_is_bottom_p10_overriding_p0(self):
        self.assertEqual(tc.effective_priority(pr(1, ["P0"], draft=True)), 10)

    def test_draft_and_broken_still_p10(self):
        self.assertEqual(tc.effective_priority(pr(1, ["broken"], draft=True)), 10)

    def test_double_digit_pseudo_label_is_not_a_priority(self):
        # Only P0-P9 count (regex ^P[0-9]$); "P10" is not a valid priority label.
        self.assertEqual(tc.effective_priority(pr(1, ["P10"])), 5)

    def test_priority_label_text(self):
        self.assertEqual(tc.priority_label(0), "P0")
        self.assertEqual(tc.priority_label(5), "P5")
        self.assertIn("bottom", tc.priority_label(10))


class RunsToCancelTests(unittest.TestCase):
    def test_non_p0_self_does_not_bump_normal_lower_run(self):
        # P1-P9 never preempt a *normal* strictly-lower run — they yield instead.
        me = pr(1, ["P1"])
        others = [pr(2, ["P5"], status=tc.RUNNING, run_ids=[200])]
        self.assertEqual(tc.runs_to_cancel(me, [me, *others]), [])

    def test_non_p0_self_reclaims_broken_lower_run(self):
        # Any higher-priority PR (not just P0) may reclaim a broken target's runner.
        me = pr(1, ["P3"])
        broken = pr(2, ["broken"], status=tc.RUNNING, run_ids=[200])
        self.assertEqual(tc.runs_to_cancel(me, [me, broken]), [200])

    def test_non_p0_self_reclaims_draft_lower_run(self):
        # A draft is not merge-ready — its run is likewise reclaimable by any higher PR.
        me = pr(1, ["P3"])
        draft = pr(2, [], draft=True, status=tc.QUEUED, run_ids=[200])
        self.assertEqual(tc.runs_to_cancel(me, [me, draft]), [200])

    def test_broken_self_does_not_cancel_equal_broken(self):
        # Both effective P10 — the equal-or-higher invariant still forbids cancelling.
        me = pr(1, ["broken"])
        peer = pr(2, ["broken"], status=tc.RUNNING, run_ids=[200])
        self.assertEqual(tc.runs_to_cancel(me, [me, peer]), [])

    def test_bottom_self_preempts_nothing(self):
        # A broken/draft PR (P10) is the bottom: nothing is strictly-lower, so it
        # cancels neither a higher (P5) nor an equal (P10) run.
        me = pr(1, [], draft=True)                              # P10
        prs = [
            me,
            pr(2, ["P5"], status=tc.RUNNING, run_ids=[200]),   # higher
            pr(3, ["broken"], status=tc.RUNNING, run_ids=[300]),  # equal P10
        ]
        self.assertEqual(tc.runs_to_cancel(me, prs), [])

    def test_p0_cancels_strictly_lower_active_runs(self):
        me = pr(1, ["P0"])
        low = pr(2, ["P5"], status=tc.RUNNING, run_ids=[200])
        queued = pr(3, ["P9"], status=tc.QUEUED, run_ids=[300])
        self.assertEqual(
            sorted(tc.runs_to_cancel(me, [me, low, queued])), [200, 300])

    def test_p0_cancels_broken_and_draft_lower_runs(self):
        me = pr(1, ["P0"])
        broken = pr(2, ["broken"], status=tc.RUNNING, run_ids=[200])
        draft = pr(3, ["P2"], draft=True, status=tc.RUNNING, run_ids=[300])
        self.assertEqual(
            sorted(tc.runs_to_cancel(me, [me, broken, draft])), [200, 300])

    def test_p0_never_cancels_equal_priority_p0(self):
        me = pr(1, ["P0"])
        peer = pr(2, ["P0"], status=tc.RUNNING, run_ids=[200])
        self.assertEqual(tc.runs_to_cancel(me, [me, peer]), [])

    def test_p0_never_cancels_self(self):
        me = pr(1, ["P0"], status=tc.RUNNING, run_ids=[100])
        self.assertEqual(tc.runs_to_cancel(me, [me]), [])

    def test_p0_excludes_own_run_id_defensively(self):
        me = pr(1, ["P0"], status=tc.RUNNING, run_ids=[100])
        # A lower PR that somehow reports our own run id must not be cancelled.
        low = pr(2, ["P5"], status=tc.RUNNING, run_ids=[100, 200])
        self.assertEqual(
            tc.runs_to_cancel(me, [me, low], self_run_id=100), [200])

    def test_p0_skips_lower_with_no_active_run(self):
        me = pr(1, ["P0"])
        idle = pr(2, ["P5"], status=tc.NONE, run_ids=[])
        self.assertEqual(tc.runs_to_cancel(me, [me, idle]), [])


class WaitBlockersTests(unittest.TestCase):
    def test_p0_never_waits(self):
        me = pr(1, ["P0"])
        higher = pr(2, ["P0"], status=tc.RUNNING)  # nothing outranks P0 anyway
        self.assertEqual(tc.wait_blockers(me, [me, higher]), [])

    def test_yields_to_strictly_higher_with_active_run(self):
        me = pr(2, ["P5"], status=tc.RUNNING)
        higher = pr(1, ["P2"], status=tc.RUNNING)
        blockers = tc.wait_blockers(me, [me, higher])
        self.assertEqual([b.number for b in blockers], [1])
        self.assertEqual(blockers[0].kind, "higher-priority")

    def test_does_not_yield_to_higher_without_active_run(self):
        me = pr(2, ["P5"], status=tc.RUNNING)
        higher_idle = pr(1, ["P2"], status=tc.NONE)
        self.assertEqual(tc.wait_blockers(me, [me, higher_idle]), [])

    def test_does_not_yield_to_lower_priority(self):
        me = pr(1, ["P2"], status=tc.RUNNING)
        lower = pr(2, ["P5"], status=tc.RUNNING)
        self.assertEqual(tc.wait_blockers(me, [me, lower]), [])

    def test_same_level_oldest_running_proceeds(self):
        me = pr(1, ["P5"], created_at="2026-07-01T00:00:00Z", status=tc.RUNNING)
        newer = pr(2, ["P5"], created_at="2026-07-02T00:00:00Z", status=tc.RUNNING)
        self.assertEqual(tc.wait_blockers(me, [me, newer]), [])

    def test_same_level_newer_running_yields_to_older(self):
        # Coordinator clarification: within a level, older createdAt goes first.
        older = pr(1, ["P5"], created_at="2026-07-01T00:00:00Z", status=tc.RUNNING)
        me = pr(2, ["P5"], created_at="2026-07-02T00:00:00Z", status=tc.RUNNING)
        blockers = tc.wait_blockers(me, [me, older])
        self.assertEqual([b.number for b in blockers], [1])
        self.assertEqual(blockers[0].kind, "same-level-ahead")

    def test_same_level_running_first_beats_older_waiting(self):
        # An in-flight peer keeps its place; a not-yet-running OLDER peer does not
        # jump ahead of us while we are the one already running.
        me = pr(2, ["P5"], created_at="2026-07-02T00:00:00Z", status=tc.RUNNING)
        older_waiting = pr(1, ["P5"], created_at="2026-07-01T00:00:00Z", status=tc.NONE)
        self.assertEqual(tc.wait_blockers(me, [me, older_waiting]), [])

    def test_same_level_waiting_orders_oldest_before_newer(self):
        # Neither running: strictly oldest-first among the waiting bucket.
        oldest = pr(1, ["P5"], created_at="2026-07-01T00:00:00Z", status=tc.NONE)
        middle = pr(2, ["P5"], created_at="2026-07-02T00:00:00Z", status=tc.NONE)
        me = pr(3, ["P5"], created_at="2026-07-03T00:00:00Z", status=tc.NONE)
        blockers = tc.wait_blockers(me, [oldest, middle, me])
        self.assertEqual([b.number for b in blockers], [1, 2])

    def test_same_level_queued_counts_as_waiting_ordered_by_age(self):
        # A queued peer is "waiting to start", not in-flight: ordered purely by age.
        me = pr(1, ["P5"], created_at="2026-07-01T00:00:00Z", status=tc.NONE)
        newer_queued = pr(2, ["P5"], created_at="2026-07-02T00:00:00Z", status=tc.QUEUED)
        self.assertEqual(tc.wait_blockers(me, [me, newer_queued]), [])

    def test_broken_self_yields_to_everyone_active(self):
        me = pr(1, ["broken"], status=tc.RUNNING)          # effective P10
        normal = pr(2, ["P5"], status=tc.RUNNING)
        blockers = tc.wait_blockers(me, [me, normal])
        self.assertEqual([b.number for b in blockers], [2])
        self.assertEqual(blockers[0].kind, "higher-priority")


class EndToEndDecisionTests(unittest.TestCase):
    def test_p0_emergency_cancels_lower_and_proceeds(self):
        me = pr(10, ["P0"], status=tc.RUNNING, run_ids=[1000])
        prs = [
            me,
            pr(11, ["P2"], status=tc.RUNNING, run_ids=[1100]),
            pr(12, ["P5"], status=tc.QUEUED, run_ids=[1200]),
            pr(13, ["P0"], status=tc.RUNNING, run_ids=[1300]),  # equal — spared
        ]
        dec = tc.decide(me, prs, self_run_id=1000)
        self.assertEqual(sorted(dec.cancel_run_ids), [1100, 1200])
        self.assertTrue(dec.proceed)

    def test_p5_waits_behind_running_higher(self):
        me = pr(20, ["P5"], status=tc.RUNNING, run_ids=[2000])
        higher = pr(21, ["P2"], status=tc.RUNNING, run_ids=[2100])
        dec = tc.decide(me, [me, higher])
        self.assertEqual(dec.cancel_run_ids, ())     # not P0 — cancels nothing
        self.assertFalse(dec.proceed)
        self.assertEqual([b.number for b in dec.blockers], [21])

    def test_lone_p5_proceeds(self):
        me = pr(30, ["P5"], status=tc.RUNNING, run_ids=[3000])
        dec = tc.decide(me, [me])
        self.assertEqual(dec.cancel_run_ids, ())
        self.assertTrue(dec.proceed)

    def test_p5_reclaims_draft_then_waits_behind_higher(self):
        # A non-P0 PR can BOTH reclaim a broken/draft lower run (PASS 1) AND still
        # yield to a strictly-higher PR (PASS 2) in the same evaluation.
        me = pr(40, ["P5"], status=tc.RUNNING, run_ids=[4000])
        prs = [
            me,
            pr(41, ["P2"], status=tc.RUNNING, run_ids=[4100]),        # higher — blocks
            pr(42, [], draft=True, status=tc.RUNNING, run_ids=[4200]),  # draft — reclaimed
        ]
        dec = tc.decide(me, prs)
        self.assertEqual(list(dec.cancel_run_ids), [4200])
        self.assertFalse(dec.proceed)
        self.assertEqual([b.number for b in dec.blockers], [41])


class SnapshotParsingTests(unittest.TestCase):
    def test_from_json_label_objects_and_fields(self):
        obj = {
            "number": 7,
            "labels": [{"name": "P3"}, {"name": "bug"}],
            "isDraft": True,
            "createdAt": "2026-07-01T00:00:00Z",
            "runStatus": "running",
            "runIds": [42, 43],
        }
        p = PullRequest.from_json(obj)
        self.assertEqual(p.number, 7)
        self.assertEqual(p.labels, ("P3", "bug"))
        self.assertTrue(p.is_draft)
        self.assertEqual(p.run_status, tc.RUNNING)
        self.assertEqual(p.run_ids, (42, 43))
        self.assertEqual(tc.effective_priority(p), 10)   # draft => bottom

    def test_from_json_plain_string_labels_and_unknown_status(self):
        p = PullRequest.from_json(
            {"number": 8, "labels": ["P1"], "runStatus": "bogus"})
        self.assertEqual(p.labels, ("P1",))
        self.assertEqual(p.run_status, tc.NONE)          # unknown -> none

    def test_load_snapshot_roundtrip(self):
        text = json.dumps({
            "self": 2,
            "self_run_id": 222,
            "prs": [
                {"number": 1, "labels": ["P2"], "runStatus": "running",
                 "runIds": [111]},
                {"number": 2, "labels": ["P5"], "runStatus": "running",
                 "runIds": [222]},
            ],
        })
        this_pr, all_prs, self_run_id = tc._load_snapshot(text)
        self.assertEqual(this_pr.number, 2)
        self.assertEqual(len(all_prs), 2)
        self.assertEqual(self_run_id, 222)


# ── --mode trigger scheduler core (issue #349) ───────────────────────────────
def npr(number, labels=("P5",), *, draft=False, created_at="", status=tc.NONE, run_ids=()):
    """Terse builder defaulting to a P5 PR (for the trigger tests)."""
    return pr(number, labels, draft=draft, created_at=created_at, status=status,
              run_ids=run_ids)


class ClassifyShaRunsTests(unittest.TestCase):
    def test_no_runs_is_needy(self):
        status, ids, needy = tc.classify_sha_runs([])
        self.assertEqual(status, tc.NONE)
        self.assertEqual(ids, ())
        self.assertTrue(needy)                     # absent checks => must be triggered

    def test_success_verdict_not_needy(self):
        _, ids, needy = tc.classify_sha_runs(
            [{"status": "completed", "conclusion": "success", "databaseId": 1}])
        self.assertEqual(ids, ())
        self.assertFalse(needy)

    def test_failure_verdict_not_needy(self):
        # A real failure is the author's to fix — never auto-retriggered (no fail loop).
        _, _, needy = tc.classify_sha_runs(
            [{"status": "completed", "conclusion": "failure", "databaseId": 1}])
        self.assertFalse(needy)

    def test_only_cancelled_is_needy(self):
        # Cancelled leaves no verdict → re-trigger so the PR can reach a mergeable state.
        _, ids, needy = tc.classify_sha_runs(
            [{"status": "completed", "conclusion": "cancelled", "databaseId": 1}])
        self.assertEqual(ids, ())
        self.assertTrue(needy)

    def test_in_progress_is_active_not_needy(self):
        status, ids, needy = tc.classify_sha_runs(
            [{"status": "in_progress", "conclusion": None, "databaseId": 9}])
        self.assertEqual(status, tc.RUNNING)
        self.assertEqual(ids, (9,))
        self.assertFalse(needy)

    def test_queued_is_active_not_needy(self):
        status, ids, needy = tc.classify_sha_runs(
            [{"status": "queued", "conclusion": None, "databaseId": 8}])
        self.assertEqual(status, tc.QUEUED)
        self.assertEqual(ids, (8,))
        self.assertFalse(needy)

    def test_running_beats_queued_in_status(self):
        status, ids, _ = tc.classify_sha_runs([
            {"status": "queued", "databaseId": 1},
            {"status": "in_progress", "databaseId": 2},
        ])
        self.assertEqual(status, tc.RUNNING)
        self.assertEqual(sorted(ids), [1, 2])

    def test_cancelled_plus_active_not_needy(self):
        # An active run already covers the SHA — cancelled siblings don't make it needy.
        _, ids, needy = tc.classify_sha_runs([
            {"status": "completed", "conclusion": "cancelled", "databaseId": 1},
            {"status": "in_progress", "databaseId": 2},
        ])
        self.assertEqual(ids, (2,))
        self.assertFalse(needy)


class SelectTriggersTests(unittest.TestCase):
    def test_empty_needy_triggers_nothing(self):
        dec = tc.select_triggers([npr(1), npr(2)], set(), max_inflight=3)
        self.assertEqual(dec.trigger_numbers, ())

    def test_single_needy_triggered(self):
        dec = tc.select_triggers([npr(1)], {1}, max_inflight=3)
        self.assertEqual(dec.trigger_numbers, (1,))
        self.assertEqual(dec.cancel_run_ids, ())

    def test_priority_order(self):
        prs = [npr(1, ["P5"]), npr(2, ["P2"]), npr(3, ["P8"])]
        dec = tc.select_triggers(prs, {1, 2, 3}, max_inflight=3)
        self.assertEqual(dec.trigger_numbers, (2, 1, 3))      # P2, P5, P8

    def test_same_level_oldest_first(self):
        prs = [
            npr(1, ["P5"], created_at="2026-07-03T00:00:00Z"),
            npr(2, ["P5"], created_at="2026-07-01T00:00:00Z"),
            npr(3, ["P5"], created_at="2026-07-02T00:00:00Z"),
        ]
        dec = tc.select_triggers(prs, {1, 2, 3}, max_inflight=3)
        self.assertEqual(dec.trigger_numbers, (2, 3, 1))      # oldest createdAt first

    def test_cap_limits_triggers(self):
        prs = [npr(1, ["P2"]), npr(2, ["P3"]), npr(3, ["P4"])]
        dec = tc.select_triggers(prs, {1, 2, 3}, max_inflight=2)
        self.assertEqual(dec.trigger_numbers, (1, 2))         # only 2 free slots
        self.assertEqual(dec.slots, 2)

    def test_inflight_consumes_slots(self):
        prs = [
            npr(1, ["P5"], status=tc.RUNNING, run_ids=[100]),  # inflight — occupies a slot
            npr(2, ["P2"]),
            npr(3, ["P3"]),
        ]
        dec = tc.select_triggers(prs, {2, 3}, max_inflight=2)
        self.assertEqual(dec.slots, 1)                        # 2 cap - 1 inflight
        self.assertEqual(dec.trigger_numbers, (2,))           # highest-priority needy only
        self.assertEqual(dec.inflight_numbers, (1,))

    def test_running_needy_is_not_retriggered(self):
        # Defensive: a PR flagged needy but already running is never a candidate.
        prs = [npr(1, ["P5"], status=tc.RUNNING, run_ids=[100])]
        dec = tc.select_triggers(prs, {1}, max_inflight=3)
        self.assertEqual(dec.trigger_numbers, ())

    def test_fork_pr_skipped(self):
        dec = tc.select_triggers([npr(1, ["P2"]), npr(2, ["P1"])],
                                 {1, 2}, max_inflight=3, forks={2})
        self.assertEqual(dec.trigger_numbers, (1,))           # fork #2 not token-triggerable
        self.assertEqual(dec.skipped_fork_numbers, (2,))

    def test_p0_bypasses_cap_and_preempts_lower(self):
        # Cap full (a P5 running), but a needy P0 still triggers AND preempts the strictly-
        # lower running run to free a runner immediately.
        prs = [
            npr(1, ["P5"], status=tc.RUNNING, run_ids=[500]),  # inflight, strictly-lower
            npr(2, ["P0"]),                                     # needy emergency
        ]
        dec = tc.select_triggers(prs, {2}, max_inflight=1)
        self.assertEqual(dec.slots, 0)                        # cap is full
        self.assertEqual(dec.trigger_numbers, (2,))           # P0 bypasses the cap
        self.assertEqual(dec.cancel_run_ids, (500,))          # preempts the lower run

    def test_p0_does_not_preempt_equal_priority(self):
        prs = [
            npr(1, ["P0"], status=tc.RUNNING, run_ids=[500]),  # equal P0 — spared
            npr(2, ["P0"]),                                     # needy emergency
        ]
        dec = tc.select_triggers(prs, {2}, max_inflight=1)
        self.assertEqual(dec.trigger_numbers, (2,))
        self.assertEqual(dec.cancel_run_ids, ())              # never preempts an equal P0

    def test_needy_numbers_reports_all_candidates_in_order(self):
        dec = tc.select_triggers([npr(1, ["P5"]), npr(2, ["P2"])], {1, 2}, max_inflight=1)
        self.assertEqual(dec.needy_numbers, (2, 1))           # priority order, cap-independent
        self.assertEqual(dec.trigger_numbers, (2,))           # but only 1 slot triggered

    def test_zero_cap_is_clamped_to_one(self):
        # A misconfigured cap must never stall everything: clamp to >= 1 so at least the
        # top-priority needy PR still gets a slot (fail-safe forward progress).
        dec = tc.select_triggers([npr(1, ["P5"])], {1}, max_inflight=0)
        self.assertEqual(dec.max_inflight, 1)
        self.assertEqual(dec.trigger_numbers, (1,))


class TriggerStarvationTests(unittest.TestCase):
    def test_every_needy_pr_is_triggered_within_bounded_passes(self):
        # Liveness / anti-starvation: with a fixed needy set and cap=2, simulate scheduler
        # passes where a triggered PR gains a run (leaves the needy set) and its run finishes
        # one pass later (freeing its slot). Mixed priorities prove lower-priority PRs are
        # served too — never starved — while higher-priority PRs still go first.
        labels = {1: ["P1"], 2: ["P1"], 3: ["P5"], 4: ["P5"],
                  5: ["P8"], 6: ["P8"], 7: ["P5"]}
        created = {n: f"2026-07-{n:02d}T00:00:00Z" for n in labels}
        needy = set(labels)
        running: dict[int, int] = {}                # number -> passes left running
        triggered_ever: set[int] = set()
        first_pass: dict[int, int] = {}
        cap, max_passes = 2, 12
        for pass_no in range(1, max_passes + 1):
            snap = [
                pr(n, labels[n], created_at=created[n],
                   status=tc.RUNNING if n in running else tc.NONE,
                   run_ids=[1000 + n] if n in running else [])
                for n in labels
            ]
            dec = tc.select_triggers(snap, needy, max_inflight=cap)
            for n in dec.trigger_numbers:
                triggered_ever.add(n)
                first_pass.setdefault(n, pass_no)
                needy.discard(n)                    # gained a run -> no longer needy
                running[n] = 1                      # occupies a slot for one pass
            for n in list(running):                 # running PRs finish after one pass
                running[n] -= 1
                if running[n] <= 0:
                    del running[n]
            if not needy and not running:
                break
        self.assertEqual(triggered_ever, set(labels),
                         "a PR was starved (never triggered)")
        # Priority respected: the two P1s go in the very first pass; everything else later.
        self.assertTrue(all(first_pass[n] == 1 for n in (1, 2)))
        self.assertTrue(all(first_pass[n] >= 2 for n in (3, 4, 5, 6, 7)))


class TriggerSnapshotParsingTests(unittest.TestCase):
    def test_load_trigger_snapshot_roundtrip(self):
        text = json.dumps({
            "max_inflight": 2,
            "needy": [1, 3],
            "forks": [3],
            "prs": [
                {"number": 1, "labels": ["P2"], "createdAt": "2026-07-01T00:00:00Z"},
                {"number": 2, "labels": ["P5"], "runStatus": "running", "runIds": [22]},
                {"number": 3, "labels": ["P1"], "createdAt": "2026-07-02T00:00:00Z"},
            ],
        })
        all_prs, needy, forks, cap = tc._load_trigger_snapshot(text)
        self.assertEqual(len(all_prs), 3)
        self.assertEqual(needy, {1, 3})
        self.assertEqual(forks, {3})
        self.assertEqual(cap, 2)
        dec = tc.select_triggers(all_prs, needy, max_inflight=cap, forks=forks)
        # #2 is inflight (uses a slot); #3 is a fork (skipped); only #1 fits the 1 free slot.
        self.assertEqual(dec.inflight_numbers, (2,))
        self.assertEqual(dec.skipped_fork_numbers, (3,))
        self.assertEqual(dec.trigger_numbers, (1,))


if __name__ == "__main__":
    unittest.main()
