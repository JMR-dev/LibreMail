<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Issue #124 — paging the unified "All inboxes" view

Follow-up to #86. PR #123 made the per-account+folder list flat by scoping the query in SQL, but the
unified inbox (`WHERE folder = ?`, no `accountId`) has no `folder`-leading index, so it still **scans**
in timestamp order and — worse — materializes the **entire** unified inbox (~thousands of rows) into
memory on every emission. This measures that path **before** the fix and **with** Paging 3, on a large
seeded cache, to decide whether an index (schema migration) is actually needed once paged.

## TL;DR / verdict

- **Paging fixes it; no index, no schema migration.** The current whole-unified-inbox query grows
  linearly with the number of INBOX rows (**~6.8 ms at 1k INBOX → ~24.6 ms at 4k INBOX**), because it
  reads and maps every INBOX row across all accounts. The paged first page (the production initial
  load of 120 rows) is **flat at ~5–7 ms regardless of total cache size** — it stops as soon as it has
  a screenful — a **~3.6× first-emit speedup at a 20k cache**, and, more importantly, it stays flat as
  the cache keeps growing (full-history backfill, #12/#13) while the current path keeps rising.
- **`EXPLAIN QUERY PLAN` still shows a `SCAN … index_messages_timestampMillis`** for the paged query on
  the existing indices — i.e. no `folder`-leading index is used — yet the paged first page is already
  flat, because the `LIMIT` lets the planner stop early. A `(folder, timestampMillis)` /
  `(folder, inInbox, timestampMillis)` index would only materially help a **deep scroll** (large
  `OFFSET`), which is rare and already bounded by scroll depth (not total cache), and even the worst
  deep page measured (40 rows at offset ~3.9k) is **faster than the current whole-inbox load**. So the
  ticket's strong preference holds: **Paging 3 alone captures the win — no `(folder, …)` index, no
  v15→v16 migration, no #118 version coordination.**
- **Per-account views are untouched** (already flat from #86); only the unified browse list is paged.

## Setup & method

- Device: Gradle-managed AVD `libremail_api29` (API 29, x86, google_apis), the same device #86 used, so
  the figures are comparable. Cross-checked on a physical Pixel (API 37) — same shape (see below).
- Harness: a throwaway instrumented probe (`UnifiedInboxPagingProbe`, removed after measuring, like
  #86's) built the real `LibreMailDatabase` in-memory (real entities, real indices, real generated
  `MessageDao`), seeded it, and timed each variant with **5 warmup + 15 measured iterations**, reporting
  **median and min** wall-clock ms with a GC between phases. `androidx.benchmark` was deliberately not
  used (as in #86: on an emulator only the **relative** A/B result is meaningful, and it would force the
  module's instrumentation runner, changing what CI's E2E jobs run under).
- Dataset: rows spread across **3 accounts × {INBOX, Sent, Archive, Spam, Work}** (INBOX ≈ 20%, so at
  20k the unified inbox is ~4k rows), timestamps interleaved across folders (a realistic worst case —
  INBOX rows are **not** clustered at the top of the timestamp index), each row carrying a ~1 KB body so
  rows are realistically sized (the summary projection never selects the body).
- Variants: (a) **CURRENT** = `observeUnifiedFolderSummaries("INBOX")` first Flow emission (materializes
  the whole unified inbox); (b) **PAGED first page** = `pagingUnifiedFolderSummaries("INBOX")` loaded via
  a `Refresh(key = null, loadSize = 120)` — the production `initialLoadSize` (3 × pageSize); (c) **PAGED
  deep page** = a `Refresh(key ≈ inboxCount − 60, loadSize = 40)` window near the end of the inbox
  (models scrolling to the bottom). Each query's `EXPLAIN QUERY PLAN` was captured with existing indices
  only.

## Results (median / min ms, lower is better)

| total rows | INBOX rows | CURRENT whole-inbox first-emit | PAGED first page (120) | PAGED deep page (40 @ ~end) |
|-----------:|-----------:|-------------------------------:|-----------------------:|----------------------------:|
|      5 000 |      1 000 |                  **6.80** / 4.93 |            5.44 / 4.64 |                 3.67 / 2.83 |
|     20 000 |      4 000 |                 **24.56** / 18.89 |            6.82 / 5.67 |                12.86 / 10.52 |

- **CURRENT scales with the inbox size:** 6.8 → 24.6 ms as INBOX rows go 1k → 4k (≈ linear); this cost
  is paid on **every** re-emission (any write to `messages` — IDLE delivery, a read/star toggle, a
  backfill page, a sync of any folder), and it also builds a full `List<Message>` of every INBOX row.
- **PAGED first page is flat:** ~5.4 → ~6.8 ms (within noise) — independent of the total cache, because
  the scan stops once it has 120 INBOX rows. This is the common case (opening / reading the top of the
  inbox), and it's what the acceptance criterion asks for.
- **PAGED deep page** (40 rows at offset ~3.9k) costs 12.9 ms at 20k — more than the first page (the
  `OFFSET` walks ~3.9k INBOX matches) but still **below the current whole-inbox load**, returns only 40
  rows (vs. the current path's 4 000), and scales with **scroll depth**, not total cache.

Cross-check on a physical **Pixel (API 37)** at 20k: CURRENT 27.2 / 24.8, PAGED first page 8.6 / 7.2,
PAGED deep page 15.3 / 12.4 ms — same shape (~3.2× first-page speedup).

## Query plans (`EXPLAIN QUERY PLAN`, existing indices only)

```
CURRENT whole-inbox : SCAN TABLE messages USING INDEX index_messages_timestampMillis
PAGED  first page   : SCAN TABLE messages USING INDEX index_messages_timestampMillis  (LIMIT 120)
PAGED  deep page    : SCAN TABLE messages USING INDEX index_messages_timestampMillis  (LIMIT 40 OFFSET ~3.9k)
```

- All three walk `index_messages_timestampMillis` newest-first (no `folder`-leading index), filtering
  `folder`/`inInbox` per row. The paged queries differ only by the `LIMIT`/`OFFSET` the planner applies,
  which is exactly what makes the first page cheap: it stops after a screenful.
- A `(folder, inInbox, timestampMillis)` index would turn the scan into a seek and remove the deep-page
  `OFFSET` walk. It is **not added**: the first page — the case the ticket targets — is already flat on
  the existing indices, deep scroll is rare and bounded by depth, and avoiding the index avoids a
  schema migration (v15→v16) and the #118 version-coordination it would require. Revisit only if deep
  scrolling the unified inbox becomes a measured problem.

## What was implemented

- `MessageDao.pagingUnifiedFolderSummaries(folder)` — a Paging 3 `PagingSource<Int, MessageSummary>`
  over `WHERE folder = ? AND inInbox = 1 ORDER BY timestampMillis DESC` (synced rows only; unified
  **search** keeps using `observeUnifiedFolderSummaries`, which also surfaces transient `inInbox = 0`
  hits).
- `MailRepository.pagedUnifiedFolderMessages(folder)` — wraps it in a `Pager`
  (`pageSize = 40`, `initialLoadSize = 120`, no placeholders) and maps summaries to domain.
- `MailboxViewModel.pagedMessages` — `flatMapLatest` to the paged flow while browsing the unified inbox
  (no account selected, no active search), else empty paging data; `cachedIn(viewModelScope)`. The old
  `messages` list flow now stays **empty** while browsing the unified inbox, so the whole inbox is never
  pulled into memory; it still serves per-account views and unified **search**. Selection carries each
  row's `accountId` (captured at tap time) so "Move" still works without a full in-memory list.
- `MailboxScreen` renders the unified browse list via `collectAsLazyPagingItems()`; per-account/search
  render the flat list as before.

## Reproduce

Seed a large multi-account `messages` cache, then compare `observeUnifiedFolderSummaries("INBOX")`
(first emit) against `pagingUnifiedFolderSummaries("INBOX")` loaded with a `Refresh(null, 120)` on an
emulator, and inspect `EXPLAIN QUERY PLAN`. The paged first page should be ~flat (~5–7 ms) regardless
of total cache; the whole-inbox query should grow with the INBOX row count.
