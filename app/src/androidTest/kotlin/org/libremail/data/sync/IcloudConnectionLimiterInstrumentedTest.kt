// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.domain.model.MailProvider

/**
 * On-device proof of issue #363's iCloud connection cap, on the REAL Android coroutine runtime (not
 * coroutines-test virtual time) across the CI API matrix. A real, production-wired [IcloudConnectionLimiter]
 * (its `@Inject` constructor, so this also pins the production cap — mirrored here as [PRODUCTION_CAP]) —
 * the per-account permit gate [MailBackfiller] consults around every connection it opens for an iCloud
 * account — must:
 *
 * - let up to the cap run concurrently with no waiting;
 * - make a caller past the cap wait for a live in-flight one to release, then resume it;
 * - never gate a non-iCloud account, even while an iCloud account's cap is fully held.
 *
 * Deliberately mock-free (no `mockk`, no framework `Context`) and uses only the public production
 * constructor (no internal test-only constructor, which — unlike the JVM `test` source set —
 * `androidTest` cannot see, mirroring [BackfillPacerInstrumentedTest]'s own hardcoded-cap idiom): the
 * limiter is the whole synchronisation primitive #363 adds, so exercising it directly is both the
 * faithful behavioural test and the most portable across API 29-37. The JVM `IcloudConnectionLimiterTest`
 * / `MailBackfillerTest` cover the same contract (with a smaller configured cap for speed) plus the full
 * backfiller wiring under coroutines-test.
 */
@RunWith(AndroidJUnit4::class)
class IcloudConnectionLimiterInstrumentedTest {

    private val icloudAccount = MailProvider.ICLOUD.createAccount("me@icloud.com")
    private val gmailAccount = MailProvider.GMAIL.createAccount("me@gmail.com")

    @Test
    fun aCallerPastTheCapWaitsForALivePermitThenResumesOnceItReleases() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter()

        // Exhaust every production permit for this account.
        val entered = List(PRODUCTION_CAP) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        val holders = entered.map { deferred ->
            launch(Dispatchers.Default) {
                limiter.withPermit(icloudAccount) {
                    deferred.complete(Unit)
                    release.await()
                }
            }
        }
        entered.forEach { it.await() }

        val extraEntered = CompletableDeferred<Unit>()
        val extra = launch(Dispatchers.Default) {
            limiter.withPermit(icloudAccount) { extraEntered.complete(Unit) }
        }
        delay(PARK_PROBE_MS)
        assertFalse("a caller past the cap must wait while every permit is held", extraEntered.isCompleted)

        release.complete(Unit)
        withTimeout(HAND_OFF_TIMEOUT_MS) { extraEntered.await() }
        holders.forEach { it.join() }
        extra.join()
    }

    @Test
    fun aNonIcloudAccountIsNeverGatedEvenWhileTheIcloudCapIsFullyHeld() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter()
        val entered = List(PRODUCTION_CAP) { CompletableDeferred<Unit>() }
        val release = CompletableDeferred<Unit>()
        val holders = entered.map { deferred ->
            launch(Dispatchers.Default) {
                limiter.withPermit(icloudAccount) {
                    deferred.complete(Unit)
                    release.await()
                }
            }
        }
        entered.forEach { it.await() }

        // A regression that gated every provider on one shared cap would hang this withTimeout.
        val ran = withTimeout(HAND_OFF_TIMEOUT_MS) { limiter.withPermit(gmailAccount) { true } }

        assertTrue("a non-iCloud account must never wait behind the iCloud-only cap", ran)
        release.complete(Unit)
        holders.forEach { it.join() }
    }

    @Test
    fun thePermitReleasesEvenWhenTheGuardedBlockThrowsSoNoCallerIsStrandedWaiting() = runBlocking<Unit> {
        val limiter = IcloudConnectionLimiter()

        val failed = runCatching { limiter.withPermit(icloudAccount) { throw IllegalStateException("boom") } }
        assertTrue("the failing block propagates to the caller", failed.isFailure)

        // A regression would hang here forever instead of acquiring the "stuck" permit.
        val ran = withTimeout(HAND_OFF_TIMEOUT_MS) { limiter.withPermit(icloudAccount) { true } }
        assertTrue("a failed block must not strand its permit held", ran)
    }

    private companion object {
        /**
         * Mirrors [IcloudConnectionLimiter.MAX_CONCURRENT_CONNECTIONS] (`internal`, so not a symbolic
         * reference — see the class doc). A production regression to that constant would only make this
         * test over- or under-exhaust the cap, not silently pass, since every permit is awaited by name.
         */
        const val PRODUCTION_CAP = 5

        /** Slack given to a parked waiter to (wrongly) resume before we assert it is still parked. */
        const val PARK_PROBE_MS = 300L

        /** Generous bound for the permit hand-off; only a real park/resume regression approaches it. */
        const val HAND_OFF_TIMEOUT_MS = 5_000L
    }
}
