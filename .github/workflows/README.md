<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# GitHub Actions workflows

This directory holds the repo's workflows:

- **`ci.yml`** — the pull-request gate: build, unit tests, static analysis (ktlint /
  detekt), and the E2E/instrumented-test matrix, aggregated into one `CI passed` check
  that branch protection requires. It also runs the `traffic-control` job described
  below.
- **`autoupdate.yml`** — rebases every open PR onto `main` whenever `main` advances, so
  the "branches up to date" branch rule never needs a manual update.
- **`release.yml`** — turns a pushed version tag into signed, published release
  artifacts; see [`docs/release.md`](../../docs/release.md).

The rest of this README is about **`traffic-control`** — the job (in the Checks tab it
shows up as **"Traffic control (runner priority)"**) that decides whose CI gets to run
first when several PRs are queued at once.

## Why this job exists

GitHub Actions has no concept of "run this PR's checks before that one" — every PR's
workflow run joins the same pool of runners and is served roughly first-come,
first-served. That's fine most of the time, but with several PRs open at once it means
an urgent one-line hotfix queues up as an equal to a routine refactor, and can end up
stuck waiting behind CI runs for changes that aren't in any hurry.

`traffic-control` addresses that by reading a **priority label** on the current PR,
comparing it against every other open PR, and then either freeing up a runner by
cancelling a lower-priority PR's run (**preemption**), or briefly waiting before this
PR's own heavy jobs start so a higher-priority PR's jobs get a head start
(**hold-back**). It runs first in every PR's CI: every other job in `ci.yml`
(`debug-build`, `unit-tests`, `static-analysis`, `e2e`, `e2e-preview`) declares
`needs: traffic-control`, so it always goes first —

```
PR's CI run starts
        │
        ▼
  traffic-control
        │  1. compute this PR's effective priority (see table below)
        │  2. PASS 1 — preemption: cancel strictly-lower-priority OTHER PRs' active
        │     runs, but only if we're P0, or the target PR is `broken`
        │  3. PASS 2 — hold-back: if we're not P0, wait (up to 180s) while any
        │     strictly-higher-priority OTHER PR still has an active run, then
        │     proceed regardless
        ▼
  debug-build · unit-tests · static-analysis · e2e · e2e-preview
```

## Effective priority

Priority comes from a label on the PR:

| Label | Effective priority | Meaning |
| --- | --- | --- |
| `P0` | 0 (highest) | **Emergency only** — production is broken, or an emergency security fix. |
| `P1` – `P9` | 1 – 9 | Higher number = lower priority. |
| *(no `P` label)* | 5 (default) | Normal priority — most PRs. |
| `broken` | 10 (lowest) | A stuck/failing PR, deprioritised below even `P9`. Overrides any `P0`–`P9` label also present. |

Apply at most one `P0`–`P9` label; if more than one is somehow present, the numerically
lowest (most urgent) one wins. The `broken` label is meant to be applied by a maintainer
to a PR whose CI is stuck or failing, as a "let everyone else go first while this gets
fixed" signal — not something a PR author sets on their own work. Removing it restores
whatever `P0`–`P9` priority (or the `P5` default) the PR would otherwise have.

## Preemption vs. holding back

### P0 preempts everyone lower

If *this* PR is `P0`, it's treated as an emergency: the job immediately cancels the
in-progress or queued CI runs of **every other open PR at a strictly lower priority**
(that is, anything that isn't also `P0`), freeing up their runners right away. A PR
that gets cancelled this way isn't harmed long-term — it simply reruns on its next push,
or the next time `autoupdate.yml` rebases it onto `main`. Because nothing outranks an
emergency, a `P0` PR also never does the hold-back wait described below.

### A `broken` PR can be preempted by anyone

A PR labelled `broken` can't merge while it's broken, so its CI run occupying a runner
is wasted capacity. Any PR that isn't itself `broken` — in other words, any PR with a
real `P0`–`P9` priority — outranks it and may cancel its active run to reclaim the
runner, not just a `P0` PR. `broken` is also the only priority level that yields to
*everything*: since it sits below every other level, it always waits for other PRs'
runs rather than the other way around.

### P1–P9 yield, but never cancel

Every other level (`P1`–`P9`, including the `P5` default) is cooperative rather than
aggressive: it never cancels a run that's already going, no matter how much lower that
run's priority is. Instead, before letting its own heavy jobs start, it checks whether
any **strictly higher**-priority PR currently has an active or queued CI run. If so, it
waits — polling every 15 seconds and re-checking the full list of open PRs each time, so
a newly opened higher-priority PR is picked up mid-wait too — giving that PR's jobs a
chance to reach the runner queue first. The wait is capped at **180 seconds**
(comfortably inside the job's 6-minute hard timeout); once the budget runs out, this PR
proceeds regardless. A PR should never be able to block itself indefinitely.

## Safety invariants

Whatever the priority math says, a few things are hard-coded to never happen:

- **Never touches `main` / push-triggered runs.** The job only acts on `pull_request`
  events, and every run it's even allowed to consider cancelling is filtered down to
  `event == pull_request` with `headBranch != main`.
- **Never cancels this PR's own run.** The current PR is excluded from the "other PRs"
  list up front by PR number, and the currently-executing run ID is skipped too, just in
  case.
- **Never cancels an equal-or-higher-priority run.** Only strictly-lower-priority PRs
  (a numerically larger, i.e. worse, priority) are ever candidates for cancellation.

## Honest limitation

This is a **best-effort head start, not a real priority queue.** GitHub Actions has no
API for "give this run's jobs priority over that run's jobs" — runners are handed out
roughly FIFO no matter what this job does. Hold-back approximates priority by making
lower-priority PRs wait a little before their jobs even enter that FIFO queue, but under
sustained contention (many PRs queuing at once) the bounded wait can run out before a
higher-priority PR's jobs have actually made it through the runner pool. The waiting job
itself is cheap and short-lived, which is exactly why the wait is capped rather than
open-ended — occasionally under-prioritizing is preferable to a job that ties up a
runner indefinitely just to wait.

## Not a merge gate

`traffic-control` is an optimizer, not a check your PR needs to pass. It's deliberately
left out of `ci-passed`'s `needs:` list, every GitHub API call it makes is guarded
against failure, the script always exits `0`, and the step itself runs with
`continue-on-error: true`. A hiccup here — a transient API error, a missing permission,
a fork PR without write access — can never fail or block your PR.

That said, the heavy jobs still order themselves after it via `needs: traffic-control`,
so if this job were ever skipped or failed outright, GitHub would mark those jobs
`skipped` — and `ci-passed` treats a required job coming back `skipped` as a gate
failure. So the worst case is fail-safe: it blocks the merge rather than letting an
untested PR through.

It also needs very little to run: no checkout step (it only calls the `gh` CLI), and
just two permissions (`actions: write` to cancel runs, `pull-requests: read` to read
labels). Values that come from outside the repo — labels, branch names — are only ever
read through `gh`'s JSON output into shell variables, never interpolated as shell code.

## Where this is heading

**#342** is rewriting this logic as a tested Python module
(`.github/scripts/traffic_control.py`), with a couple of small behavior refinements:
draft PRs will also sink to the bottom (like `broken`), and PRs at the exact same
priority level get an explicit order (whichever run is already in flight finishes
first; among the rest, whoever has been waiting longest goes next). This README
describes the shell-script version currently in `ci.yml` — see the comment block above
the `traffic-control` job there for the byte-for-byte spec — and will be updated once
#342 lands.
