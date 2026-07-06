<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# API 37 preview E2E sharding — feasibility spike

A time-boxed investigation into whether **sharding the `e2e-preview` job** (the hand-provisioned
API 37 / `google_apis_ps16k` 16 KB-page emulator that runs the whole instrumented suite serially)
would meaningfully cut CI wall-clock. `e2e-preview` is **consistently the single longest leg in CI**
(~16.4–17.6 min on recent runs), so it sets the pipeline's critical path.

This is an **assessment + recommendation**, not a finished feature. It was produced from CI run logs
and repo config only — **no emulator was booted locally** (avoids the machine-freeze risk noted in the
project memory and runner contention). All numbers below come from real successful `e2e-preview` runs.

## TL;DR / recommendation

**GO — shard `e2e-preview` in CI at `N = 2`** (a `strategy.matrix.shard: [1, 2]` fan-out, each shard
provisioning its own API 37 emulator and running one half of the suite via AndroidJUnitRunner's built-in
`numShards`/`shardIndex`). Projected: the leg drops from **~17.1 min → ~12.7 min**, cutting the whole
pipeline's critical path by **~4.9 min (~28%)** for the cost of **+1 emulator job (~1.5× this leg's
emulator-minutes)**.

- **`N = 3` is a defensible stretch** (~11.2 min) but buys only ~0.7 min more of *total-CI* time, because
  at that point the **rest of the E2E matrix (API 30, ~12.0 min) becomes the new critical path**. The
  extra 0.7 min costs a second added emulator and +50% more preview-boot flake surface.
- **Do NOT exceed `N = 3`.** Below ~12 min the API 29–36 matrix caps the pipeline; further API 37 shards
  burn emulator-minutes for zero wall-clock gain.
- **Keep local preflight single-emulator.** `api37_e2e.py` / `local_instrumented.py` must stay one cold
  boot — a dev box has one free hypervisor (VT-x/WHPX) and the project memory explicitly warns parallel
  emulators can freeze the machine. Sharding is a **CI-only** change.
- The mechanism is already proven in-repo: `local_instrumented.py` passes
  `-Pandroid.testInstrumentationRunnerArguments.class=…` to the same `connectedDebugAndroidTest` task,
  so `numShards`/`shardIndex` args flow the identical way — **no GMD, no orchestrator, no Gradle change**.

## 1. Current job anatomy + timing breakdown

`e2e-preview` (`.github/workflows/ci.yml`) hand-provisions the emulator because API 37's only image is the
nonstandard `system-images;android-37.0;google_apis_ps16k;x86_64` (16 KB page size), which neither
`reactivecircus/android-emulator-runner` nor AGP's Gradle Managed Device DSL can build. It installs the
SDK + image directly (`sdkmanager`), creates the AVD (`avdmanager`), cold-boots headless with a
two-attempt retry loop, then runs `./gradlew connectedDebugAndroidTest`. `api37_e2e.py` mirrors this
locally (identical image/flags except `-gpu auto-no-window` vs CI's `-gpu swiftshader_indirect`).

### Step timing — reference run `28811804353` (branch `fix-359-sqlcipher-16kb`, **success**, total **17.6 min**)

| Phase | Step(s) | Time | Re-paid per shard? |
|-------|---------|-----:|:------------------:|
| Job/checkout/JDK/Gradle/KVM setup | 1–6 | ~0.4 min | yes |
| Restore image cache + `sdkmanager` install (cache **hit**) | 7–8 | ~0.7 min | yes |
| `avdmanager create avd` | 9 | ~0.03 min | yes |
| **Emulator cold boot** (1 failed attempt + retry) | 10a | **~1.1 min** | yes |
| **Gradle daemon start + configure + dependency resolution** | 10b | **~4.1 min** | yes |
| **Compile debug app + androidTest APK + install** | 10c | **~2.0 min** | yes (per runner) |
| **Test execution** (`Starting 263 tests` → `Finished 263 tests`) | 10d | **~8.75 min** | **NO — parallelizable** |
| Teardown, upload report/diagnostics, post-cache/gradle | 12–29 | ~0.4 min | yes |

Step 10 ("Boot emulator and run E2E", 16.05 min) bundles boot + build + test into one shell step; the
sub-splits above were recovered from the step's own timestamped log markers
(`##[group]Start API 37 emulator`, `Starting N tests on emulator-5554`, `Finished N tests`, `BUILD SUCCESSFUL`).

### The boot-vs-test ratio (the crux)

Sharding only parallelizes **test execution**; every shard re-pays boot **and** the whole fixed prologue
(setup + boot + Gradle daemon/config + compile + install + teardown). The fixed cost is **not just boot**
(~1.1 min) — it is dominated by the ~6 min Gradle daemon-start/configure/compile block. Splitting the job:

- **`T` (parallelizable test execution) ≈ 8.8 min**
- **`B` (fixed, re-paid by every shard) ≈ 8.3 min**

i.e. **B ≈ T** — roughly a 50/50 split. That is the single most important number in this spike: because
the fixed overhead is about as large as the test time, sharding has a **hard floor of ~8.3 min** no matter
how many shards you add.

### Cross-run confirmation (test execution is stable, not a one-off)

| Run | Branch | Total leg | Test-exec window | Boot attempts |
|-----|--------|----------:|-----------------:|:-------------:|
| `28811804353` | fix-359-sqlcipher-16kb | 17.6 min | ~8.75 min (263 tests) | 2 |
| `28763651551` | feat-125-imap-connection-reuse | 16.9 min | ~9.2 min (262 tests) | 1 |
| `28760208017` | fix-359-sqlcipher-16kb | 16.4 min | ~8.4 min (261 tests) | 2 |

Test execution is a tight **8.4–9.2 min**; the leg total **16.4–17.6 min**. Run-to-run variance (~±0.6 min)
is mostly the boot retry (1 vs 2 attempts costs ~1 min) — reinforcing that boot flake, not test time, drives
the jitter.

## 2. Instrumented-test inventory

- **~263 instrumented `@Test` methods across 59 test classes** (`app/src/androidTest/…`; the runner
  reports "Starting 263 tests"). Confirmed by static count (`@Test` occurrences) and the CI log.
- Distribution is **class-skewed**: a few heavy classes dominate — `MessageDaoTest` (17),
  `MigrationTest` (15), `MailboxScreenTest` (13), `AccountDataMigratorTest` (8), several DAO/`*Database`
  classes (7 each) — while most classes have 1–6.
- The suite is a **mix** of Room/DAO/migration DB tests (CPU + disk on `google_apis_ps16k`) and Compose UI
  tests (Espresso/`createComposeRule`). No single test is a multi-minute outlier; 263 tests / ~8.8 min ≈
  **~2 s/test average**, so runtime is spread reasonably evenly and **count-based sharding will be
  approximately runtime-balanced** at N = 2–3.

At ~263 tests, **N = 2 (~131/shard) or N = 3 (~88/shard)** are the sensible shard counts. Beyond that the
per-shard test time shrinks below the fixed boot/build cost and the split stops paying off (see §4).

## 3. Sharding approaches

### 3a. AndroidJUnitRunner built-in sharding across a GHA matrix — RECOMMENDED

`AndroidJUnitRunner` has first-class sharding: it hashes each test name into one of `numShards` buckets and
runs only `shardIndex`'s bucket. AGP surfaces runner args through Gradle properties, so on the **existing
hand-provisioned `connectedDebugAndroidTest`** (no GMD required) each shard runs:

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.numShards=2 \
  -Pandroid.testInstrumentationRunnerArguments.shardIndex=${{ matrix.shard-index }}
```

Wrapped in a `strategy.matrix.shard: [1, 2]` (or `[1, 2, 3]`) fan-out where each matrix leg provisions its
**own** API 37 emulator and runs one shard. This is the same fan-in pattern the repo **already uses** for
the `e2e` (API 29–36) matrix, and the same runner-arg channel `local_instrumented.py` already uses for
`…arguments.class=` — so it is a known-good mechanism here, needs **no orchestrator, no Gradle-side change,
and no branch-protection change** (see §5).

**Confirmed compatible with the hand-provisioned 16 KB job:** runner-arg sharding is a property of
`AndroidJUnitRunner` + the AGP `connected*AndroidTest` task, entirely independent of *how* the AVD was
created. GMD is irrelevant. Count-based, deterministic, and stable across runs.

### 3b. Alternatives (rejected)

- **Manual test-class partitioning** (`-Pandroid.testInstrumentationRunnerArguments.class=A,B,C` per shard,
  or `package`/annotation buckets): lets you hand-balance by runtime, but is **brittle** — every new test
  class must be manually assigned or it silently runs in no shard (coverage gap) or a wrong one. Not worth
  it for a ~0.5 min balance gain over hashing.
- **Android Test Orchestrator** (`clearPackageData`): solves per-test process isolation, **not** wall-clock;
  it actually *slows* a run (fresh process per test). Orthogonal to sharding — skip.
- **`maxParallelForks` (in-JVM):** N/A — that is a *JVM unit-test* knob (and the very thing #258 rejected
  because GreenMail binds fixed ports). Instrumented tests run on the device, not forked JVMs.

## 4. Expected wall-clock gain vs. CI cost

Model: an N-shard leg ≈ `B + T/N` (parallel), costing ≈ `N × (B + T/N)` emulator-minutes. Using the measured
**B ≈ 8.3 min, T ≈ 8.8 min**:

| N | Leg time `B + T/N` | Δ vs today | Emulator-minutes `N × leg` | Cost multiple |
|:-:|-------------------:|-----------:|---------------------------:|:-------------:|
| 1 (today) | **17.1 min** | — | 17.1 | 1.0× |
| **2** | **12.7 min** | **−4.4 min** | 25.4 | 1.5× |
| 3 | 11.2 min | −5.9 min | 33.7 | 2.0× |
| 4 | 10.5 min | −6.6 min | 42.0 | 2.5× |
| ∞ | 8.3 min (floor = B) | −8.8 min | — | — |

But the **total-CI critical path** is `max(all jobs)`, and the API 29–36 E2E matrix already runs **~8.3–12.0
min (API 30 ≈ 12.0 is its ceiling)**. So the API 37 leg only helps the pipeline until it drops to that wall:

| N | API 37 leg | **Total-CI critical path** = max(API 37, API 30≈12.0) | Total CI saved |
|:-:|-----------:|:------------------------------------------------------:|---------------:|
| 1 | 17.1 | **17.6** (API 37) | — |
| **2** | 12.7 | **12.7** (API 37, just above the matrix wall) | **~4.9 min (~28%)** |
| 3 | 11.2 | **12.0** (now **API 30** caps it) | ~5.6 min (~32%) |
| 4 | 10.5 | 12.0 (API 30) — **no gain over N=3** | ~5.6 min |

**Point of diminishing returns.** Two effects compound:

1. **B ≈ T** gives a hard ~8.3 min floor for the leg itself (N = ∞).
2. Long before that, the **~12.0 min API 30 matrix wall** caps *total* CI. N = 2 lands the leg at 12.7
   (~0.7 above the wall); N = 3 tucks it under the wall (API 30 takes over). **N = 3 is the last count that
   changes anything**; N ≥ 4 is pure waste unless the API 29–36 matrix is *also* sharded (a separate,
   larger effort).

**Verdict:** **N = 2 captures ~4.9 of the ~5.6 min theoretically achievable** — the overwhelming majority
— for the least cost and flake. N = 3 collects the last ~0.7 min at +1 more preview emulator.

## 5. Risks / gotchas

1. **Flake multiplication (top code-risk).** `e2e-preview` is already the flakiest leg (16 KB preview image;
   boot/snapshot races; the `input keyevent 82` timing hazard; infra-vs-code ambiguity). N shards = N
   independent cold boots per pipeline, so `P(all shards boot)` = `p^N`. Mitigation is **built in**: each
   shard inherits the job's existing **two-attempt boot loop** (the memory note pegs a single un-retried
   boot race at ~2% → ~0.04% after two attempts), keeping aggregate boot success ~99.9% even at N = 3. Keep
   the retry loop per shard; do **not** collapse it to save time.
2. **Runner concurrency (top practical-risk).** The pipeline already fans out 8 E2E matrix jobs +
   `e2e-preview` + 3 lighter jobs (~12 concurrent). N = 2/3 pushes peak to ~13/14 concurrent runners. **If
   the account's concurrent-job limit is hit, shards queue serially and the entire wall-clock gain
   evaporates** (or inverts). The project already actively manages runner allocation/priority (P0–P9), so
   this is a real, tracked constraint — confirm headroom before enabling, and give the preview shards the
   same priority as the rest of the E2E matrix.
3. **`ci-passed` gate must require ALL shards — but needs no branch-protection edit.** Converting
   `e2e-preview` to a matrix makes GHA roll all shard legs under the one `e2e-preview` entry already in
   `ci-passed.needs`; the gate's `contains(needs.*.result, 'failure' | 'cancelled' | 'skipped')` check
   fails if **any** shard fails (a matrix job's `result` is `failure` when any leg fails). Branch protection
   only requires the single `CI passed` context, so **no protected-check list change** — same as the
   existing `e2e` matrix. Verified against the current gate wiring.
4. **Artifact-name collision.** Each shard uploads its own `app/build/reports/androidTests/connected/` HTML
   report; `actions/upload-artifact@v7` **errors on duplicate names**. The report name must gain the shard
   index (e.g. `e2e-test-report-api37-preview-shard${{ matrix.shard }}`), mirroring the API-level suffix the
   `e2e` matrix already uses. Same for the boot-diagnostics artifact.
5. **No cross-shard coverage merge needed (a simplifier vs #258).** #258's blocker was JaCoCo aggregation
   across shards. Here, **instrumented/E2E coverage is explicitly out of scope** (`app/build.gradle.kts`
   JaCoCo scoping + issue #192), so there is nothing to merge — shard results only need to fan into the pass/
   fail gate.
6. **Cold-cache thundering herd.** The ~1 GB preview image is cached (`actions/cache`, keyed identically for
   all shards). On a **warm** cache all shards get a fast hit (steady state). On a **cold** cache (first run
   after a key change), all N shards download ~1 GB concurrently — a rare N× bandwidth spike, self-healing
   on the next run. Acceptable; no change needed.
7. **Shard balance.** Count-based hashing is only *approximately* runtime-balanced; a shard that draws
   `MigrationTest` + the DAO-heavy classes may run marginally longer. Tolerable at N = 2–3 (§2); revisit only
   if a shard consistently lags.
8. **`concurrency: cancel-in-progress` churn.** Unchanged semantics (all shards share the PR concurrency
   group, cancelled together on a new push), but there are now more in-flight jobs to cancel and re-trigger
   on each push — a small addition to the known CI merge-cascade thrash. No new failure mode.
9. **Retry-parity gap — a *pre-existing* reliability asymmetry sharding must preserve (surfaced by #370).**
   The stable `e2e` matrix retries its test run **once** on failure (`ci.yml`, the two paired
   `reactivecircus/android-emulator-runner` "Run E2E tests" + "…(retry…)" steps), so a genuinely flaky test
   self-heals on API 29–36. **`e2e-preview` runs `connectedDebugAndroidTest` exactly once — no retry**, so
   the *same* flaky test **wedges the required gate on API 37**. This is exactly what flaked #370 (a real
   `SignaturesScreenTest` Room/`viewModelScope` teardown race, being fixed at the test level separately) —
   not a boot/timing issue. **Interaction with sharding (why it belongs here):** unlike **boot** flake,
   which sharding *multiplies* by N (risk #1), per-**test** flake is **not** amplified — every test still
   runs exactly once across the whole matrix, so a single flaky test lives in exactly one shard. But it
   still fails a *required* leg, so the gate needs a retry regardless of shard count. The right design is a
   **per-shard** retry: each shard retries only its own bucket, costing **`B + T/N`** (one shard's leg),
   strictly cheaper than the unsharded whole-suite retry (`B + T`). So sharding makes flake-recovery both
   still-necessary and *cheaper*. **Framing: mitigation, not a fix** — a blanket retry also masks genuine
   regressions, so surface the retried-but-passed case as a `::warning::` and keep the real fix test-level
   (as #370 does). See §6 for the recommendation and the PoC.

## 6. Recommendation

**Worth doing: yes.** `e2e-preview` is the pipeline's critical path, so unlike #258 (unit tests, which finish
~8.5 min *inside* the E2E-bounded gate and were correctly closed as not-worthwhile) sharding this leg
**directly** shortens total CI.

- **Ship `N = 2`** via a `strategy.matrix.shard` fan-out using AndroidJUnitRunner `numShards`/`shardIndex`
  (§3a). Expected total-CI critical path **~17.6 → ~12.7 min (~28% off)** for **+1 emulator job**. Keep the
  per-shard two-attempt boot loop; suffix shard index on the report/diagnostics artifact names; leave
  `e2e-preview` in `ci-passed.needs` (matrix fan-in keeps the single gate).
- **`N = 3` optional** only if the last ~0.7 min matters and runner headroom is comfortable — it moves the
  critical path onto the API 30 matrix leg (~12.0 min). **Never `N ≥ 4`** without also sharding the API 29–36
  matrix.
- **Local preflight stays single-emulator** — `api37_e2e.py` and `local_instrumented.py` keep their single
  hand-provisioned boot (do **not** shard locally). One free hypervisor per dev box; parallel local emulators
  risk freezing the machine (project memory). The sharding divergence is intentional and should be documented
  in the job comment.

Two **reliability** fixes to pair with sharding (from the #370 root-cause; §5 item 9) — together they make
the API 37 leg *faster **and** more reliable*:

- **Add retry parity.** Give `e2e-preview` the **same single test-run retry** the `e2e` matrix has, as a
  **per-shard** retry (re-runs only that shard's `B + T/N`, not the whole suite), and **mirror it into the
  local `api37_e2e.py`** test-run step (its `connectedDebugAndroidTest` invocation) so preflight and CI stay
  in lockstep. **This is a mitigation, not a fix:** a blanket retry masks genuine regressions, so flag the
  retried-but-passed case as a warning and keep real flakes fixed at the test level (as #370 did). Do **not**
  raise the retry count above one. *Sharding interaction:* a per-shard retry is the natural fit and is
  cheaper than an unsharded retry — see §5 item 9.
- **Minor: `adb start-server` before the boot loop** in `e2e-preview` (mirror `api37_e2e.py`'s
  `wait_for_boot`, which already does this), to avoid the attempt-1 adb-daemon "Address already in use" bind
  race seen in #370 (self-recovered there; low priority, but free to harden).

### Relationship to #258 (unit-test sharding)

#258 was closed **not-worthwhile** because the unit-test job (~6.5 min) isn't on the critical path (E2E
dominates), so sharding it saved ~0 wall-clock, and `maxParallelForks` collided on GreenMail's fixed ports.
**Reused pattern:** *measure the critical path first, only shard what's on it.* API 37 preview **is** on it,
so the conclusion flips to GO. **Distinct from #258:** different layer (on-device instrumented vs JVM),
different mechanism (`numShards`/`shardIndex` matrix vs `maxParallelForks`), and **no coverage-merge
constraint** (instrumented coverage is out of scope), which makes this strictly simpler to land.

## PoC

This branch carries a **draft, non-auto-merged** PoC that converts `e2e-preview` to an `N = 2` shard matrix
(clearly marked as a spike). It also folds in the two §6 reliability fixes on the CI side so the PoC shows
the full *faster + more reliable* picture: a **per-shard single test retry** (retry parity with the `e2e`
matrix, flagged `::warning::` on the retried-but-passed case) and an **`adb start-server` before the boot
loop**. The matching `api37_e2e.py` retry mirror is left as a documented recommendation (a focused local
change, since the local path is validated by a real emulator, which this spike deliberately did not boot).

Because a PR runs the workflow *from its own branch*, **the draft PR's own CI run is the validation** — the
maintainer can confirm both shards go green and read the real per-shard wall-clock straight off that run
(this is a CI-infra change, so a green pipeline is the "test", per #258's DoD note). It is **not** to be
merged as-is: adopt after reviewing the live shard timings and confirming runner headroom.
