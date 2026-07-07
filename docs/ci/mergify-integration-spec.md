<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Mergify integration spec (proposal for issue #407)

> **Status: PROPOSAL — NOTHING IS ACTIVE.** This document and its companion
> `docs/ci/mergify.yml.proposed` are a review artifact. No Mergify app is installed and
> **no live `.mergify.yml` exists** in the repo, so Mergify does nothing until a maintainer
> explicitly adopts it. This spec exists to be reviewed *before* adoption. It must not weaken
> the `require branches to be up to date` invariant — see [The require-up-to-date trilemma](#the-require-up-to-date-trilemma),
> which is the central decision this proposal surfaces.

## TL;DR / recommendation

- **Mergify is free for open source** (public repos, unlimited contributors) and the free
  "Open Source" plan includes the full **Merge Queue + batching + speculative (parallel) checks**.
- Adopting it lets us **retire the entire hand-rolled traffic-controller** (`autoupdate.yml`
  + `ci-trigger.yml` + the mothballed `traffic-control.yml`) and the manual serial-bump grind,
  replacing a "poor-man's merge queue" with a real one.
- **Recommendation: adopt Mergify, in two phases.**
  - **Phase 1 (adopt now, zero impact on the hard rule):** a **serial** queue
    (`batch_size: 1`, `merge_method: merge`). Mergify respects branch protection, keeps
    `require branches up to date` **literally ON**, does the up-to-date-ing + merge itself.
    This alone kills the manual grind. No throughput multiplier yet.
  - **Phase 2 (the batching throughput win — needs one conscious maintainer decision):**
    batch N test-only PRs into **one** CI run. Batching is **incompatible with GitHub's
    literal `require branches up to date` checkbox**; to get it, the guarantee must move
    *into* Mergify's queue (checkbox off, invariant preserved/strengthened by speculative
    testing) — or history must go linear via `fast-forward` (which breaks the merge-commit
    policy). **This is a trilemma: `require-up-to-date` literal × merge-commits × batching —
    you can keep at most two.** Phase 2 should not ship until the maintainer picks which
    constraint to relax.
- **Do I recommend batching?** Yes, *if* the maintainer accepts moving the up-to-date
  guarantee from GitHub's checkbox into Mergify (Phase 2, `merge-batch`). That preserves the
  merge-commit policy **and** the actual invariant (nothing merges without being tested on top
  of the latest `main`), and it is the only path to the #373-style throughput win now that the
  native merge queue is off the table. If the literal checkbox is non-negotiable *and* linear
  history is unacceptable, batching is unreachable and we stop at Phase 1.

---

## 1. Problem recap

The merge cascade (see the `ci-merge-cascade` note + issue #349) comes from three things
multiplying together:

1. **Branch protection `require branches to be up to date before merging`** — a **hard
   maintainer rule that stays**. Every PR must be tested against the current tip of `main`
   before it merges.
2. **Serial merges** — merging PR *A* advances `main`, which makes every other open PR
   out-of-date, so each must be re-bumped and re-run before it can merge.
3. **A slow, wedge-prone E2E matrix** — each PR fans out to API 29–36 + the API 37 preview
   (~15 min, occasionally wedging on an emulator boot race).

Draining a multi-PR batch is therefore painful: the #373 Robolectric epic was ~6 PRs, each a
full E2E run, hand-bumped one at a time. GitHub's **native merge queue** solves exactly this,
but it is **org/enterprise-only** and **JMR-dev is a user account**, so it is unavailable.

The repo's current mitigation is a hand-built "poor-man's merge queue":

| Piece | File | Role | Status |
|---|---|---|---|
| Auto-update | `.github/workflows/autoupdate.yml` | rebases behind PRs with `GITHUB_TOKEN` (no CI re-trigger, by anti-recursion) | active, being mothballed |
| CI trigger / scheduler | `.github/workflows/ci-trigger.yml` | re-triggers CI for the top-priority PRs, `MAX_INFLIGHT_RUNS: 2` | active, being mothballed |
| Runner priority | `.github/workflows/traffic-control.yml` | in-run P0-preempt / P1–P9 hold-back ordering | **mothballed/disabled** |
| Decision core | `.github/scripts/traffic_control.py` | pure, unit-tested priority logic | kept (still unit-tested by `traffic-control-tests`) |

**Mergify replaces the first three outright.** The single required gate — the `ci-passed`
job, status name **`CI passed`** — stays exactly as is.

---

## 2. What Mergify is + free-for-OSS eligibility

Mergify is a third-party GitHub App providing a real **merge queue** with **batching**,
**speculative/parallel checks**, auto-update/rebase, and label-driven **priority**.

- **Free for open source.** The **"Open Source" plan is $0** with **unlimited users** for
  public repositories (private teams are free up to 5 active contributors). LibreMail is a
  public GPL-3.0 repo, so it qualifies. ([pricing](https://mergify.com/pricing),
  [marketplace](https://github.com/marketplace/mergify))
- **Full product on the free tier.** "Every plan includes … Merge Queue, Merge Protections …"
  — batching and speculative checks are **not** gated behind a paid tier.
  ([pricing](https://mergify.com/pricing))

---

## 3. How Mergify's merge queue works

1. A PR is **queued** (by a `pull_request_rules` `queue` action when it is green + approved,
   or manually via a `@mergifyio queue` comment).
2. Mergify builds a **temporary branch** = *latest `main`* + the queued PR(s), and runs your
   CI on that temporary branch — i.e. it tests **against an up-to-date base speculatively**,
   without touching the PR's own branch. ([merge queue](https://docs.mergify.com/merge-queue/),
   [parallel checks](https://docs.mergify.com/merge-queue/parallel-checks/))
3. When the temporary branch's checks pass, Mergify **merges** the original PR(s).
4. **Speculative / parallel checks:** Mergify validates several positions of the queue at once
   — `(A)`, `(A+B)`, `(A+B+C)` — so it does not pay one-CI-run-per-PR serially.
   `merge_queue.max_parallel_checks` bounds the concurrency (our analogue of the old
   `MAX_INFLIGHT_RUNS: 2`). ([parallel checks](https://docs.mergify.com/merge-queue/parallel-checks/))
5. **Batching** (`batch_size > 1`): Mergify combines the next *N* PRs into **one** temporary
   batch branch and validates the whole batch with **a single CI run** — the key win for
   test-only batches. It groups by priority + similarity (touching-similar-files) so batches
   are cohesive. ([batches](https://docs.mergify.com/merge-queue/batches/))
6. **Batch failure → automatic bisection:** a red batch is **not** wholesale dequeued; Mergify
   splits it (down toward single PRs) to isolate the culprit, dequeues only that PR, and lets
   the rest proceed — bounded by `batch_max_failure_resolution_attempts`.
   ([batches](https://docs.mergify.com/merge-queue/batches/))

---

## The require-up-to-date trilemma

**This is the crux of the whole proposal.** Mergify's own docs are explicit:

> "Batches require the branch protection setting *Require branches to be up to date before
> merging* to be **disabled**." — [Mergify: Merge Queue Batches](https://docs.mergify.com/merge-queue/batches/)

The reason: with batching, Mergify tests a *temporary batch branch* that is up-to-date, then
merges the **original** PRs — whose own branches are **not** up-to-date per GitHub — so the
literal checkbox would block the merge. The documented ways out each cost something:

| You want to keep… | …and also… | …then batching is | Cost |
|---|---|---|---|
| `require-up-to-date` checkbox **literally on** | merge commits (`merge_method: merge`) | **serial only** (no batching) | no throughput win |
| `require-up-to-date` checkbox **literally on** | **batching** | via `queue_branch_merge_method: fast-forward` | **linear history — breaks the merge-commit policy** ([discussion #5138](https://github.com/Mergifyio/mergify/discussions/5138)) |
| merge commits **and** batching | (the throughput win) | yes, `merge_method: merge-batch` | **checkbox must be off**; guarantee moves into Mergify |

So `{ require-up-to-date literal · merge-commits · batching }` — **pick two.**

**The key reframing:** the maintainer rule is really about the **invariant** — *never merge
code that wasn't tested against the latest `main`.* Mergify's speculative queue **enforces
that invariant by construction** (it literally builds `latest-main + PRs` and tests *that*
before merging). Whether the *invariant* is enforced by GitHub's checkbox or by Mergify's
queue, it holds either way. Turning the checkbox off in Phase 2 does **not** weaken the
invariant — arguably it strengthens it (GitHub's checkbox only guarantees the branch *was*
up-to-date at some point and then requires a fresh run; Mergify guarantees the *exact merged
commit set* passed on top of the exact tip). But it **does** flip a literal setting the rule
names, so it is a conscious call for the maintainer, not something this proposal does silently.

**Recommended resolution:** Phase 1 keeps the checkbox literally on (serial). Phase 2 uses
**`merge_method: merge-batch`** (one merge commit per batch — preserves the merge-commit
policy), turns the **checkbox off**, and closes the bypass with "block any merge outside
Mergify" (below). The `fast-forward` row is documented for completeness but **not
recommended** — it would silently drop merge commits.

> **Open item to verify in a trial** (do not take on faith from docs): confirm on a throwaway
> branch that `merge-batch` lands a real merge commit and that, with the checkbox off + "merge
> only via Mergify" set, no path exists to merge a stale PR outside the queue.

---

## 4. Coexistence with the single `CI passed` gate

- Branch protection keeps requiring the one context **`CI passed`**. No change to
  `ci.yml`'s `ci-passed` fan-in.
- Mergify **injects the repo's required checks as queue conditions automatically**
  (`branch_protection_injection_mode`, default `queue`), and the proposed config *also* names
  `check-success = CI passed` in `merge_conditions` explicitly, so a PR merges **only** when
  the same gate branch protection requires is green. ([queue action](https://docs.mergify.com/workflow/actions/queue/))
- During Phase-1 serial operation Mergify "**respects your GitHub branch protections and
  rulesets**" out of the box — including `require-up-to-date`, which it satisfies by updating
  the branch itself before merging. ([setup](https://docs.mergify.com/merge-queue/setup/))

> **Gotcha:** the condition string must match the check name **exactly** — `CI passed` (the
> `name:` of the `ci-passed` job), *not* `ci-passed`. A wrong name means PRs queue but never
> merge (or never queue).

---

## 5. Priority: mapping the P0–P9 labels

Mergify `priority_rules` assign a priority from PR conditions; higher merges first (keywords
`low`=1000 / `medium`=2000 / `high`=3000, or numeric 1–10000; unmatched → `medium`).
([priority](https://docs.mergify.com/merge-queue/priority/)) The proposed config maps
**P0 → 10000 … P9 → 1000** linearly, so:

- **P0** (emergency-only) outranks everything and is picked first for the next batch.
- Unlabelled PRs fall to Mergify's default `medium` (2000) — the same "no label ⇒ P5-ish
  default" behaviour `traffic_control.py` uses today.
- `broken`/`draft` PRs are **excluded from queueing** (they simply shouldn't merge), rather
  than run at the bottom as the old traffic-controller did.

`allow_checks_interruption` (default true) reproduces the old **P0-preempts** behaviour: a
higher-priority arrival can interrupt in-flight speculative checks of lower-priority PRs.

---

## 6. What it replaces

| Old piece | Fate under Mergify |
|---|---|
| `autoupdate.yml` (branch bumping) | **Disable** — the queue updates branches itself. |
| `ci-trigger.yml` (priority scheduler, `MAX_INFLIGHT_RUNS`) | **Disable** — replaced by the queue + `max_parallel_checks`. |
| `traffic-control.yml` (already mothballed) | **Delete** (or leave disabled). |
| `traffic_control.py` + `traffic-control-tests` job | **Keep** — harmless, still unit-tested; can be removed later. Note: `ci-passed` currently lists `traffic-control-tests` in its `needs`; leave it or drop it, either is fine. |
| `AUTOUPDATE_TOKEN` secret / PAT plumbing | Becomes unnecessary — Mergify acts via its App installation, not a PAT. |

Net: a large amount of bespoke CI-orchestration YAML + Python is superseded by one
`.mergify.yml` and the App.

---

## 7. Interaction with the other CI workstreams

- **E2E path-filter (#399 / #402):** synergistic. Mergify runs CI on the *batch* branch, so
  the path filter evaluates the batch's *combined* diff: a docs-only batch still skips E2E and
  merges fast; a test-only batch still triggers the E2E it needs. The one requirement — that a
  path-skipped run still reports a green **`CI passed`** — is already how branch protection is
  satisfied, and Mergify keys on the same context, so no extra work. (Verify the skip path
  produces `CI passed`, not an absent check, or the queue will wait forever.)
- **E2E sharding (#372, and the API 37 N=2 shards):** orthogonal and compounding — faster CI
  ⇒ faster batch validation ⇒ shorter queue latency. No config interaction.
- **Wedge diagnostics (#404 / #406):** still run inside the CI Mergify triggers. Better,
  `checks_timeout` on the queue bounds a wedged leg: instead of a hung run blocking the queue,
  the batch times out and Mergify bisects/re-runs — turning "hand-babysit a wedged emulator"
  into an automatic dequeue of the offending PR.

---

## 8. Proposed `.mergify.yml`

The full proposed config is in **`docs/ci/mergify.yml.proposed`** (kept as `*.proposed`, **not**
a live `.mergify.yml`, so nothing activates). Outline below.

```yaml
# NOT ACTIVE — see docs/ci/mergify.yml.proposed for the annotated version.
queue_rules:
  - name: default
    merge_conditions:
      - check-success = CI passed          # the single required gate
      - "#approved-reviews-by >= 1"
      - -draft
      - label != broken
    # Phase 1 (keeps require-up-to-date literally ON): batch_size: 1 + merge_method: merge
    # Phase 2 (batching — checkbox off, guarantee moved into the queue):
    batch_size: { min: 1, max: 5 }         # dynamic; one CI run per batch
    batch_max_wait_time: 5 min
    merge_method: merge-batch              # one MERGE COMMIT per batch (keeps merge policy)
    checks_timeout: 45 min                 # slow/wedge-prone E2E: time out -> bisect

merge_queue:
  max_parallel_checks: 2                   # == old MAX_INFLIGHT_RUNS

priority_rules:                            # P0 -> 10000 ... P9 -> 1000 (higher merges first)
  - { name: p0-emergency, conditions: [label = P0], priority: 10000 }
  # … P1..P9 …

pull_request_rules:
  - name: Queue green, approved, non-draft PRs targeting main
    conditions:
      - base = main
      - -draft
      - label != broken
      - "#approved-reviews-by >= 1"
      - check-success = CI passed
    actions:
      queue: { name: default }
```

---

## 9. Setup steps (free-OSS)

1. **Install the Mergify GitHub App** on `JMR-dev/LibreMail` from the
   [marketplace](https://github.com/marketplace/mergify) (Open Source plan, $0). Requires
   GitHub admin on the repo. The App requests read/write on pull requests, checks, and
   contents (it must be able to create temporary branches and merge).
2. **Commit `.mergify.yml`** at the repo root — start from `docs/ci/mergify.yml.proposed`,
   **Phase-1 variant** (`batch_size: 1`, `merge_method: merge`). Mergify validates the config
   on push.
3. **Keep** branch protection `require branches up to date` + the required `CI passed` context
   unchanged (Phase 1). Confirm PRs queue and merge.
4. **Phase 2 (only after the maintainer signs off on the trilemma):** switch to
   `merge_method: merge-batch` + `batch_size`, **turn off** GitHub's literal
   `require branches up to date` checkbox, and **block merges outside Mergify** (restrict who
   can push/merge to `main` so the queue is the only merge path — Mergify's recommended
   companion to disabling the checkbox
   ([discussion #5138](https://github.com/Mergifyio/mergify/discussions/5138))).
5. **Disable** `autoupdate.yml` and `ci-trigger.yml`; delete/retire `traffic-control.yml`.

---

## 10. Risks & tradeoffs

- **Third-party in the merge path.** Mergify gets write/merge authority on `main`. It is an
  OSS-standard, widely used app, but it is a new external dependency and a supply-chain /
  availability surface. If Mergify is down, merges pause (fail-safe, not fail-open).
- **The require-up-to-date decision (Phase 2).** Turning off the literal checkbox is the
  price of batching. Mitigated by moving the guarantee into the queue **and** blocking
  out-of-queue merges — but it is a real, conscious change to a rule the maintainer has
  pinned, and must be signed off, not assumed.
- **Speculative-check CI cost.** Parallel checks and batch bisection **run CI more**: a failed
  batch can re-run the ~15-min E2E matrix several times while splitting to find the culprit.
  On a slow/wedge-prone matrix that is a genuine runner-minute cost. Mitigate with a small
  `batch_size` cap, `batch_max_wait_time`, `checks_timeout`, and modest `max_parallel_checks`.
- **Batching hides which PR broke it (until bisection finishes).** A red batch doesn't
  immediately name the culprit; Mergify bisects automatically, but that costs runs + latency.
  Best for **test-only / low-risk** batches (the #373 case); route risky feature PRs through a
  serial path (batch_size 1) or lower priority so they validate alone.
- **Config complexity + a new failure mode.** `.mergify.yml` is another surface. The classic
  footgun is a condition that doesn't match the real check name (`CI passed`) — PRs then queue
  but never merge. Needs a short trial to shake out.
- **Loss of bespoke control.** The traffic-controller's exact P0-preempt / hold-back semantics
  are approximated (not byte-identical) by Mergify priorities + `allow_checks_interruption`.
  Close enough in practice, but not the same code.

---

## 11. Recommendation

**Adopt Mergify, phased.**

- **Phase 1 now:** serial queue. Immediate elimination of the manual serial-bump grind and the
  whole traffic-controller apparatus, with **zero** change to the require-up-to-date rule or
  the merge-commit policy. Low risk, high daily-quality-of-life win.
- **Phase 2 when the maintainer signs off:** batching via `merge-batch`, moving the
  up-to-date **guarantee** into the queue (checkbox off, invariant preserved, merges locked to
  Mergify). This is the only remaining path to the #373-scale throughput win (native queue is
  unavailable) and it keeps the merge-commit policy. Gate it on the explicit trilemma decision
  and a throwaway-branch trial that verifies (a) `merge-batch` really lands merge commits and
  (b) no stale PR can merge outside the queue.

If the literal checkbox is truly immovable *and* linear history is unacceptable, **stop at
Phase 1** — that is still a large win over today.

---

## Sources

- [Mergify — Merge Queue](https://docs.mergify.com/merge-queue/)
- [Mergify — Merge Queue Batches](https://docs.mergify.com/merge-queue/batches/)
- [Mergify — Parallel Checks](https://docs.mergify.com/merge-queue/parallel-checks/)
- [Mergify — Using Queue Rules](https://docs.mergify.com/merge-queue/rules/)
- [Mergify — Using Priorities](https://docs.mergify.com/merge-queue/priority/)
- [Mergify — Merge Strategies](https://docs.mergify.com/merge-queue/merge-strategies/)
- [Mergify — Configuration File Format](https://docs.mergify.com/configuration/file-format/)
- [Mergify — queue action](https://docs.mergify.com/workflow/actions/queue/)
- [Mergify — Merge Queue setup](https://docs.mergify.com/merge-queue/setup/)
- [Mergify changelog — new `merge-batch` merge method](https://docs.mergify.com/changelog/2026-04-07-new-merge-batch-merge-method-for-merge-queue/)
- [Mergify discussion #5138 — Merge Queue and branch up-to-date protection](https://github.com/Mergifyio/mergify/discussions/5138)
- [Mergify pricing](https://mergify.com/pricing) · [Mergify on GitHub Marketplace](https://github.com/marketplace/mergify)
- Repo: `.github/workflows/ci.yml`, `autoupdate.yml`, `ci-trigger.yml`, `traffic-control.yml`, `.github/scripts/traffic_control.py`; issue #349 + the `ci-merge-cascade` note.
