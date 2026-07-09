// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.util.Log
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.BackfillProgressEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.FetchedMessage
import org.libremail.mail.ImapClient
import org.libremail.power.BatteryStatus
import org.libremail.power.BatteryStatusProvider
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.util.Date
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end backfill (issue #12) against a real in-process IMAP server with in-memory DAO fakes:
 * the backfiller caches the WHOLE mailbox (far more than the 50-message foreground window), persists
 * a resumable boundary, resumes correctly after a simulated interruption, honours the retention floor
 * (issue #13 precedence), and never deletes anything.
 */
class MailBackfillerTest {

    private lateinit var greenMail: GreenMail

    // Reuse off: this suite pins the connect-per-operation backfill behaviour it was written against.
    private val client = ImapClient(reuseConnections = false)

    private val accountEntity = AccountEntity(
        id = "acct",
        email = "alice@example.org",
        displayName = "Alice",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("127.0.0.1", 993, "NONE"),
        smtp = ServerConfigEmbedded("127.0.0.1", 465, "NONE"),
    )

    // In-memory stand-ins for the two tables the backfiller writes.
    private val cached = mutableListOf<MessageEntity>()
    private val progress = mutableMapOf<Pair<String, String>, BackfillProgressEntity>()

    // Rows offered to insertNew, counted BEFORE de-dupe: a re-fetched page inflates this even
    // though `cached` would silently absorb it. Isolates the "no message fetched twice" guarantee.
    private var totalOffered = 0

    // issue #329: AppLog breadcrumbs — a fresh buffer per test (a new instance per @Test, per JUnit4).
    private val logBuffer = RingLogBuffer()

    @Before
    fun setUp() {
        greenMail = GreenMail(ServerSetupTest.SMTP_IMAP)
        greenMail.start()
        greenMail.setUser("alice@example.org", "secret")

        // `android.util.Log` is a no-op stub under plain JVM unit tests, so it is statically mocked here
        // for the whole class, mirroring org.libremail.reporting.AppLogTest — every test now exercises
        // real backfill code, which breadcrumbs through AppLog.
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() {
        greenMail.stop()
        unmockkAll()
        DebugFetchGate.reset() // the gate is a process-global object; don't leak a pause to other tests
    }

    private fun params() = ImapConnectionParams(
        host = "127.0.0.1",
        port = greenMail.imap.port,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = "secret",
        useXoauth2 = false,
    )

    @Test
    fun `backfills the entire history over successive runs, far beyond the 50-message window`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()
        assertEquals(WINDOW, cached.size)

        val backfiller = backfiller(AccountSettings("acct"))
        // Drive it to completion; each run does a bounded slice.
        var guard = 0
        while (backfiller.runBackfill() && guard++ < 10) { /* keep going until no more work */ }

        assertEquals(TOTAL, distinctCachedUids().size, "backfill must cache every message")
        assertEquals(TOTAL - WINDOW, totalOffered, "each backfilled message fetched exactly once")
        assertTrue(cached.size > WINDOW, "that is strictly more than the foreground window")
        assertEquals(true, progress["acct" to "INBOX"]?.complete)
        assertNoDeletes()
    }

    @Test
    fun `resumes from the persisted boundary after an interruption`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()

        // First run: only ONE page, then "process death" — keep just cached rows + persisted boundary.
        val partial = backfiller(AccountSettings("acct")).runBackfill(maxBatches = 1)
        assertTrue(partial, "one page can't finish 120 messages")
        assertTrue(cached.size in (WINDOW + 1) until TOTAL)
        val boundary = progress["acct" to "INBOX"]
        assertNotNull(boundary)
        assertFalse(boundary.complete)

        // Fresh backfiller instance (new process) resumes ONLY from what was persisted.
        val resumed = backfiller(AccountSettings("acct"))
        var guard = 0
        while (resumed.runBackfill() && guard++ < 10) { /* finish */ }

        assertEquals(TOTAL, distinctCachedUids().size, "resume completes the full history")
        assertEquals(TOTAL - WINDOW, totalOffered, "no re-fetch across the interruption")
        assertEquals(TOTAL, cached.size, "and nothing is double-inserted")
        assertNoDeletes()
    }

    @Test
    fun `stops at the retention floor instead of paging the whole folder`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()

        // Keep only the newest 60: backfill pages until it has at least 60, then stops well short of 120.
        val backfiller = backfiller(AccountSettings("acct", retentionCount = 60))
        backfiller.runBackfill()
        val afterFirst = cached.size

        assertTrue(afterFirst >= 60, "must fetch at least up to the retention floor")
        assertTrue(afterFirst < TOTAL, "must NOT page the entire 120-message history")
        // Marked complete at the floor so the pruner deleting aged-out rows can't re-open paging (a
        // retention change resets progress to resume); stable — running again fetches nothing more.
        assertTrue(progress["acct" to "INBOX"]!!.complete)
        backfiller.runBackfill()
        assertEquals(afterFirst, cached.size, "at the floor, further runs must not fetch more")
        assertNoDeletes()
    }

    /**
     * Regression for the #12/#13 AGE-retention contention: reaching the age floor marks the folder
     * complete, so the pruner deleting aged-out rows — which raises the oldest cached timestamp back
     * above the cutoff — can't re-open paging. Before the fix, the next run re-fetched exactly the rows
     * the pruner had just deleted, an endless re-download/re-prune loop. (The count floor was already a
     * fixpoint, so only an age-based case exercises this.)
     */
    @Test
    fun `reaching the age floor is sticky across a prune, so backfill never re-fetches`() = runTest {
        val now = System.currentTimeMillis()
        // UID order follows append order: the first 60 are ~8 months old (beyond the 6-month cutoff),
        // the last 60 are recent (within retention).
        val old = (1..60).map { now - 8 * MONTH_MILLIS - it * DAY_MILLIS }
        val recent = (1..60).map { now - it * DAY_MILLIS }
        appendMessages(old + recent)
        seedForegroundWindow()

        val backfiller = backfiller(AccountSettings("acct", retentionMonths = 6))
        backfiller.runBackfill()

        assertEquals(
            true,
            progress["acct" to "INBOX"]!!.complete,
            "backfill marks the folder complete at the age floor",
        )
        val offeredBeforePrune = totalOffered

        // Simulate the pruner: drop every cached row older than the 6-month cutoff. This raises the
        // oldest cached timestamp back above the cutoff — the state that used to re-open paging.
        val cutoff = now - 6 * MONTH_MILLIS
        cached.removeAll { it.timestampMillis < cutoff }

        backfiller.runBackfill()

        assertEquals(offeredBeforePrune, totalOffered, "a floored folder must not re-fetch after a prune")
        assertNoDeletes()
    }

    @Test
    fun `low battery pauses the backfill content prefetch even for ALWAYS, but not header paging`() = runTest {
        appendMessages(60)
        seedForegroundWindow()

        backfiller(
            AccountSettings("acct"),
            fetchPolicy = FetchPolicy.ALWAYS,
            battery = BatteryStatus(percent = 15, isCharging = false),
        ).runBackfill()

        // #89 gates only the aggressive body/attachment prefetch; history headers keep paging in.
        assertEquals(60, distinctCachedUids().size, "header paging itself is not battery-gated")
        coVerify(exactly = 0) { requireNotNull(lastMailRepository).prefetchMessage(any()) }
    }

    // --- issue #393: debug-only fetch gate ------------------------------------------------------

    @Test
    fun `a paused prefetch gate skips body prefetch but still pages history headers`() = runTest {
        appendMessages(60)
        seedForegroundWindow()
        DebugFetchGate.pause(setOf(FetchScope.PREFETCH))

        // ALWAYS + healthy battery: without the gate this WOULD prefetch, so the gate is the only cause.
        backfiller(AccountSettings("acct"), fetchPolicy = FetchPolicy.ALWAYS).runBackfill()

        // The gate stops only the body/attachment prefetch; the history headers still page in.
        assertEquals(60, distinctCachedUids().size, "header paging itself is not gated")
        coVerify(exactly = 0) { requireNotNull(lastMailRepository).prefetchMessage(any()) }
        assertTrue(logBuffer.snapshot().any { it.message == "prefetch skipped: fetch-gate paused" })
    }

    @Test
    fun `charging at low percent keeps the backfill content prefetch running`() = runTest {
        appendMessages(60)
        seedForegroundWindow()

        backfiller(
            AccountSettings("acct"),
            fetchPolicy = FetchPolicy.ALWAYS,
            battery = BatteryStatus(percent = 15, isCharging = true),
        ).runBackfill()

        coVerify(atLeast = 1) { requireNotNull(lastMailRepository).prefetchMessage(any()) }
    }

    /**
     * Regression for #94: backfill pages by UID (arrival order) while the age floor cuts by the Date
     * header. A single message with a HIGH UID but an OLD Date (mail moved/imported into the folder)
     * used to drag the oldest cached timestamp below the cutoff and stop paging after zero pages,
     * silently gapping every older-UID message whose Date is still within retention. The floor must
     * instead be decided from the pages actually fetched.
     */
    @Test
    fun `a high-UID old-Date message must not stop the age floor while history is unfetched`() = runTest {
        val now = System.currentTimeMillis()
        // UIDs 1..20: genuinely old (~8 months). UIDs 21..99: within retention, Dates ascending with
        // UID. UID 100: a recent arrival whose Date header is 8 months old — the Date/UID inversion —
        // which lands inside the seeded foreground window.
        val old = (1..20).map { now - 8 * MONTH_MILLIS - it * DAY_MILLIS }
        val recent = (1..79).map { now - (80 - it) * DAY_MILLIS }
        val inverted = listOf(now - 8 * MONTH_MILLIS)
        appendMessages(old + recent + inverted)
        seedForegroundWindow()

        val backfiller = backfiller(AccountSettings("acct", retentionMonths = 6))
        var guard = 0
        while (backfiller.runBackfill() && guard++ < 10) { /* drive to completion */ }

        val cutoff = now - 6 * MONTH_MILLIS
        assertEquals(
            recent.size,
            cached.count { it.timestampMillis >= cutoff },
            "every within-retention message must be cached — no silent history gap",
        )
        assertEquals(true, progress["acct" to "INBOX"]?.complete, "paging still terminates at the floor")
        assertNoDeletes()
    }

    /**
     * The flip side of the page-based age floor (#94): a page ENTIRELY older than the cutoff ends
     * paging — the folder is marked complete without caching that page, which is pure prune-fodder
     * (persisting it would just make the pruner delete it again, the #12/#13 churn the sticky floor
     * exists to prevent).
     */
    @Test
    fun `an entirely-old page ends age paging without caching beyond the floor`() = runTest {
        val now = System.currentTimeMillis()
        // UIDs 1..50 are ~8 months old; UIDs 51..100 are within retention and fill the whole window.
        val old = (1..50).map { now - 8 * MONTH_MILLIS - (51 - it) * DAY_MILLIS }
        val recent = (1..50).map { now - (51 - it) * DAY_MILLIS }
        appendMessages(old + recent)
        seedForegroundWindow()

        backfiller(AccountSettings("acct", retentionMonths = 6)).runBackfill()

        assertEquals(true, progress["acct" to "INBOX"]?.complete, "one entirely-old page ends the folder")
        assertEquals(0, totalOffered, "the beyond-the-floor page must not be cached")
        val cutoff = now - 6 * MONTH_MILLIS
        assertTrue(cached.none { it.timestampMillis < cutoff }, "nothing older than the cutoff is cached")
        assertNoDeletes()
    }

    /**
     * Regression for #95: a cached row with `uid = 0` — a row migrated before the `uid` column
     * existed (MIGRATION_12_13 backfills a non-numeric id tail to 0) — used to collapse MIN(uid) to
     * 0, and `fetchOlderThan` treats a bound `<= 1` as "nothing older": the folder was marked fully
     * backfilled after caching NOTHING. The boundary must come from resolved (positive) UIDs only.
     */
    @Test
    fun `a uid 0 placeholder row must not poison the backfill boundary`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()
        // A pre-uid-column row, exactly as MIGRATION_12_13 leaves one whose id tail isn't numeric.
        cached += cached.first().copy(id = "acct:INBOX:legacy", uid = 0L)

        val backfiller = backfiller(AccountSettings("acct"))
        var guard = 0
        while (backfiller.runBackfill() && guard++ < 10) { /* drive to completion */ }

        assertEquals(
            TOTAL,
            cached.mapTo(HashSet()) { it.uid }.count { it > 0L },
            "the placeholder row must not stop backfill from paging the full history",
        )
        assertEquals(TOTAL - WINDOW, totalOffered, "each backfilled message fetched exactly once")
        assertEquals(true, progress["acct" to "INBOX"]?.complete)
        assertNoDeletes()
    }

    /**
     * Regression for #95 (server variant): when every UID on a fetched page is unresolvable
     * (UIDFolder.getUID returned -1), the boundary must not descend to -1 — the next fetch would come
     * back empty and the folder would be FALSELY marked fully backfilled. The folder must instead
     * stall: stay incomplete (retryable on a future run) without claiming immediate more-work (which
     * would make the worker's slice-chaining loop spin on the same page).
     */
    @Test
    fun `a page of unresolvable UIDs stalls the folder instead of falsely completing it`() = runTest {
        // One cached window row makes INBOX a backfill target with boundary UID 60.
        cached += fetchedMessage(uid = "60").toEntity("acct", "INBOX")

        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } answers {
            // The real client's contract: a collapsed boundary (<= 1) means "nothing older".
            if (thirdArg<Long>() <= 1L) emptyList() else listOf(fetchedMessage(uid = "-1"))
        }

        val moreWork = backfiller(AccountSettings("acct"), imapClient = imapClient).runBackfill()

        assertNotEquals(true, progress["acct" to "INBOX"]?.complete, "the folder must stay retryable")
        assertFalse(moreWork, "a stalled folder must not spin the worker's slice-chaining loop")
        coVerify(exactly = 0) { imapClient.fetchOlderThan(any(), any(), match { it <= 1L }, any()) }
    }

    /**
     * A backfilled page whose ids already exist (e.g. former search-only rows) must be *refreshed*
     * (markSynced + header update), not just IGNORE-inserted — this covers persistBatch's
     * pre-existing-row branch, which the all-brand-new happy paths above never hit. The refresh is
     * routed through the BATCHED [MessageDao.updateHeaderContents] (issue #322), never the per-row
     * [MessageDao.updateHeaderContent].
     */
    @Test
    fun `re-inserting a pre-existing header refreshes it through the batched update`() = runTest {
        appendMessages(60)
        seedForegroundWindow()
        val backfiller = backfiller(AccountSettings("acct"))
        // Report every offered id as already present, so persistBatch takes the refresh branch.
        coEvery { lastMessageDao!!.existingIds(any()) } answers { firstArg() }

        backfiller.runBackfill()

        coVerify(atLeast = 1) { lastMessageDao!!.markSynced(any()) }
        // The whole batch is refreshed in one transaction — the per-row path is never taken (issue #322).
        coVerify(atLeast = 1) { lastMessageDao!!.updateHeaderContents(any()) }
        coVerify(exactly = 0) {
            lastMessageDao!!.updateHeaderContent(any(), any(), any(), any(), any(), any())
        }
    }

    // --- issue #322: batched persist ------------------------------------------------------------

    /**
     * persistBatch routes its pre-existing-row refreshes through the BATCHED
     * [MessageDao.updateHeaderContents] — one transaction per page (issue #322) — and refreshes ONLY
     * the rows that already existed: brand-new rows are written whole by insertNew, so re-updating them
     * would be redundant. A partial page (some ids already present, some brand-new) exercises exactly
     * that split, in one batched call, never the per-row [MessageDao.updateHeaderContent].
     */
    @Test
    fun `a partial page refreshes only its pre-existing rows through one batched update`() = runTest {
        // One cached window row makes INBOX a backfill target with boundary UID 20.
        cached += fetchedMessage(uid = "20").toEntity("acct", "INBOX")
        // A single older page (UIDs 10..19); the next fetch (boundary 10) returns empty → folder complete.
        val page = (10..19).map { fetchedMessage(uid = it.toString()) }
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } answers {
            if (thirdArg<Long>() > 10L) page else emptyList()
        }
        val backfiller = backfiller(AccountSettings("acct"), imapClient = imapClient)
        // Only UIDs 10..13 are reported as already present — the partial pre-existing subset.
        val preexistingIds = (10..13).map { "acct:INBOX:$it" }.toSet()
        coEvery { lastMessageDao!!.existingIds(any()) } answers {
            firstArg<List<String>>().filter { it in preexistingIds }
        }
        val refreshedBatches = mutableListOf<List<MessageEntity>>()
        coEvery { lastMessageDao!!.updateHeaderContents(any()) } answers {
            refreshedBatches += firstArg<List<MessageEntity>>()
        }

        backfiller.runBackfill()

        // Exactly one batched call for the page, carrying exactly the pre-existing subset (not the 6 new rows).
        assertEquals(1, refreshedBatches.size, "one batched update per page")
        assertEquals(preexistingIds, refreshedBatches.single().mapTo(HashSet()) { it.id })
        // markSynced promotes exactly that subset; the per-row update path is never taken (issue #322).
        coVerify(exactly = 1) { lastMessageDao!!.markSynced(match { it.toSet() == preexistingIds }) }
        coVerify(exactly = 0) {
            lastMessageDao!!.updateHeaderContent(any(), any(), any(), any(), any(), any())
        }
        // Counts-only persist breadcrumb (PII-free): 10 fetched, 4 refreshed.
        assertTrue(
            logBuffer.snapshot().any { it.message == "backfill persist: fetched=10 refreshed=4" },
            "the persist breadcrumb logs page + refresh counts only",
        )
    }

    /**
     * The empty-refresh boundary: a page whose rows are ALL brand-new must skip the header-refresh
     * transaction entirely — insertNew writes them whole, so neither markSynced nor the batched
     * updateHeaderContents runs (issue #322 preserves persistBatch's isNotEmpty guard).
     */
    @Test
    fun `an all-new page skips the batched header update entirely`() = runTest {
        cached += fetchedMessage(uid = "20").toEntity("acct", "INBOX")
        val page = (10..19).map { fetchedMessage(uid = it.toString()) }
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } answers {
            if (thirdArg<Long>() > 10L) page else emptyList()
        }
        // existingIds stays at the relaxed default (empty) → every fetched row is brand-new.
        backfiller(AccountSettings("acct"), imapClient = imapClient).runBackfill()

        coVerify(exactly = 0) { lastMessageDao!!.updateHeaderContents(any()) }
        coVerify(exactly = 0) { lastMessageDao!!.markSynced(any()) }
        coVerify(exactly = 0) {
            lastMessageDao!!.updateHeaderContent(any(), any(), any(), any(), any(), any())
        }
        // The persist breadcrumb still records the page, with zero refreshed.
        assertTrue(
            logBuffer.snapshot().any { it.message == "backfill persist: fetched=10 refreshed=0" },
            "an all-new page logs refreshed=0",
        )
    }

    // --- issue #360: throttling backoff + graceful degradation ----------------------------------

    /**
     * When the server throttles the backfill (a `[THROTTLED]` / "too many connections" NO), the slice
     * must record a backoff for that account and STOP paging it — not retry the page in a tight loop
     * (which is exactly what makes provider throttling worse, `docs/perf/issue-125-*`). It also must not
     * report more-work, so [BackfillWorker]'s slice-chaining loop stops rather than spinning.
     */
    @Test
    fun `a throttling server pauses that account's backfill instead of hammering it`() = runTest {
        cached += fetchedMessage(uid = "60").toEntity("acct", "INBOX")
        var calls = 0
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } answers {
            calls++
            throw MessagingException("A3 NO [THROTTLED] Too many simultaneous connections")
        }
        val gate = AccountThrottleGate()

        val moreWork = backfiller(AccountSettings("acct"), imapClient = imapClient, throttleGate = gate).runBackfill()

        assertFalse(moreWork, "a throttled account must not drive an immediate re-slice (no tight loop)")
        assertTrue(gate.isThrottled("acct"), "the account is now backing off")
        assertEquals(1, calls, "paging stops at the first throttle, it is not retried in a tight loop")
        assertTrue(
            logBuffer.snapshot().any { it.message.startsWith("throttled acct:") },
            "a PII-free throttle breadcrumb is recorded",
        )
    }

    /**
     * An account still inside its backoff window is skipped entirely — no server call at all — so a
     * provider we were just throttled by is left alone until the window elapses (graceful degradation +
     * per-account isolation).
     */
    @Test
    fun `an account inside its backoff window is skipped, not paged`() = runTest {
        cached += fetchedMessage(uid = "60").toEntity("acct", "INBOX")
        val imapClient = mockk<ImapClient>(relaxed = true)
        val gate = AccountThrottleGate().apply { onThrottle("acct", ThrottleSignal(ThrottleKind.RATE_LIMIT)) }

        val moreWork = backfiller(AccountSettings("acct"), imapClient = imapClient, throttleGate = gate).runBackfill()

        assertFalse(moreWork, "a slice whose only account is throttled reports done, not more-work")
        coVerify(exactly = 0) { imapClient.fetchOlderThan(any(), any(), any(), any()) }
        assertTrue(logBuffer.snapshot().any { it.message.startsWith("backfill skip acct:") })
    }

    // --- issue #363: iCloud Mail connection cap --------------------------------------------------

    /**
     * The core of #363: an iCloud account's page fetch is routed through [IcloudConnectionLimiter], so
     * once its one connection permit is held elsewhere backfill's next page WAITS for it to free instead
     * of opening past iCloud's documented cap — proven by holding the limiter's only permit externally
     * and observing backfill park until it releases. Mirrors the #355 park/resume test's real-dispatcher
     * hand-off idiom just below (a virtual-time `runTest` can't observe a real suspend shared by two
     * launched coroutines across a [kotlinx.coroutines.sync.Semaphore]).
     */
    @Test
    fun `an iCloud account's backfill waits for a connection permit instead of exceeding the cap`() =
        runBlocking<Unit> {
            cached += fetchedMessage(uid = "60").toEntity("acct", "INBOX")
            val icloudEntity = accountEntity.copy(imap = ServerConfigEmbedded("imap.mail.me.com", 993, "SSL_TLS"))
            val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 1)
            val fetched = CompletableDeferred<Unit>()
            val imapClient = mockk<ImapClient>()
            coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } coAnswers {
                fetched.complete(Unit)
                emptyList() // one page, then the folder completes and the slice ends
            }

            // Something else already holds the account's one iCloud connection permit.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) {
                limiter.withPermit(icloudEntity.toDomain()) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            val backfillJob = launch(Dispatchers.Default) {
                backfiller(
                    AccountSettings("acct"),
                    imapClient = imapClient,
                    icloudConnectionLimiter = limiter,
                    account = icloudEntity,
                ).runBackfill()
            }
            delay(PARK_PROBE_MS)
            assertFalse(fetched.isCompleted, "backfill must wait for a free connection permit")

            release.complete(Unit)
            withTimeout(HAND_OFF_TIMEOUT_MS) { backfillJob.join() }
            assertTrue(fetched.isCompleted, "backfill proceeds once a permit frees")
            holder.join()
        }

    /**
     * A non-iCloud account (the GreenMail fixture's host) is never gated by [IcloudConnectionLimiter]:
     * exhausting the SAME limiter instance's one permit for an unrelated iCloud account must not affect
     * it — the gate is iCloud-only policy, not a general connection pool (#361/#362/#364 are unaffected).
     */
    @Test
    fun `a non-iCloud account's backfill is never gated by the iCloud connection limiter`() = runBlocking<Unit> {
        cached += fetchedMessage(uid = "60").toEntity("acct", "INBOX")
        val limiter = IcloudConnectionLimiter(maxConcurrentConnections = 1)
        val imapClient = mockk<ImapClient>(relaxed = true)
        coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } returns emptyList()

        // Hold the one permit for a DIFFERENT, genuinely-iCloud account.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            limiter.withPermit(MailProvider.ICLOUD.createAccount("other@icloud.com")) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val moreWork = withTimeout(HAND_OFF_TIMEOUT_MS) {
            backfiller(
                AccountSettings("acct"),
                imapClient = imapClient,
                icloudConnectionLimiter = limiter,
            ).runBackfill()
        }

        assertFalse(moreWork)
        coVerify(atLeast = 1) { imapClient.fetchOlderThan(any(), any(), any(), any()) }
        release.complete(Unit)
        holder.join()
    }

    // --- issue #355: interactive-fetch priority -------------------------------------------------

    /**
     * The core of #355: while an interactive fetch (a message open, an attachment download, …) holds the
     * [InteractiveImapGate], backfill must PARK at its per-page yield point instead of stealing the
     * account's IMAP throughput, and resume the instant the interactive fetch clears. The backfiller and
     * the interactive holder share ONLY the gate, so a pass is attributable solely to it. Mirrors
     * [MailMaintenanceGateTest]'s real-dispatcher hand-off idiom (runBlocking + Dispatchers.Default).
     */
    @Test
    fun `backfill parks before its next page while an interactive fetch holds the gate, then resumes`() =
        runBlocking<Unit> {
            cached += fetchedMessage(uid = "60").toEntity("acct", "INBOX")
            val gate = InteractiveImapGate()
            val fetched = CompletableDeferred<Unit>()
            val imapClient = mockk<ImapClient>()
            coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } coAnswers {
                fetched.complete(Unit)
                emptyList() // one page, then the folder completes and the slice ends
            }

            // An interactive fetch is in flight: it holds the gate until we release it.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val interactive = launch(Dispatchers.Default) {
                gate.withInteractive {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            val backfillJob = launch(Dispatchers.Default) {
                backfiller(AccountSettings("acct"), imapClient = imapClient, interactiveGate = gate).runBackfill()
            }
            delay(PARK_PROBE_MS)
            assertFalse(fetched.isCompleted, "backfill must not page while an interactive fetch is active")
            assertTrue(
                logBuffer.snapshot().any { it.message.startsWith("backfill parking acct:") },
                "a PII-free park breadcrumb is recorded",
            )

            release.complete(Unit)
            withTimeout(HAND_OFF_TIMEOUT_MS) { backfillJob.join() }
            assertTrue(fetched.isCompleted, "backfill resumes and pages once the interactive fetch clears")
            assertTrue(
                logBuffer.snapshot().any { it.message.startsWith("backfill resumed acct:") },
                "a PII-free resume breadcrumb is recorded",
            )
            interactive.join()
        }

    /**
     * No-deadlock guarantee: an interactive fetch that ERRORS still releases the gate (withInteractive's
     * `finally`), so backfill is never stranded parked behind a failed open. The failing fetch has already
     * released the gate here, and backfill then runs to completion without hanging.
     */
    @Test
    fun `an interactive fetch that errors does not strand backfill parked`() = runBlocking<Unit> {
        cached += fetchedMessage(uid = "60").toEntity("acct", "INBOX")
        val gate = InteractiveImapGate()
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } returns emptyList()

        val failed = runCatching { gate.withInteractive { throw MessagingException("open failed") } }
        assertTrue(failed.isFailure, "the interactive fetch failed")
        assertFalse(gate.isInteractiveActive(), "the errored fetch still released the gate")

        val moreWork = withTimeout(HAND_OFF_TIMEOUT_MS) {
            backfiller(AccountSettings("acct"), imapClient = imapClient, interactiveGate = gate).runBackfill()
        }

        assertFalse(moreWork)
        coVerify(atLeast = 1) { imapClient.fetchOlderThan(any(), any(), any(), any()) }
    }

    // --- issue #329: AppLog breadcrumbs ---------------------------------------------------------

    @Test
    fun `runBackfill logs a start breadcrumb, a per-folder breadcrumb, and a done breadcrumb`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()

        backfiller(AccountSettings("acct")).runBackfill(maxBatches = 3)

        val snapshot = logBuffer.snapshot()
        val start = snapshot.first { it.message.startsWith("backfill slice:") }
        val perFolder = snapshot.first { it.message.startsWith("backfill acct:") }
        val done = snapshot.first { it.message.startsWith("backfill slice done:") }
        assertEquals('I', start.level)
        assertEquals("backfill slice: maxBatches=3", start.message)
        assertEquals('D', perFolder.level)
        assertTrue(perFolder.message.contains("folder=INBOX"), perFolder.message)
        assertTrue(perFolder.message.contains("pages="), perFolder.message)
        assertTrue(perFolder.message.contains("complete="), perFolder.message)
        assertEquals('I', done.level)
        assertTrue(done.message.contains("moreWork="), done.message)
        snapshot.forEach { assertNoPii(it.message) }
    }

    @Test
    fun `an already-complete folder logs no redundant per-folder breadcrumb on the next slice`() = runTest {
        appendMessages(60)
        seedForegroundWindow()
        val backfiller = backfiller(AccountSettings("acct"))
        var guard = 0
        while (backfiller.runBackfill() && guard++ < 10) { /* drive to completion */ }
        logBuffer.clear()

        // The folder is already complete; this slice does no work for it.
        backfiller.runBackfill()

        assertTrue(
            logBuffer.snapshot().none { it.message.startsWith("backfill acct:") },
            "an already-complete folder must not spam a breadcrumb on every subsequent slice",
        )
    }

    @Test
    fun `no slice of a full backfill ever logs the account's email, host, or a message address`() = runTest {
        appendMessages(TOTAL)
        seedForegroundWindow()
        val backfiller = backfiller(AccountSettings("acct"))

        var guard = 0
        while (backfiller.runBackfill() && guard++ < 10) { /* drive to completion */ }

        val snapshot = logBuffer.snapshot()
        assertTrue(snapshot.isNotEmpty(), "the run must have logged something to be a meaningful check")
        snapshot.forEach { assertNoPii(it.message) }
    }

    /** No test fixture's email address or host may ever reach a log line — the hard PII rule. */
    private fun assertNoPii(message: String) {
        assertFalse(message.contains("@example.org"), message) // covers the account and every sender
        assertFalse(message.contains("127.0.0.1"), message) // the GreenMail host
    }

    private fun fetchedMessage(uid: String) = FetchedMessage(
        uid = uid,
        sender = "Sender",
        senderEmail = "sender@example.org",
        subject = "Message $uid",
        timestampMillis = System.currentTimeMillis(),
        isRead = false,
        isFlagged = false,
    )

    /** Builds a backfiller wired to [imapClient] (GreenMail-backed by default) with the in-memory fakes. */
    private fun backfiller(
        accountSettings: AccountSettings,
        fetchPolicy: FetchPolicy = FetchPolicy.ON_DEMAND,
        battery: BatteryStatus = BatteryStatus(percent = 100, isCharging = false),
        imapClient: ImapClient = client,
        throttleGate: AccountThrottleGate = AccountThrottleGate(),
        interactiveGate: InteractiveImapGate = InteractiveImapGate(),
        icloudConnectionLimiter: IcloudConnectionLimiter = IcloudConnectionLimiter(),
        account: AccountEntity = accountEntity,
    ): MailBackfiller {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns listOf(account)

        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.insertNew(any()) } answers {
            val batch = firstArg<List<MessageEntity>>()
            totalOffered += batch.size // count BEFORE de-dupe (see field)
            batch.forEach { e -> if (cached.none { it.id == e.id }) cached += e }
        }
        coEvery { messageDao.syncedFolders("acct") } answers {
            cached.filter { it.inInbox }.map { it.folder }.distinct()
        }
        coEvery { messageDao.lowestSyncedUid("acct", any()) } answers {
            val folder = secondArg<String>()
            // Mirrors the real query's `uid > 0` guard (#95): placeholder rows never drive the boundary.
            cached.filter { it.inInbox && it.folder == folder && it.uid > 0L }.minOfOrNull { it.uid }
        }
        coEvery { messageDao.countSynced("acct", any()) } answers {
            val folder = secondArg<String>()
            cached.count { it.inInbox && it.folder == folder }
        }

        val backfillProgressDao = mockk<BackfillProgressDao>(relaxed = true)
        coEvery { backfillProgressDao.get("acct", any()) } answers { progress["acct" to secondArg<String>()] }
        coEvery { backfillProgressDao.upsert(any()) } answers {
            val p = firstArg<BackfillProgressEntity>()
            progress[p.accountId to p.folder] = p
        }

        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns params()

        val settingsRepository = mockk<SettingsRepository>()
        coEvery { settingsRepository.fetchPolicy() } returns fetchPolicy
        every { settingsRepository.settings } returns flowOf(AppSettings())

        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns accountSettings

        val batteryStatusProvider = mockk<BatteryStatusProvider> { every { current() } returns battery }
        val mailRepository = mockk<MailRepository>(relaxed = true)

        return MailBackfiller(
            context = mockk<Context>(relaxed = true),
            accountDao = accountDao,
            messageDao = messageDao,
            backfillProgressDao = backfillProgressDao,
            imapClient = imapClient,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            batteryStatusProvider = batteryStatusProvider,
            mailRepository = mailRepository,
            maintenanceGate = MailMaintenanceGate(),
            throttleGate = throttleGate,
            interactiveGate = interactiveGate,
            icloudConnectionLimiter = icloudConnectionLimiter,
        ).also {
            lastMessageDao = messageDao
            lastMailRepository = mailRepository
        }
    }

    private var lastMessageDao: MessageDao? = null
    private var lastMailRepository: MailRepository? = null

    /** Seeds the in-memory cache with the newest [WINDOW] headers, mimicking a prior foreground sync. */
    private suspend fun seedForegroundWindow() {
        client.fetchRecent(params(), "INBOX", limit = WINDOW)
            .map { it.toEntity("acct", "INBOX") }
            .forEach { cached += it }
    }

    private fun distinctCachedUids(): Set<Long> = cached.map { it.uid }.toSet()

    private fun assertNoDeletes() {
        val dao = lastMessageDao ?: return
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
        coVerify(exactly = 0) { dao.deleteSyncedInWindowNotIn(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteSyncedByAccountFolder(any(), any()) }
    }

    private fun appendMessages(count: Int) = appendMessages(List<Long?>(count) { null })

    /**
     * Appends messages to INBOX with the given per-message sent dates (null = server default, ~now).
     * UID order follows list order, so earlier entries get lower UIDs.
     */
    private fun appendMessages(sentDates: List<Long?>) {
        val props = Properties().apply {
            put("mail.store.protocol", "imap")
            put("mail.imap.host", "127.0.0.1")
            put("mail.imap.port", greenMail.imap.port.toString())
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, "alice@example.org", "secret")
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            val messages = sentDates.mapIndexed { i, millis ->
                MimeMessage(session).apply {
                    setFrom(InternetAddress("sender$i@example.org"))
                    setRecipient(Message.RecipientType.TO, InternetAddress("alice@example.org"))
                    subject = "Message $i"
                    setText("Body of message $i")
                    if (millis != null) sentDate = Date(millis)
                }
            }.toTypedArray()
            inbox.appendMessages(messages)
            inbox.close(false)
        } finally {
            store.close()
        }
    }

    private companion object {
        const val TOTAL = 120
        const val WINDOW = 50
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val MONTH_MILLIS = 30L * DAY_MILLIS

        /** Slack given to a parked backfill to (wrongly) page before we assert it is still parked (#355). */
        private const val PARK_PROBE_MS = 300L

        /** Generous bound for the gate hand-off; only a real park/resume regression approaches it (#355). */
        private const val HAND_OFF_TIMEOUT_MS = 5_000L
    }
}
