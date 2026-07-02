<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# IMAP folder-open latency (issue #125)

Structural analysis of the IMAP round-trips paid when opening/selecting a folder, a follow-up to the
#86 profiling and distinct from the mailbox cached-render fix (#123) and the fetch policy (#88–#90).

Performed 2026-07-02 against `main` by reading the folder-open path and confirming the round-trip
*structure* with deterministic GreenMail tests (`ImapFolderOpenLatencyTest` +
`CountingImapProxy`).

**Verdict.** The folder-open network path re-establishes a **full, freshly-authenticated IMAP
connection on every operation** — there is no connection pooling or keep-alive. Each folder-open pays
`CONNECT + TLS + LOGIN + EXAMINE + FETCH + LOGOUT`; only the `EXAMINE + FETCH` is intrinsic to opening
a folder, and the entire `CONNECT + TLS + LOGIN` setup group (the majority of the round-trips) is
**avoidable on the second and subsequent operations** if a connection were reused. The recommended
mitigation is a per-account connection cache/keep-alive. It is **not implemented here**: the sizing,
eviction, stale-detection, and battery trade-offs are genuine latency/battery decisions that need
real-network + real-device measurement (which localhost GreenMail — ~0 RTT — cannot provide), and a
naïve implementation risks regressing the deliberate concurrency design and the IDLE connection budget.
This is the "spike first, measure before committing" the issue asks for.

> **Note on numbers.** This document counts *protocol round-trips* (RTTs), which are deterministic and
> measurable in-process. It does **not** quote measured wall-clock latency — there is no real network
> or account in this environment. Where a millisecond figure appears it is explicitly *illustrative
> arithmetic* (`round-trips × RTT`), with RTT a placeholder for a real network's round-trip time.

## The folder-open path

Opening/selecting a folder in the UI runs two independent things:

1. **Render from cache (already optimized, not the subject of #125).**
   `MailboxViewModel.selectFolder()` sets `_selectedFolder` synchronously
   (`MailboxViewModel.kt:307`). That immediately re-filters the cached Room rows into the list — no
   network. #123 optimized this cached render. The network open below is *off* the render path, so its
   cost shows up as a background refresh, not a blank screen.

2. **Network sync (the subject of #125).**
   `selectFolder()` then launches `mailSyncer.syncFolder(accountId, folder)`:

   ```
   MailboxViewModel.selectFolder()                       (MailboxViewModel.kt:307)
     └─ MailSyncer.syncFolder()                           (MailSyncer.kt:80)
          └─ syncFolderHeaders()                          (MailSyncer.kt:87)
               ├─ connectionFactory.imapParamsFor(account)  (resolves/refreshes credentials)
               └─ imapClient.fetchRecent(params, folder, limit)   (MailSyncer.kt:93)
                    └─ ImapClient.withStore { … }          (ImapClient.kt:111, 521)
   ```

`ImapClient.withStore()` is the crux (`ImapClient.kt:521`):

```kotlin
private inline fun <T> withStore(params: ImapConnectionParams, block: (Store) -> T): T {
    val store = Session.getInstance(buildProps(protocol, params)).getStore(protocol)
    store.connect(params.host, params.port, params.username, params.secret)  // CONNECT + TLS + LOGIN
    return try { block(store) } finally { runCatching { store.close() } }     // LOGOUT + teardown
}
```

**Every** `ImapClient` operation — `fetchRecent`, `fetchOlderThan`, `search`, `fetchBodyMarkingSeen`,
`fetchBodyPeek`, `fetchAttachment`, `setFlag`, `deleteMessage`, `moveMessages`, `fetchForReply` — is a
`withStore { … }`, so each one builds and authenticates its own connection and tears it down. Nothing
is reused between operations.

## Per-open round-trip sequence

For one `fetchRecent` (a folder-open), the client → server exchange is:

| # | Step | RTTs | Necessary to *open a folder*? |
|---|------|------|-------------------------------|
| 1 | TCP handshake | ~1 | Setup — avoidable on reuse |
| 2 | TLS handshake (implicit TLS / `imaps`) | 1 (TLS 1.3) – 2 (TLS 1.2) | Setup — avoidable on reuse |
| 3 | `CAPABILITY` (Angus; reused from greeting when advertised) | 0–1 | Setup — avoidable on reuse |
| 4 | `LOGIN` / `AUTHENTICATE XOAUTH2` | 1 (+1 if challenged) | Setup — avoidable on reuse |
| 5 | `CAPABILITY` post-auth (reused from `LOGIN` response when advertised) | 0–1 | Setup — avoidable on reuse |
| 6 | `EXAMINE` (READ_ONLY select of the folder) | 1 | **Necessary** per folder |
| 7 | `FETCH` recent headers (`ENVELOPE FLAGS UID`) | 1 | **Necessary** header download |
| 8 | `LOGOUT` + socket teardown | ~1 | Setup — avoidable on reuse |

- **STARTTLS (`imap` on 143)** is worse: it inserts a pre-TLS `CAPABILITY`, the `STARTTLS` command,
  then a post-TLS `CAPABILITY` *before* step 4 — roughly **6–8 setup RTTs** instead of 4–6.
- **Setup (steps 1–5, 8): ~4–6 RTT (imaps) / ~6–8 RTT (STARTTLS).**
- **Intrinsic folder work (steps 6–7): 2 RTT.**

So the connection setup is the **majority** of the round-trips on every open, and it is exactly the
part a reused connection would skip. Illustratively, at an RTT of *R*: a cold open ≈ `(4–6)·R` setup +
`2·R` work; a warm (reused-connection) open ≈ `2·R`. The setup share — everything except the
`EXAMINE + FETCH` — is what a fix removes from the 2nd open onward.

### Compounding across operations

Because the pattern is per-operation, costs stack:

- **Folder switch A → B → A:** 3 folder-opens ⇒ 3 full `CONNECT + TLS + LOGIN` setups.
- **List then open a message:** `fetchRecent` (open) + `fetchBodyMarkingSeen` (read) ⇒ 2 full setups,
  even though the read targets the folder just listed (proven by the test below).
- **Prefetch after a sync** (`MailSyncer.prefetchIfEnabled`, FetchPolicy territory #88–#90, *not*
  changed here): each unfetched message body is another `withStore` connection, and each attachment
  another still. A folder-open that triggers prefetch of *K* messages can open `1 + K + attachments`
  separate authenticated connections. This amplifies the motivation for pooling but is out of scope.

## Deterministic evidence (no real network needed)

`ImapFolderOpenLatencyTest` routes `ImapClient` through `CountingImapProxy` — a localhost TCP proxy
that forwards a cleartext IMAP session to in-process GreenMail while counting connections and parsing
IMAP command words. This measures the *structure* exactly, without needing real latency:

- `each folder-open establishes a brand-new IMAP connection (no reuse today)` — N opens ⇒ **N** TCP
  connections.
- `each folder-open pays a fresh LOGIN and its own SELECT` — N opens ⇒ **N** `LOGIN` **and** N
  `EXAMINE` (the avoidable auth vs. the necessary select).
- `a single folder-open's round-trip sequence is CONNECT-LOGIN-EXAMINE-FETCH-LOGOUT` — pins the
  sequence: 1 connection, 1 `LOGIN`, 1 `EXAMINE`, ≥1 `FETCH`, 1 `LOGOUT`.
- `opening a folder then reading a message uses two separate connections (compounding cost)` — list +
  read ⇒ **2** connections and **2** `LOGIN`s.

These assertions encode the *current* (no-reuse) behaviour and double as the **validation harness for a
future fix**: once a connection is reused across folder switches, the connection/auth counts drop below
the operation count — flip the expectations to assert reuse and the same real-IMAP tests confirm the win.

## Recommended mitigation: per-account connection reuse / keep-alive

Keep one authenticated `Store` alive per account and reuse it across folder-opens and message
operations instead of `withStore`'s connect-per-call, so only the first operation pays setup and
subsequent ones pay just `EXAMINE + FETCH`. Design constraints that make this **non-trivial** and why
it needs measurement before landing:

1. **Must not disturb IMAP IDLE (#90).** `IdleService` already holds a *separate*, dedicated
   long-lived `Store` per account (`ImapClient.idle`, `IdleService.watchAccount`), blocking on
   `INBOX.idle()`. IMAP is serial per connection and IDLE blocks its connection, so folder-opens
   cannot be multiplexed onto it. A reuse pool is therefore an **additional** persistent connection
   per account (IDLE + pool), which must respect the server's per-account connection limit (Gmail
   ~15; many servers 3–5) — a budget `ImapClient.idle`'s own comment already flags.
2. **Thread-safety.** `MailRepositoryImpl`'s UI operations (`openMessage`, `setStarred`,
   `deleteMessage`, `moveMessages`, `setFlag`, …) are **not** serialized and can overlap `MailSyncer`
   (whose `prefetchIfEnabled` deliberately runs *outside* `syncMutex` so downloads don't block
   pull-to-refresh). Today's connect-per-call sidesteps this. A shared connection needs its own
   discipline: a single mutex-guarded connection (simplest, but head-of-line-blocks a flag toggle
   behind a slow body download — a regression of the current concurrency) **or** a small bounded pool
   of N connections (more throughput, needs a size cap + eviction). Choosing between them is a
   latency/throughput trade-off that needs real measurement.
3. **Stale-connection handling.** A pooled socket can be dropped by the server's idle timeout
   (RFC-permitted), NAT rebinding, or a network change. Reuse must detect staleness — a `NOOP` probe
   (adds 1 RTT, partly defeating the point) or catch-and-retry-once on a fresh connection — behaviour
   best validated against real servers and real network transitions.
4. **Battery / lifecycle (#88/#89/#90).** Holding a socket open has a battery cost; #90 already tears
   IDLE down at low battery. A reuse pool needs an idle-eviction timeout and should likely mirror that
   low-battery posture. The right timeout is a battery-vs-latency trade-off that needs device
   measurement.

Because every one of these knobs (mutex vs. pool, eviction timeout, stale-probe strategy, battery
posture) trades latency against battery/complexity and can only be tuned with a real network and a
real device — which this environment cannot provide — forcing an implementation now would be guessing.
Per #125's "investigation/spike first" guidance, this change ships the measurement harness + analysis
and **defers the pool to a measured follow-up**.

> **Follow-up spike.** A flag-gated (default OFF) prototype of this reuse now exists, with the harness
> flipped to prove it collapses `N` opens to one connection / one LOGIN. See
> [`issue-125-connection-reuse-spike.md`](issue-125-connection-reuse-spike.md) for the prototype
> design, the flag-off-vs-on proof, and the per-decision trade-offs.

**Already correct — do not redo.** Optimistic render-from-cache is already the architecture
(`selectFolder` renders cached rows instantly; the network sync is a background refresh). #125's
"optimistic render while the network catches up" is satisfied; only connection reuse remains.

## What a maintainer needs to fully close #125 (real device + real account)

1. **Instrument the open.** Add timing around `syncFolder → fetchRecent → store.connect / open / fetch
   / close` (or enable Angus `mail.imap` debug) and capture on a real Gmail/Outlook account over both
   Wi-Fi and cellular.
2. **Attribute the wall-clock.** Break each open into connect (TCP+TLS), login, `EXAMINE`, `FETCH`,
   `LOGOUT`; confirm the hypothesis that connection setup dominates and quantify its share.
3. **A/B the pool behind a flag.** Measure folder-switch latency (open A → open B → back to A) and
   list-then-open-message latency, cold vs. warm-reuse, on the same accounts/networks. Expect warm
   opens to fall by the connection-setup share.
4. **Battery check.** Measure the kept-alive socket's idle cost against candidate eviction timeouts;
   confirm no regression versus the #88/#89/#90 posture.
5. **Resilience check.** Force server idle-timeout and network transitions; confirm transparent
   reconnect with no user-visible failures, and that IDLE + pool stay within the per-account limit.
6. **Lock it in.** Flip `ImapFolderOpenLatencyTest` to assert reuse (connection/auth counts < operation
   count) as the deterministic regression guard.
