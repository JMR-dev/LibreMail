// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

/**
 * How severe a provider's throttling response is, which sets how long the reactive backoff waits
 * before the offending activity may touch that account again (issue #360).
 */
enum class ThrottleKind {
    /**
     * A transient rate / connection / bandwidth limit — an IMAP `[THROTTLED]` / "Too many requests"
     * NO, an HTTP 429, or "too many simultaneous connections". Recoverable after a short, exponentially
     * growing backoff: the provider is asking us to slow down, not shutting us out.
     */
    RATE_LIMIT,

    /**
     * A provider lockout — e.g. Yahoo's ~1-hour auth lock after repeated logins, or an explicit
     * "account temporarily locked / suspended". A hard signal to stop retrying for a long window; a
     * tight retry loop here only prolongs (or re-arms) the lock.
     */
    LOCKOUT,
}

/**
 * A classified provider throttling response — the output of [ThrottleClassifier] and the input to
 * [AccountThrottleGate]. Deliberately carries no PII (no host, address, or server body): only the
 * [kind] and, when the provider gave a machine-readable minimum wait (e.g. an HTTP `Retry-After`
 * header — [retryAfterMillis]), a number of milliseconds. IMAP/SMTP throttling rarely carries a
 * `Retry-After`, so [retryAfterMillis] is usually null and the exponential schedule alone applies.
 */
data class ThrottleSignal(
    val kind: ThrottleKind,
    /** Provider-suggested minimum wait in ms (e.g. Graph `Retry-After`), or null when none was given. */
    val retryAfterMillis: Long? = null,
)
