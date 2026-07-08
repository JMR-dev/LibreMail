// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ThrottleClassifier] must recognize provider throttling / lockout wording across IMAP, SMTP, and
 * HTTP — and, just as importantly, must NOT flag an ordinary auth failure, the "IMAP disabled" state
 * (issue #390), or a plain network error, since a false positive would silently stall an account for
 * up to hours.
 */
class ThrottleClassifierTest {

    private fun classify(message: String?) = ThrottleClassifier.classify(RuntimeException(message))

    // --- rate-limit positives -------------------------------------------------------------------

    @Test
    fun `gmail too-many-simultaneous-connections is a rate limit`() {
        assertEquals(
            ThrottleSignal(ThrottleKind.RATE_LIMIT),
            classify("A3 NO [ALERT] Too many simultaneous connections. (Failure)"),
        )
    }

    @Test
    fun `an imap THROTTLED response code is a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, classify("* BYE [THROTTLED] slow down")?.kind)
    }

    @Test
    fun `too many requests is a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, classify("Server said: Too Many Requests")?.kind)
    }

    @Test
    fun `an rfc5530 UNAVAILABLE response code is a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, classify("NO [UNAVAILABLE] System temporarily overloaded")?.kind)
    }

    @Test
    fun `an rfc5530 LIMIT response code is a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, classify("NO [LIMIT] too much")?.kind)
    }

    @Test
    fun `an smtp too-many-messages rejection is a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, classify("421 4.7.0 Too many messages, try later")?.kind)
    }

    @Test
    fun `an http 429 that surfaced as text is a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, classify("Graph sendMail failed (HTTP 429): quota")?.kind)
    }

    @Test
    fun `an explicit rate-limit phrase is a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, classify("Request was rate-limited by the provider")?.kind)
    }

    // --- lockout positives ----------------------------------------------------------------------

    @Test
    fun `a temporarily-locked account is a lockout`() {
        assertEquals(ThrottleKind.LOCKOUT, classify("Your account has been temporarily locked")?.kind)
    }

    @Test
    fun `a suspended account is a lockout`() {
        assertEquals(ThrottleKind.LOCKOUT, classify("This account is temporarily suspended")?.kind)
    }

    @Test
    fun `too many login attempts is a lockout`() {
        assertEquals(ThrottleKind.LOCKOUT, classify("Login failed: too many login attempts")?.kind)
    }

    @Test
    fun `a message naming both a lock and a rate limit prefers the longer lockout`() {
        assertEquals(ThrottleKind.LOCKOUT, classify("Too many requests; account locked")?.kind)
    }

    // --- negatives (must NOT be throttling) -----------------------------------------------------

    @Test
    fun `a wrong-password auth failure is not throttling`() {
        assertNull(classify("A2 NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)"))
    }

    @Test
    fun `the imap-disabled state is not throttling`() {
        // Issue #390's actionable "turn on IMAP" case must stay distinct from a throttle/lockout.
        assertNull(classify("Your account is not enabled for IMAP use. Please enable IMAP."))
    }

    @Test
    fun `an ordinary network error is not throttling`() {
        assertNull(ThrottleClassifier.classify(IOException("Connection refused")))
    }

    @Test
    fun `a not-found error is not throttling`() {
        assertNull(classify("Message 42 not found"))
    }

    @Test
    fun `a null or blank message is not throttling`() {
        assertNull(classify(null))
        assertNull(classify("   "))
    }

    // --- cause chain ----------------------------------------------------------------------------

    @Test
    fun `throttle wording nested in a cause is still classified`() {
        val wrapped = RuntimeException("sync failed", IOException("[THROTTLED] too many requests"))
        assertEquals(ThrottleKind.RATE_LIMIT, ThrottleClassifier.classify(wrapped)?.kind)
    }

    @Test
    fun `a cyclic cause chain terminates and classifies`() {
        val a = RuntimeException("Too many requests")
        val b = RuntimeException("wrapper", a)
        a.initCause(b) // cycle: a -> b -> a
        assertEquals(ThrottleKind.RATE_LIMIT, ThrottleClassifier.classify(b)?.kind)
    }

    // --- http status entry point ----------------------------------------------------------------

    @Test
    fun `http 429 maps to a rate limit and honors a parsed retry-after`() {
        assertEquals(
            ThrottleSignal(ThrottleKind.RATE_LIMIT, retryAfterMillis = 120_000L),
            ThrottleClassifier.classifyHttpStatus(429, retryAfterMillis = 120_000L),
        )
    }

    @Test
    fun `http 503 maps to a rate limit`() {
        assertEquals(ThrottleKind.RATE_LIMIT, ThrottleClassifier.classifyHttpStatus(503)?.kind)
    }

    @Test
    fun `a 2xx status is not throttling`() {
        assertNull(ThrottleClassifier.classifyHttpStatus(200))
        assertNull(ThrottleClassifier.classifyHttpStatus(401))
    }
}
