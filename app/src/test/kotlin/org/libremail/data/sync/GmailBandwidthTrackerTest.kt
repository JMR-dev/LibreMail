// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GmailBandwidthTracker] (issue #361) must accumulate an account's daily download bytes, isolate
 * accounts from one another, roll its window over at the day boundary, report the daily-budget
 * threshold accurately, and log only a PII-free, once-per-crossing breadcrumb. Mirrors
 * [AccountThrottleGateTest]'s virtual-clock idiom (a plain injected `nowMillis` instead of
 * coroutines-test virtual time, since the tracker itself is not a suspend API).
 */
class GmailBandwidthTrackerTest {

    private val logBuffer = RingLogBuffer()

    /** A manual clock for the day-rollover tests; [tracker] reads it live, so tests advance it by hand. */
    private var now = 0L

    private fun tracker() = GmailBandwidthTracker(nowMillis = { now })

    @Before
    fun setUp() {
        // AppLog forwards to android.util.Log, a throwing no-op stub under plain JVM unit tests.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `bytes accumulate across calls for the same account and day`() {
        val tracker = tracker()

        tracker.recordDownload("acct", 100L)
        tracker.recordDownload("acct", 250L)

        assertEquals(350L, tracker.bytesDownloadedToday("acct"))
    }

    @Test
    fun `an untracked account reports zero bytes and is not over budget`() {
        val tracker = tracker()

        assertEquals(0L, tracker.bytesDownloadedToday("acct"))
        assertFalse(tracker.isOverDailyBudget("acct"))
    }

    @Test
    fun `crossing the daily download budget marks the account over budget`() {
        val tracker = tracker()

        tracker.recordDownload("acct", GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES - 1)
        assertFalse(tracker.isOverDailyBudget("acct"), "one byte under budget must not trip it")

        tracker.recordDownload("acct", 1L)
        assertTrue(tracker.isOverDailyBudget("acct"), "reaching the budget exactly must trip it")
    }

    @Test
    fun `a tracked account never affects another account's budget`() {
        val tracker = tracker()

        tracker.recordDownload("heavy", GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES)

        assertTrue(tracker.isOverDailyBudget("heavy"))
        assertFalse(tracker.isOverDailyBudget("light"))
        assertEquals(0L, tracker.bytesDownloadedToday("light"))
    }

    @Test
    fun `a new day resets the tracked total instead of carrying it forward`() {
        val tracker = tracker()
        val oneDayMs = 24 * 60 * 60 * 1000L

        tracker.recordDownload("acct", GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES)
        assertTrue(tracker.isOverDailyBudget("acct"))

        now += oneDayMs
        assertFalse(tracker.isOverDailyBudget("acct"), "a new day must clear yesterday's total")
        assertEquals(0L, tracker.bytesDownloadedToday("acct"))

        tracker.recordDownload("acct", 10L)
        assertEquals(10L, tracker.bytesDownloadedToday("acct"), "today's total starts fresh, not carried over")
    }

    @Test
    fun `recording zero or negative bytes is a no-op`() {
        val tracker = tracker()

        tracker.recordDownload("acct", 0L)
        tracker.recordDownload("acct", -5L)

        assertEquals(0L, tracker.bytesDownloadedToday("acct"))
    }

    @Test
    fun `crossing the budget logs exactly once and stays PII-free`() {
        val tracker = tracker()
        val accountId = "imap:user@example.org"

        tracker.recordDownload(accountId, GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES) // crosses
        tracker.recordDownload(accountId, 10L) // still over budget; must not log again

        val messages = logBuffer.snapshot().map { it.message }
        assertEquals(1, messages.count { it.contains("daily download budget reached") })
        messages.forEach { assertFalse(it.contains("user@example.org"), it) }
    }

    @Test
    fun `staying under budget never logs`() {
        val tracker = tracker()

        tracker.recordDownload("acct", GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES - 1)

        assertTrue(logBuffer.snapshot().isEmpty())
    }
}
