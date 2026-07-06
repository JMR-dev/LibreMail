<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# IMAP connection-reuse spike (issue #125)

> **Update — shipped (issue #357 Part 2).** The real-device validation this spike deferred has since
> run: an on-device drilldown proved Gmail server-side throttles LibreMail's connect-per-operation IMAP
> (full-history backfill generated ~601 connections in ~22 min, tripping and sustaining a per-account
> rate/bandwidth clamp; `live` peaked at only 5, so it is connection *volume*, not count). Connection
> reuse is therefore now **ON by default**, gated by `BuildConfig.IMAP_CONNECTION_REUSE` as a safety
> switch, with the cache hardened for production: transparent stale-connection recovery, idle eviction
> (`ImapConnectionCache.evictIdle`, swept by `IdleService`), low-battery teardown, and per-account
> mutex concurrency. The single-connection-vs-pool and per-provider-cap knobs below remain a separate
> effort (#356/#360-#364); this change is connection reuse only. The sections below are the original
> spike design, kept for context.

A time-boxed spike that **prototypes** the connection reuse the investigation
(`issue-125-imap-folder-open.md`) recommended and defers. It exists to reduce uncertainty — *is
per-account keep-alive feasible in this codebase, and does it actually collapse the per-open setup
cost?* — not to ship a finished feature. The prototype is **flag-gated and OFF by default**, so it
cannot change `main`'s behaviour, and the win is proven structurally with the existing GreenMail
harness.

> **Still no wall-clock numbers.** As in the investigation, everything here counts *protocol
> round-trips* (deterministic in-process) and TCP connections. Localhost GreenMail is ~0 RTT, so this
> spike proves the connection is **reused** (structure), not how many milliseconds that saves (that is
> the real-device work in the last section). No latency figure is fabricated.

## What the spike delivers

1. A flag-gated per-account keep-alive **prototype** — `ImapConnectionCache` + an OFF-by-default
   `reuseConnections` flag on `ImapClient`.
2. **Deterministic proof it reuses the connection** — two new `ImapFolderOpenLatencyTest` cases that
   flip the flag on and assert the connection/LOGIN counts collapse, run against real in-process IMAP.
3. This design note: the prototype's stance on each real design decision, and the refined real-device
   validation plan.

## The prototype

### The flag (default OFF, cannot destabilize `main`)

`ImapClient`'s production constructor is unchanged in behaviour:

```kotlin
class ImapClient(private val reuseConnections: Boolean) {
    @Inject constructor() : this(reuseConnections = false) // production: reuse OFF
    ...
}
```

Hilt still calls the no-arg `@Inject` constructor, so every production/`ImapClient()` call site gets
`reuseConnections = false`. With the flag off, `withStore` is byte-for-byte the previous
connect-per-call + `LOGOUT`-per-call code, the reuse cache is **never allocated**, and no new state or
code path is reachable. Only the harness opts in, via `ImapClient(reuseConnections = true)`. When
real-device validation confirms the win, this flag is what gets wired to a setting / `BuildConfig`.

### The reused connection — `ImapConnectionCache`

`ImapConnectionCache` keeps one authenticated `jakarta.mail.Store` alive per account and lends it out:

- **One connection per account, mutex-guarded.** Each account key owns a single `Store` behind its own
  coroutine `Mutex`; `withStore` locks it, ensures the `Store` is connected (creating it on first use),
  runs the operation, and returns **without closing it**. Angus's `IMAPStore` internally pools the
  authenticated connection across folder `open()`/`close()`, so a kept-alive `Store` reuses one socket;
  the reused store is pinned to `connectionpoolsize=1` + `separatestoreconnection=false` so it is
  provably a single socket.
- **Keyed by connection identity, not the secret.** The key is
  `host|port|security|username|useXoauth2` — deliberately **excluding** `secret`, so a rotated OAuth
  access token reuses the same live, already-authenticated socket instead of orphaning it. The current
  `params` (with the fresh secret) is always passed to `connect`, so a genuine reconnect uses the new
  token.
- **Lazy, catch-and-retry-once stale handling.** No periodic `NOOP` probe (that would add a round-trip
  to *every* reused op, partly defeating the point). An operation runs optimistically; if it throws a
  dropped-connection signal (`FolderClosedException`, `StoreClosedException`, or a `MessagingException`
  caused by `IOException`), the socket is rebuilt once and the op retried. A non-connection error
  (e.g. "message not found") is never retried.
- **`closeReusedConnections()`** evicts everything (`LOGOUT` + teardown). Today it is the *only*
  eviction, driven by the harness; a shipped feature would also drive it from an idle timer and the
  low-battery push teardown.

IDLE is untouched: `ImapClient.idle` still opens its own dedicated long-lived `Store` (it is *not* in
the cache), so the reuse connection is strictly **additional** to the IDLE connection — which is
exactly why the per-account connection budget below is a first-class concern.

## Deterministic proof (the harness, flag off vs on)

`ImapFolderOpenLatencyTest` routes `ImapClient` through `CountingImapProxy` (a localhost TCP proxy in
front of GreenMail that counts TCP connections and parses IMAP command words). The existing cases pin
the flag-**off** behaviour; the two new cases flip the flag **on** over the *same* real IMAP
operations. For `N = OPENS = 3` folder-opens:

| Scenario | TCP connections | LOGIN | EXAMINE (per open) | LOGOUT |
|----------|-----------------|-------|--------------------|--------|
| **Flag OFF** — `N` folder-opens | `N` (=3) | `N` (=3) | `N` (=3) | `N` (=3) |
| **Flag ON** — `N` folder-opens | **1** | **1** | `N` (=3) | **1** (at eviction) |
| **Flag OFF** — open folder + read a message | 2 | 2 | (1 EXAMINE + 1 SELECT) | 2 |
| **Flag ON** — open folder + read a message | **1** | **1** | (1 EXAMINE + 1 SELECT) | **1** |

The avoidable setup — `CONNECT + TLS + LOGIN` and the trailing `LOGOUT` — drops from *once per
operation* to *once per account, ever*, while the intrinsic per-folder `EXAMINE` is unchanged. That
divergence (operations ≫ connections/LOGINs) **is** connection reuse, proven against a real IMAP
server. These flag-on assertions are also the regression guard the investigation asked for: they fail
if reuse ever silently regresses to connect-per-call.

All six cases pass on the JVM fast gate (`:app:testDebugUnitTest`); no emulator needed.

## Real design decisions — the prototype's stance and the trade-offs

The spike takes the **simplest defensible** position on each knob and leaves the tuning to
measurement. Each is a genuine latency/battery/complexity trade-off that localhost cannot settle.

| Decision | Prototype's stance | Trade-off / what's left open |
|----------|-------------------|------------------------------|
| **Single connection vs. bounded pool** | Single mutex-guarded connection per account. | Simplest and provably one socket, but **head-of-line blocking**: a quick flag toggle can queue behind a slow body download — a regression of today's connect-per-call concurrency. A bounded pool (N sockets + a size cap) restores parallelism at the cost of more sockets and eviction bookkeeping. Which wins needs real throughput/latency measurement. |
| **Idle-eviction timeout** | None yet; a connection lives until `closeReusedConnections()`. | A kept-alive socket has a battery cost (below). The right idle timeout is a battery-vs-latency trade-off; the hook exists (`closeReusedConnections`) but no timer drives it. |
| **Stale-connection detection** | Lazy catch-and-retry-once on a dropped-connection signal; no `NOOP` probe. | Retry avoids a per-op probe RTT but means one operation *fails then recovers* when a stale socket is first used; a `NOOP` pre-check trades that for a guaranteed extra RTT on every op. For a **mutating** op, an automatic retry after a mid-flight drop is at-least-once — safe for the read-only folder-open target, but a real-server correctness item for flags/move/expunge. |
| **IDLE per-account budget (#90)** | Reuse connection is **additional** to the IDLE connection (IDLE stays separate). | So an account holding IDLE **and** a reuse connection uses ≥2 persistent sockets; a bounded pool would use even more. Must stay under the server's per-account limit (Gmail ~15; many servers 3–5). A shipped version should treat IDLE + reuse (+ pool) as one budget. |
| **Concurrency (prefetch outside `syncMutex`, unserialized UI ops)** | The per-account mutex serializes *all* reuse traffic for an account. | Correct and thread-safe under the current design (concurrent UI ops + prefetch can hit the same account), but it serializes work that today runs concurrently on separate throwaway sockets — the head-of-line cost again. A pool would relax this. |
| **Low-battery posture (#88/#89/#90)** | None yet — no battery signal wired in. | A kept-alive socket has idle cost; #90 already tears IDLE down at low battery. Reuse should mirror that (evict + stop reusing at low battery). The eviction hook exists; the policy wiring is deferred. |

**Deliberately left open** (out of this spike's scope): the eviction timer, the battery-signal wiring,
the bounded-pool variant, unifying the IDLE + reuse connection budget, and the mutation-retry
idempotency review. Each needs the real-device measurement below to tune, not a guess.

## Feasibility verdict + recommendation

**Feasible, and mechanically small.** The reuse path is one ~90-line class plus a flag; the existing
concurrency model already hands us the seam (a single `withStore` chokepoint every operation flows
through), and Angus's own connection pooling does the socket reuse once we stop discarding the `Store`.
The structural win is real and now proven: setup collapses from per-operation to per-account.

**Recommendation:** keep the flag **OFF** and land this as a spike (harness + prototype + this note).
Before flipping the default on, do the real-device validation below and decide the two knobs that
localhost cannot: **single connection vs. bounded pool** (measure the head-of-line cost against real
concurrent UI-op + prefetch traffic) and the **idle-eviction timeout** (measure the kept-alive
socket's battery cost). Ship the mutation-retry idempotency review and the IDLE-budget unification
alongside. If the pool is chosen, the mutex-per-account seam generalizes to a bounded semaphore with
minimal churn.

## Real-device / real-account validation that remains

Refines the investigation's six-step plan against what *this prototype* needs:

1. **A/B the flag on real accounts/networks.** Flip `reuseConnections` on (wire it to a debug setting)
   and measure folder-switch (open A → open B → back to A) and list-then-open-message latency, cold vs.
   warm-reuse, on Gmail + Outlook over Wi-Fi and cellular. Expect warm opens to fall by the
   connection-setup share; quantify it.
2. **Attribute the wall-clock.** Instrument `store.connect` / `open` / `fetch` / `close` (or Angus
   `mail.imap` debug) and confirm setup dominates the cold open and is what reuse removes.
3. **Decide single vs. pool.** Under real concurrent traffic (UI op + prefetch on one account),
   measure the single-connection head-of-line delay; if material, prototype the bounded pool and
   re-measure.
4. **Tune idle-eviction against battery.** Measure the kept-alive socket's idle drain across candidate
   timeouts; pick one that beats the #88/#89/#90 posture, and wire `closeReusedConnections()` to that
   timer and to the low-battery teardown.
5. **Resilience + connection budget.** Force server idle-timeout and network transitions; confirm the
   catch-and-retry-once reconnect is transparent (and review mutation idempotency), and that IDLE +
   reuse (+ pool) stay under the per-account connection limit.
6. **Lock it in.** The flag-on `ImapFolderOpenLatencyTest` cases are already the deterministic
   regression guard; once the default flips on, they assert reuse can't silently regress.
