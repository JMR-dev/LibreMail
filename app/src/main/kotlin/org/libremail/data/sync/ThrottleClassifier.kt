// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

/**
 * Classifies a mail-layer failure as a **provider throttling / lockout** signal, distinct from an
 * ordinary transient error (a dropped socket, a timeout, a "message not found"). This is the shared,
 * provider-aware detection half of issue #360's reactive backoff layer: [AccountThrottleGate] turns a
 * non-null [ThrottleSignal] into an exponential, jittered per-account backoff so the app degrades
 * gracefully instead of hammering a server that just asked it to slow down (which — per the on-device
 * perf finding, `docs/perf/issue-125-*` — makes the throttle worse and can trip a lasting lockout).
 *
 * Matching is by **message text** across the whole cause chain (case-insensitive), so it works for any
 * transport (IMAP/SMTP over Jakarta Mail, or a Graph HTTP error surfaced as an exception) without
 * coupling to a specific exception type. It is deliberately **conservative**: an ordinary auth failure
 * (wrong password) or the separately-handled "IMAP is disabled" state (issue #390) must NOT be read as
 * throttling — misclassifying them would make the app back off for up to hours on a permanent error.
 * A false negative merely falls back to the existing transient-error handling; a false positive would
 * silently stall an account, so the patterns anchor on explicit throttle/lock wording.
 */
object ThrottleClassifier {

    /** HTTP 429 Too Many Requests — the canonical rate-limit status (e.g. Microsoft Graph). */
    const val HTTP_TOO_MANY_REQUESTS = 429

    /** HTTP 503 Service Unavailable — a load-shed / backoff signal, usually with a `Retry-After`. */
    const val HTTP_SERVICE_UNAVAILABLE = 503

    /**
     * Classifies [error] (and its transitive causes) into a [ThrottleSignal], or null when it is not a
     * throttling/lockout response. [ThrottleKind.LOCKOUT] is tested first so a message naming both a
     * lock and a rate limit takes the longer, safer backoff.
     */
    fun classify(error: Throwable): ThrottleSignal? {
        val text = causeChain(error)
            .mapNotNull { it.message }
            .joinToString(separator = " | ")
            .lowercase()
        if (text.isBlank()) return null
        if (LOCKOUT_PATTERNS.any { it.containsMatchIn(text) }) return ThrottleSignal(ThrottleKind.LOCKOUT)
        if (RATE_LIMIT_PATTERNS.any { it.containsMatchIn(text) }) return ThrottleSignal(ThrottleKind.RATE_LIMIT)
        return null
    }

    /**
     * Classifies an HTTP response by status code, honoring a parsed `Retry-After` when the caller has
     * one (Microsoft Graph returns it on a 429). The structured entry point for a REST transport that
     * already has the status + header in hand, complementing the text-based [classify] used by the
     * IMAP/SMTP paths. Only the throttling statuses map to a signal; everything else is null.
     */
    fun classifyHttpStatus(status: Int, retryAfterMillis: Long? = null): ThrottleSignal? = when (status) {
        HTTP_TOO_MANY_REQUESTS, HTTP_SERVICE_UNAVAILABLE ->
            ThrottleSignal(ThrottleKind.RATE_LIMIT, retryAfterMillis)
        else -> null
    }

    /**
     * The exception and its transitive causes, in order, guarding against a self-referential or cyclic
     * cause chain (identity-based visited check — [Throwable] does not override `equals`). Mirrors the
     * same-shaped walk in [org.libremail.mail.ImapAuthError].
     */
    private fun causeChain(error: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.none { it === current }) {
            seen.add(current)
            current = current.cause
        }
        return seen
    }

    /**
     * Lockout wording (matched against a lowercased message). Anchored on lock/suspend/too-many-logins
     * so a plain "AUTHENTICATE failed" wrong-password message never matches, and "IMAP is disabled"
     * (issue #390's actionable state) is deliberately excluded — that is not a throttle.
     */
    private val LOCKOUT_PATTERNS = listOf(
        // "account temporarily locked", "your account has been locked", "account locked", "lockout".
        Regex("""lock(ed|out)"""),
        // "temporarily suspended", "account suspended".
        Regex("""suspend(ed)?"""),
        // Yahoo-style repeated-login lock precursor: "too many login attempts", "too many failed logins".
        Regex("""too many (failed )?log(in|ins)"""),
    )

    /**
     * Rate-limit wording (matched against a lowercased message): explicit IMAP/SMTP throttle NOs, the
     * RFC 5530 `[LIMIT]` / `[UNAVAILABLE]` response codes, connection/request caps, and an HTTP 429 that
     * surfaced only as text. Each anchors on a rate/throttle phrase, never a bare number.
     */
    private val RATE_LIMIT_PATTERNS = listOf(
        Regex("""throttl"""), // throttled / throttling / [THROTTLED]
        Regex("""too many requests"""),
        Regex("""too many (simultaneous|concurrent) connections"""),
        Regex("""too many connections"""),
        Regex("""too many messages"""),
        Regex("""rate[ -]?limit"""),
        Regex("""\[limit\]"""),
        Regex("""\[unavailable\]"""),
        Regex("""temporarily unavailable"""),
        Regex("""service (not|un)available"""),
        Regex("""http 429"""),
        Regex("""429 too many"""),
    )
}
