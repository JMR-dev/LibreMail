// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.MailProvider
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [IcloudConnectionLimiter] (issue #363) must cap an iCloud account's concurrent connections at its
 * configured limit — a caller past the cap waits for a free permit rather than failing — isolate that
 * budget per account, release a stuck permit even when the guarded block throws, and pass every other
 * provider's account straight through, unconstrained.
 *
 * Deliberately real-dispatcher (`runBlocking` + `Dispatchers.Default`), not coroutines-test virtual time:
 * the behaviour under test is real suspension shared across two independently launched coroutines via a
 * [kotlinx.coroutines.sync.Semaphore], which a virtual clock cannot observe. Mirrors
 * [org.libremail.data.sync.BackfillPacerTest]'s "composes with" tests and the #355
 * `InteractiveImapGateInstrumentedTest` park/resume idiom.
 */
class IcloudConnectionLimiterTest {

    private val logBuffer = RingLogBuffer()

    private val icloud = MailProvider.ICLOUD.createAccount("me@icloud.com")
    private val gmail = MailProvider.GMAIL.createAccount("me@gmail.com")

    @Before
    fun setUp() {
        // AppLog forwards to android.util.Log, a throwing no-op stub under plain JVM unit tests; fully
        // qualified (no import) per the ForbiddenImport style already used by GraphSenderSendTest.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a second caller waits for the first to release when the cap is one`() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            limiter.withPermit(icloud) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val secondEntered = CompletableDeferred<Unit>()
        val second = launch(Dispatchers.Default) {
            limiter.withPermit(icloud) { secondEntered.complete(Unit) }
        }
        delay(PARK_PROBE_MS)
        assertFalse(secondEntered.isCompleted, "the second caller must wait for the permit")

        release.complete(Unit)
        withTimeout(HAND_OFF_TIMEOUT_MS) { secondEntered.await() }
        holder.join()
        second.join()
    }

    @Test
    fun `up to the cap runs concurrently without waiting`() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 2)
        val entered1 = CompletableDeferred<Unit>()
        val entered2 = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) {
            limiter.withPermit(icloud) {
                entered1.complete(Unit)
                release.await()
            }
        }
        val second = launch(Dispatchers.Default) {
            limiter.withPermit(icloud) {
                entered2.complete(Unit)
                release.await()
            }
        }

        // Both entering (rather than one waiting on the other) proves the cap of 2 admits 2 at once.
        withTimeout(HAND_OFF_TIMEOUT_MS) {
            entered1.await()
            entered2.await()
        }

        release.complete(Unit)
        first.join()
        second.join()
    }

    @Test
    fun `every other provider runs unconstrained even while the cap is fully held`() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 1)
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            limiter.withPermit(icloud) { release.await() }
        }

        // A Gmail account must never wait behind the iCloud-only cap — a regression would hang this.
        val ran = withTimeout(HAND_OFF_TIMEOUT_MS) { limiter.withPermit(gmail) { true } }

        assertTrue(ran)
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `two iCloud accounts each get their own budget`() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 1)
        val other = MailProvider.ICLOUD.createAccount("someone-else@icloud.com")
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            limiter.withPermit(icloud) { release.await() }
        }

        // A different account's own single permit is untouched by the first account's held permit.
        val ran = withTimeout(HAND_OFF_TIMEOUT_MS) { limiter.withPermit(other) { true } }

        assertTrue(ran)
        release.complete(Unit)
        holder.join()
    }

    @Test
    fun `the permit releases even when the guarded block throws`() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 1)

        val failed = runCatching { limiter.withPermit(icloud) { throw IllegalStateException("boom") } }

        assertTrue(failed.isFailure)
        // A regression would hang here forever waiting on the "stuck" permit instead of acquiring it.
        val ran = withTimeout(HAND_OFF_TIMEOUT_MS) { limiter.withPermit(icloud) { true } }
        assertTrue(ran)
    }

    @Test
    fun `waiting for a full cap logs a PII-free breadcrumb`() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            limiter.withPermit(icloud) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val second = launch(Dispatchers.Default) { limiter.withPermit(icloud) { } }
        delay(PARK_PROBE_MS)

        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.any { it.contains("connection cap reached") }, "messages=$messages")
        messages.forEach { assertFalse(it.contains("icloud.com"), it) }

        release.complete(Unit)
        holder.join()
        second.join()
    }

    @Test
    fun `production wiring uses the documented conservative cap`() {
        assertEquals(5, IcloudConnectionLimiter.MAX_CONCURRENT_CONNECTIONS)
    }

    private companion object {
        /** Slack given to a parked waiter to (wrongly) resume before we assert it is still parked. */
        const val PARK_PROBE_MS = 300L

        /** Generous bound for a permit hand-off; only a real park/resume regression approaches it. */
        const val HAND_OFF_TIMEOUT_MS = 5_000L
    }
}
