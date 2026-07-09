// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.domain.model.Account
import org.libremail.domain.model.MailProvider

/**
 * On-device proof of issue #361's Gmail bandwidth pacing across the CI API matrix. Production feeds
 * [GmailBandwidthTracker] from `MailRepositoryImpl.prefetchMessage`, which can race across
 * concurrently-syncing accounts under the real dispatcher, so this proves the tracker's
 * concurrent-map-backed accounting holds up under genuine concurrent updates on real threads rather
 * than coroutines-test virtual time (the JVM [GmailBandwidthTrackerTest] covers the day-rollover and
 * threshold-crossing logic in detail). Also proves [GmailSyncLimits.appliesTo] resolves the real
 * [MailProvider] presets identically to production. Deliberately mock-free — no `mockk`, no framework
 * `Context` — the tracker's only collaborator is the real wall clock (mirrors
 * `BackfillPacerInstrumentedTest`'s mock-free idiom).
 */
@RunWith(AndroidJUnit4::class)
class GmailBandwidthTrackerInstrumentedTest {

    @Test
    fun concurrentDownloadsForOneAccountAllLandWithoutLosingAnUpdate() = runBlocking {
        val tracker = GmailBandwidthTracker()
        val perTask = 1_000L

        val jobs = (1..CONCURRENT_TASKS).map {
            async(Dispatchers.Default) { tracker.recordDownload("acct", perTask) }
        }
        jobs.awaitAll()

        assertEquals(CONCURRENT_TASKS * perTask, tracker.bytesDownloadedToday("acct"))
    }

    @Test
    fun accountsStayIsolatedUnderConcurrentRecording() = runBlocking {
        val tracker = GmailBandwidthTracker()

        val heavy = async(Dispatchers.Default) {
            tracker.recordDownload("heavy", GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES)
        }
        val light = async(Dispatchers.Default) { tracker.recordDownload("light", 1L) }
        heavy.await()
        light.await()

        assertTrue(tracker.isOverDailyBudget("heavy"))
        assertFalse(tracker.isOverDailyBudget("light"))
    }

    @Test
    fun appliesToResolvesTheRealGmailPresetOnDevice() {
        val gmail = MailProvider.GMAIL.createAccount("user@gmail.com")
        val outlook = Account.outlook("user@outlook.com")

        assertTrue(GmailSyncLimits.appliesTo(gmail))
        assertFalse(GmailSyncLimits.appliesTo(outlook))
    }

    private companion object {
        const val CONCURRENT_TASKS = 50
    }
}
