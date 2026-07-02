<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Issue #86 — mailbox message-loading profiling

Empirical validation of the issue-#86 hypothesis that the mailbox list is slow because its data
path scales with the **total** cached message count, not with what's visible. Measured on an
emulator against a large seeded cache **before** committing a fix (as the ticket asked).

## TL;DR / verdict

- **The SQL-scoping theory holds, decisively.** The current path
  (`MessageDao.observeSummaries()` → whole `messages` table → `MailRepositoryImpl.observeMessages()`
  maps every row → `MailboxViewModel` `.filter{}`) is **O(total cache size)**: ~5 ms at 1k rows,
  ~25 ms at 5k, **~125 ms at 20k**. A SQL-scoped `WHERE accountId = ? AND folder = ?` query is
  **~1.5 ms, flat** regardless of cache size — **~80× faster at 20k**. This cost is paid on **every**
  re-emission, i.e. on every unrelated write to `messages` (IDLE delivery, a read/star toggle, a
  backfill page, a sync of any other folder), because Room re-runs the whole-table query on any write
  to the table.
- **No new index is needed, and no schema migration.** The account-scoped query is already served by
  the **existing** `(accountId, folder, uid)` index (added in v13 for backfill). The ticket's proposed
  `(accountId, folder, inInbox, timestampMillis)` composite index changes the timing only within noise
  (80× → 87×) — and SQLite's planner doesn't even prefer it when both are present. So the fix is pure
  SQL WHERE-scoping; **the v14→v15 migration (and the #118 coordination) is avoided entirely.**
- **Second contributor found: the unified "All inboxes" view.** With no account selected the query is
  folder-only (`WHERE folder = 'INBOX'`), which no existing index leads with, so it still scans in
  timestamp order — better than the whole-table path (it materializes only INBOX rows, ~5.6× at 20k)
  but still **O(N)** and it returns the whole unified inbox (3.7k rows at 20k, vs ~15 on screen). The
  real fix there is **paging** (± a `(folder, timestampMillis)` index), deferred below. Notably the
  ticket's `accountId`-leading index would **not** help this case at all.
- **IMAP latency (the ticket's other suspected contributor) was not measured** — it is a separate
  network path (`MailSyncer.syncFolder` on folder open) independent of the cached-list rendering this
  benchmark covers. The cached-list bottleneck is real and confirmed on its own.

## Setup & method

- Device: Gradle-managed AVD `libremail_api29` (API 29, x86, google_apis), headless, animations off.
- Harness: a throwaway instrumented probe built the real `LibreMailDatabase` in-memory (real entities,
  real indices, real generated `MessageDao`), seeded it, and timed each query variant with **8 warmup
  + 25 measured iterations**, reporting **median and min** wall-clock ms with a GC between phases.
  `androidx.benchmark` was deliberately *not* used: on an emulator only the **relative** A/B result is
  meaningful (absolute nanos aren't representative of a real device), and it would have forced the
  module's global instrumentation runner to `AndroidBenchmarkRunner`, changing what CI's E2E jobs run
  under. Warmup + median/min-of-many is robust to emulator scheduling/GC noise for an order-of-
  magnitude comparison. (The probe was removed after measuring; the code path it exercised is now the
  shipped `observeFolderSummaries` / `observeUnifiedFolderSummaries`.)
- Dataset: a fixed **150-row visible page** (`acct0` / `INBOX`) held constant while the total cache
  grows to 1 000 / 5 000 / 20 000 rows, so the visible page is a shrinking fraction of the whole cache
  (15 % → 3 % → 0.75 %) — modelling a long-lived, fully-backfilled account (#12/#13). The remaining
  rows are distractors spread across 3 accounts × {INBOX, Sent, Archive, Spam}. Bodies are stored so
  rows are realistically sized, but the summary projection never selects them.
- Variants per size: (a) **CURRENT** = `observeSummaries()` (whole table) + map-to-domain + the
  `MailboxViewModel` filter down to the page; (b) **SCOPED acct+folder** = `WHERE accountId=? AND
  folder=?`; (c) **UNIFIED folder-only** = `WHERE folder=?`. (b)/(c) were measured with the existing
  indices and again after adding the candidate indexes, and each query's `EXPLAIN QUERY PLAN` was
  captured with existing indices only.

## Results (median / min ms, lower is better)

| total rows | visible page | CURRENT (whole-table + filter) | SCOPED acct+folder (existing → +composite) | UNIFIED folder-only (existing → +folder idx) |
|-----------:|-------------:|-------------------------------:|:------------------------------------------:|:--------------------------------------------:|
|      1 000 |          150 |                  **4.98** / 4.20 |                        1.83 → 1.61          |                       2.48 → 1.89            |
|      5 000 |          150 |                 **25.17** / 22.37 |                        1.43 → 1.45          |                       5.46 → 4.75            |
|     20 000 |          150 |                **127.6** / 122.8 |                        1.62 → 1.46          |                      23.0 → 18.6             |

Speedups @ 20 000 rows (vs current): **acct+folder 79× (existing index), 87× (+composite)**; unified
5.6× (existing), 6.9× (+folder index). The current path's cost is the whole-table SQL scan + cursor
materialization of all N rows plus mapping all N to domain objects — both eliminated by scoping.
(A second run against the exact shipped SQL reproduced these figures; its 20k *median* was GC-inflated
to 254 ms while its *min* stayed at 125 ms — the min is the stable floor, hence both are reported.)

## Query plans (`EXPLAIN QUERY PLAN`, existing indices only)

```
CURRENT whole-table : SCAN TABLE messages USING INDEX index_messages_timestampMillis
SCOPED  acct+folder : SEARCH TABLE messages USING INDEX index_messages_accountId_folder_uid
                      (accountId=? AND folder=?) ; USE TEMP B-TREE FOR ORDER BY
UNIFIED folder-only : SCAN TABLE messages USING INDEX index_messages_timestampMillis
```

- CURRENT walks the timestamp index to avoid a sort but **reads every row** → O(N).
- SCOPED seeks the ~150 matching rows via the existing `(accountId, folder, uid)` index's
  `(accountId, folder)` prefix, then sorts them with a temp b-tree — trivial for 150 rows (hence the
  composite index, which would remove that tiny sort, makes no measurable difference). After adding the
  composite `idx_afit`, **SQLite keeps choosing `index_messages_accountId_folder_uid`** — confirming the
  new index is redundant.
- UNIFIED has no `folder`-leading index → scans. A `(folder, timestampMillis)` index (not the ticket's
  `(folder, inInbox, …)`, which still needs the sort for the no-`inInbox` unified query) would turn it
  into a seek — deferred.

## Answers to the ticket's questions

1. **Root cause confirmed via profiling on a large cache?** Yes — the whole-table observe + map + filter
   is O(total cache) and dominates at scale; the SQL-scoped query is flat.
2. **Scope the list query in SQL instead of Kotlin?** Yes — implemented (below).
3. **Composite index needed?** **No.** The existing `(accountId, folder, uid)` index already captures
   the account-scoped win; the ticket's index adds only noise and isn't even preferred by the planner.
   No schema change / migration is made.
4. **Second contributor?** Yes — the unified inbox stays O(N) (folder-only scan) and returns the full
   unified inbox; its real fix is paging (± a `(folder, timestampMillis)` index). Separately, IMAP
   round-trip latency on folder open remains a distinct, unmeasured network concern.

## What was implemented

- `MessageDao.observeFolderSummaries(accountId, folder)` and `observeUnifiedFolderSummaries(folder)` —
  the SQL-scoped, newest-first list projections (`inInbox`-agnostic so one query serves both the normal
  list and search).
- `MailRepository.observeFolderMessages` / `observeUnifiedFolderMessages` replace the whole-table
  `observeMessages()`.
- `MailboxViewModel.messages` now `flatMapLatest`es over the selected account+folder to the scoped
  flow; the only remaining client-side pass distinguishes the normal list (`inInbox`) from an active
  search (`matchesSearch`) over the small folder-scoped set — never the whole cache. `StateFlow`'s
  built-in equality de-dup means an unrelated write now costs one cheap scoped re-query and no
  recomposition.
- `observeSummaries()` is retained as the #51 CursorWindow regression-guard target in the DB tests.

## Deferred follow-ups

- **Unified inbox**: add Room `PagingSource`/Paging3 so the unified list's cost scales with the screen,
  not the total inbox count; optionally a `(folder, timestampMillis)` index to turn the scan into a
  seek. (The per-account views are already flat, so Paging3 there is lower priority.)
- **IMAP latency on folder open** is out of scope for this cached-render fix.

## Reproduce

Seed a large `messages` cache and compare `observeSummaries()` (+ a client filter) against
`observeFolderSummaries(account, folder)` on an emulator, timing first-emit at 1k/5k/20k rows and
inspecting `EXPLAIN QUERY PLAN`. The scoped query should be flat (~1.5 ms) and use
`index_messages_accountId_folder_uid`; the whole-table query should grow linearly.
