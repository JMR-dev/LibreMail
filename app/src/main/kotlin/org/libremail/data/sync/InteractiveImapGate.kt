// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide priority signal (issue #355) that lets an interactive, user-facing IMAP fetch — opening a
 * message, loading its inline images, downloading an attachment, building a reply — pre-empt the
 * continuous full-history backfill ([MailBackfiller], #12).
 *
 * The reader's on-demand body fetch and the backfill share no socket ([org.libremail.mail.ImapClient] is
 * connect-per-operation) and no in-process lock, so a sustained backfill keeps the account near its
 * per-account IMAP throttle/bandwidth ceiling and the un-prioritised reader fetch queues behind it for
 * tens of seconds (the 2026-07-05 Pixel 10 Pro XL perf run: avg 48s to open an uncached message). This
 * gate fixes that cooperatively, with no thread priorities:
 *
 * - The interactive paths ([org.libremail.data.repository.MailRepositoryImpl]) wrap their work in
 *   [withInteractive], which raises an in-flight counter for the duration of the block and *always*
 *   lowers it again — even when the block throws — so a failed fetch can never strand the counter above
 *   zero and permanently starve backfill.
 * - Backfill calls [awaitInteractiveIdle] at its natural per-page yield point and *parks* (suspends)
 *   while the counter is non-zero, resuming the instant it returns to zero. A page already in flight
 *   finishes; the interactive fetch simply wins the next server round-trip (best-effort — #356 bounds the
 *   case where the open lands mid-page).
 *
 * A [counter][activeInteractiveCount] rather than a `Mutex` is deliberate: several interactive fetches
 * (e.g. an open plus its inline images) may overlap and must run concurrently — only *backfill* yields to
 * them, and only once they have *all* cleared. State lives only in-process (a [Singleton]); a restart
 * resets it, which is fine — nothing is parked across process death.
 *
 * This gate is orthogonal to [AccountThrottleGate] (#360): that one makes background work back off after a
 * provider *rejects* it; this one makes background work yield to a *foreground* fetch pre-emptively.
 */
@Singleton
class InteractiveImapGate @Inject constructor() {

    private val activeCount = MutableStateFlow(0)

    /** Interactive IMAP fetches currently in flight; `0` means background backfill may proceed. */
    val activeInteractiveCount: StateFlow<Int> = activeCount.asStateFlow()

    /** True while at least one interactive fetch holds the gate, so background work should yield to it. */
    fun isInteractiveActive(): Boolean = activeCount.value > 0

    /**
     * Runs [block] as an interactive IMAP fetch: raises the in-flight counter before it starts and lowers
     * it in a `finally`, so backfill yields for the whole duration and is released even when [block] throws
     * — the caller's `runCatching` still observes the original throwable, and backfill never deadlocks on an
     * errored fetch.
     */
    suspend fun <T> withInteractive(block: suspend () -> T): T {
        enter()
        try {
            return block()
        } finally {
            exit()
        }
    }

    /**
     * Suspends until no interactive fetch is in flight (returns immediately when already idle). A
     * [StateFlow] always replays its latest value to a new collector, so this can never miss a wake-up: if
     * the counter is already zero it returns at once, otherwise it resumes the instant the last fetch
     * releases the gate.
     */
    suspend fun awaitInteractiveIdle() {
        activeCount.first { it == 0 }
    }

    private fun enter() = activeCount.update { it + 1 }

    // coerceAtLeast(0) is defence in depth: withInteractive always pairs enter/exit, so the counter can't
    // legitimately go negative — but a stray unbalanced exit must never drive it below zero and wedge
    // awaitInteractiveIdle's `== 0` predicate forever.
    private fun exit() = activeCount.update { (it - 1).coerceAtLeast(0) }
}
