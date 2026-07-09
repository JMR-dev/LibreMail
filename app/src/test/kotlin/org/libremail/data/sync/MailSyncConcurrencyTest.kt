// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.mail.FetchedMessage
import org.libremail.mail.ImapClient
import org.libremail.power.BatteryStatus
import org.libremail.power.BatteryStatusProvider
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Interleaving tests for the two sync↔maintenance pairs that [MailMaintenanceGate] deliberately does
 * NOT serialize (issue #53). Foreground sync ([MailSyncer]) uses its own `syncMutex` and never takes
 * the maintenance gate — so it can run concurrently with the full-history backfill ([MailBackfiller],
 * #12) and the retention pruner ([MailPruner], #13). Their safety rests on a *disjoint-by-UID*
 * argument rather than a shared lock:
 *
 *  - **sync** only writes/reconciles the recent-UID window: [MessageDao.deleteSyncedInWindowNotIn]
 *    touches only `uid >= minWindowUid`, the lowest UID of the freshly-fetched newest-N;
 *  - **backfill** only writes strictly *below* the window: `ImapClient.fetchOlderThan` is bounded by
 *    [MessageDao.lowestSyncedUid] (`<= minWindowUid`), and it never deletes;
 *  - **prune** only deletes *below* the retention floor, and [MailSyncer]'s `recentWindowFor` caps the
 *    fetched window to the retention count — so the window sync keeps fresh is exactly the newest-N
 *    the count pruner keeps, and their target rows can never overlap.
 *
 * These tests wire a real [MailSyncer] and a real [MailBackfiller]/[MailPruner] to the SAME in-memory
 * message store, then — mirroring [MailMaintenanceGateTest] — use `CompletableDeferred` gates to park
 * one actor mid-critical-section (inside its IMAP fetch, or, for the IMAP-less pruner, inside its
 * retention read) while the other's whole critical section runs. Each interleaving is driven to the
 * BOUNDARY (window edge / count floor), where an overlap would surface as a lost, duplicated, or
 * wrongly-deleted row. They are expected to PASS, proving the disjointness invariant holds unlocked.
 */
class MailSyncConcurrencyTest {

    private val accountEntity = AccountEntity(
        id = "acct",
        email = "alice@example.org",
        displayName = "Alice",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("127.0.0.1", 993, "NONE"),
        smtp = ServerConfigEmbedded("127.0.0.1", 465, "NONE"),
    )

    private val params = ImapConnectionParams(
        host = "h",
        port = 143,
        security = MailSecurity.NONE,
        username = "u",
        secret = "s",
        useXoauth2 = false,
    )

    // issue #329: MailSyncer/MailBackfiller/MailPruner now breadcrumb through AppLog, whose Logcat
    // forwarding (`android.util.Log`) is a no-op stub under plain JVM unit tests — mock it statically,
    // mirroring org.libremail.reporting.AppLogTest. The breadcrumbs themselves are asserted in
    // MailSyncerTest/MailBackfillerTest/MailPrunerTest; this class only needs to not crash.
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        AppLog.install(RingLogBuffer())
    }

    @After
    fun tearDown() = unmockkAll()

    // --- sync ↔ backfill -----------------------------------------------------------------------

    /**
     * The strongest sync↔backfill interleaving: a backfill has paged in the slice *just* below the
     * window and is parked mid-way through fetching the next (older) page when a full foreground sync
     * runs. Sync's windowed reconcile ([MessageDao.deleteSyncedInWindowNotIn]) must spare every
     * below-window row the backfill already wrote — the "sync deletes a row backfill is mid-writing"
     * hazard. Seeded so the cache is the window alone (`lowestSyncedUid == minWindowUid == 21`), the
     * tightest boundary: backfill writes right up to UID 20, sync deletes from UID 21 up.
     */
    @Test
    fun `sync's windowed reconcile spares rows a concurrent backfill is paging in below the window`() = runBlocking {
        val store = FakeMessageStore()
        store.seed((21L..30L).map { entity(it) }) // only the recent window is cached
        val dao = fakeDao(store)

        val backfillAtSecondPage = CompletableDeferred<Unit>()
        val releaseSecondPage = CompletableDeferred<Unit>()
        val page = AtomicInteger(0)

        val backfillImap = mockk<ImapClient>()
        coEvery { backfillImap.fetchOlderThan(any(), any(), any(), any()) } coAnswers {
            when (page.incrementAndGet()) {
                1 -> (11L..20L).map { fetched(it) } // the page immediately below the window
                2 -> {
                    backfillAtSecondPage.complete(Unit)
                    releaseSecondPage.await()
                    (1L..10L).map { fetched(it) }
                }
                else -> emptyList()
            }
        }
        val syncImap = mockk<ImapClient>()
        coEvery { syncImap.fetchRecent(any(), any(), any()) } returns (21L..30L).map { fetched(it) }

        val backfiller = backfiller(dao, backfillImap)
        val syncer = syncer(dao, syncImap)

        // Backfill writes the first below-window page, then parks with its second page still in flight.
        val backfillJob = launch(Dispatchers.Default) { backfiller.runBackfill() }
        backfillAtSecondPage.await()
        assertEquals((11L..30L).toList(), store.syncedUids(), "the window plus the first backfilled page are cached")

        // A full foreground sync runs against that half-backfilled cache.
        launch(Dispatchers.Default) { syncer.syncFolder("acct", "INBOX") }.join()
        assertEquals(
            (11L..30L).toList(),
            store.syncedUids(),
            "sync's windowed delete must not touch the backfilled page below its window",
        )

        releaseSecondPage.complete(Unit)
        backfillJob.join()

        assertEquals((1L..30L).toList(), store.syncedUids(), "no row lost, duplicated, or wrongly deleted")
    }

    /**
     * The reverse ordering: a backfill reads its boundary (`lowestSyncedUid`) and parks in the fetch,
     * about to page the oldest slice, when a full foreground sync of the window completes. The
     * backfill's page — decided from a pre-sync snapshot — must still land strictly below the window
     * sync just reconciled, and sync must leave the already-backfilled history untouched.
     */
    @Test
    fun `a backfill paging below the window is unaffected by a concurrent foreground sync of the window`() =
        runBlocking {
            val store = FakeMessageStore()
            store.seed((11L..30L).map { entity(it) }) // window 21..30 + already-backfilled 11..20
            val dao = fakeDao(store)

            val backfillParked = CompletableDeferred<Unit>()
            val releaseBackfill = CompletableDeferred<Unit>()

            val backfillImap = mockk<ImapClient>()
            coEvery { backfillImap.fetchOlderThan(any(), any(), any(), any()) } coAnswers {
                if (thirdArg<Long>() > 1L) {
                    backfillParked.complete(Unit)
                    releaseBackfill.await()
                    (1L..10L).map { fetched(it) } // strictly below the window
                } else {
                    emptyList()
                }
            }
            val syncImap = mockk<ImapClient>()
            coEvery { syncImap.fetchRecent(any(), any(), any()) } returns (21L..30L).map { fetched(it) }

            val backfiller = backfiller(dao, backfillImap)
            val syncer = syncer(dao, syncImap)

            val backfillJob = launch(Dispatchers.Default) { backfiller.runBackfill() }
            backfillParked.await() // boundary already read (lowestSyncedUid = 11); fetch in flight

            launch(Dispatchers.Default) { syncer.syncFolder("acct", "INBOX") }.join()
            assertEquals(
                (11L..30L).toList(),
                store.syncedUids(),
                "foreground sync leaves the backfilled history intact",
            )

            releaseBackfill.complete(Unit)
            backfillJob.join()

            assertEquals((1L..30L).toList(), store.syncedUids(), "backfill's below-window page lands with no overlap")
        }

    // --- sync ↔ prune --------------------------------------------------------------------------

    /**
     * A retention prune (count floor = 10) runs to completion while a foreground sync is parked in its
     * fetch. The pruner must delete exactly the rows below the floor (UIDs 1..20), and when sync
     * resumes its windowed reconcile it must neither resurrect a pruned row (it only fetches the
     * newest-10 window) nor delete a kept one — the window sync keeps fresh (21..30) is exactly the
     * pruner's kept set, so their targets are disjoint.
     */
    @Test
    fun `a retention prune deletes only below the count floor while a foreground sync refreshes the window`() =
        runBlocking {
            val store = FakeMessageStore()
            store.seed((1L..30L).map { entity(it) }) // 30 cached; retention keeps the newest 10
            val dao = fakeDao(store)

            val syncParked = CompletableDeferred<Unit>()
            val releaseSync = CompletableDeferred<Unit>()

            val syncImap = mockk<ImapClient>()
            coEvery { syncImap.fetchRecent(any(), any(), any()) } coAnswers {
                syncParked.complete(Unit)
                releaseSync.await()
                (21L..30L).map { fetched(it) } // the newest-10 recent window (== the retention count)
            }

            val syncer = syncer(dao, syncImap, AccountSettings("acct", retentionCount = 10))
            val pruner = pruner(dao, AccountSettings("acct", retentionCount = 10))

            // Sync parks in its fetch, before any DB write.
            val syncJob = launch(Dispatchers.Default) { syncer.syncFolder("acct", "INBOX") }
            syncParked.await()

            launch(Dispatchers.Default) { pruner.prune() }.join()
            assertEquals(
                (21L..30L).toList(),
                store.syncedUids(),
                "prune removes exactly the rows below the count floor",
            )

            releaseSync.complete(Unit)
            syncJob.join()

            assertEquals(
                (21L..30L).toList(),
                store.syncedUids(),
                "sync and prune targeted disjoint rows — no pruned row resurrected, no kept row deleted",
            )
        }

    /**
     * The reverse ordering: a prune enters its critical section and parks (before touching the message
     * table) while a full foreground sync runs. Sync must touch only its window — leaving the
     * below-floor rows present for the prune — and when the prune resumes it must delete exactly those
     * below-floor rows and none of the window sync just refreshed.
     */
    @Test
    fun `a foreground sync mid-prune neither loses a kept row nor blocks the below-floor deletion`() = runBlocking {
        val store = FakeMessageStore()
        store.seed((1L..30L).map { entity(it) })
        val dao = fakeDao(store)

        val pruneParked = CompletableDeferred<Unit>()
        val releasePrune = CompletableDeferred<Unit>()

        val syncImap = mockk<ImapClient>()
        coEvery { syncImap.fetchRecent(any(), any(), any()) } returns (21L..30L).map { fetched(it) }

        val syncer = syncer(dao, syncImap, AccountSettings("acct", retentionCount = 10))
        val pruner = pruner(
            dao,
            AccountSettings("acct", retentionCount = 10),
            onEnterSection = {
                pruneParked.complete(Unit)
                releasePrune.await()
            },
        )

        val pruneJob = launch(Dispatchers.Default) { pruner.prune() }
        pruneParked.await() // inside prune's critical section, before it reads/deletes any row

        launch(Dispatchers.Default) { syncer.syncFolder("acct", "INBOX") }.join()
        assertEquals(
            (1L..30L).toList(),
            store.syncedUids(),
            "sync touches only its window; the below-floor rows are still present for the prune",
        )

        releasePrune.complete(Unit)
        pruneJob.join()

        assertEquals(
            (21L..30L).toList(),
            store.syncedUids(),
            "prune deletes exactly the below-floor rows the sync left untouched",
        )
    }

    // --- builders ------------------------------------------------------------------------------

    private fun syncer(
        messageDao: MessageDao,
        imapClient: ImapClient,
        accountSettings: AccountSettings = AccountSettings("acct"),
    ): MailSyncer {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getById("acct") } returns accountEntity
        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns params
        val settingsRepository = mockk<SettingsRepository>()
        coEvery { settingsRepository.fetchPolicy() } returns FetchPolicy.ON_DEMAND
        every { settingsRepository.settings } returns flowOf(AppSettings())
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns accountSettings
        return MailSyncer(
            context = mockk(relaxed = true),
            accountDao = accountDao,
            messageDao = messageDao,
            imapClient = imapClient,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            batteryStatusProvider = fullBattery(),
            notifier = mockk(relaxed = true),
            mailRepository = mockk(relaxed = true),
            throttleGate = AccountThrottleGate(),
            bandwidthTracker = GmailBandwidthTracker(),
        )
    }

    private fun backfiller(
        messageDao: MessageDao,
        imapClient: ImapClient,
        accountSettings: AccountSettings = AccountSettings("acct"),
    ): MailBackfiller {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns listOf(accountEntity)
        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns params
        val settingsRepository = mockk<SettingsRepository>()
        coEvery { settingsRepository.fetchPolicy() } returns FetchPolicy.ON_DEMAND
        every { settingsRepository.settings } returns flowOf(AppSettings())
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns accountSettings
        return MailBackfiller(
            context = mockk(relaxed = true),
            accountDao = accountDao,
            messageDao = messageDao,
            backfillProgressDao = mockk<BackfillProgressDao>(relaxed = true), // no persisted progress → first run
            imapClient = imapClient,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            batteryStatusProvider = fullBattery(),
            mailRepository = mockk(relaxed = true),
            maintenanceGate = MailMaintenanceGate(),
            throttleGate = AccountThrottleGate(),
            interactiveGate = InteractiveImapGate(),
            icloudConnectionLimiter = IcloudConnectionLimiter(),
            bandwidthTracker = GmailBandwidthTracker(),
        )
    }

    /**
     * A pruner over the shared [messageDao]. [onEnterSection] (when set) is invoked from inside the
     * retention read of prune's critical section — the lever used to park the (IMAP-less) pruner
     * mid-operation, after it has entered its critical section but before it touches the message table.
     */
    private fun pruner(
        messageDao: MessageDao,
        accountSettings: AccountSettings,
        onEnterSection: (suspend () -> Unit)? = null,
    ): MailPruner {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns listOf(accountEntity)
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.settings } returns flowOf(AppSettings())
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } coAnswers {
            onEnterSection?.invoke()
            accountSettings
        }
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns File(System.getProperty("java.io.tmpdir"), "libremail-sync-conc-test")
        return MailPruner(
            context = context,
            accountDao = accountDao,
            messageDao = messageDao,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            maintenanceGate = MailMaintenanceGate(),
        )
    }

    private fun fullBattery(): BatteryStatusProvider =
        mockk<BatteryStatusProvider> { every { current() } returns BatteryStatus(percent = 100, isCharging = false) }

    // --- shared in-memory message store --------------------------------------------------------

    private fun entity(uid: Long, folder: String = "INBOX", timestampMillis: Long = uid * 1_000L) = MessageEntity(
        id = "acct:$folder:$uid",
        accountId = "acct",
        sender = "s$uid",
        senderEmail = "s$uid@example.org",
        subject = "Message $uid",
        snippet = "",
        body = "",
        timestampMillis = timestampMillis,
        isRead = true,
        isStarred = false,
        folder = folder,
        inInbox = true,
        bodyFetched = false,
        uid = uid,
    )

    private fun fetched(uid: Long, timestampMillis: Long = uid * 1_000L) = FetchedMessage(
        uid = uid.toString(),
        sender = "s$uid",
        senderEmail = "s$uid@example.org",
        subject = "Message $uid",
        timestampMillis = timestampMillis,
        isRead = true,
        isFlagged = false,
    )

    /** Wires a relaxed [MessageDao] mock over [store], faithfully reproducing each query's semantics. */
    private fun fakeDao(store: FakeMessageStore): MessageDao {
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getSyncedIds(any(), any()) } answers { store.getSyncedIds(firstArg(), secondArg()) }
        coEvery { dao.getUnfetchedIds(any(), any()) } returns emptyList()
        coEvery { dao.insertNew(any()) } answers { store.insertNew(firstArg()) }
        coEvery { dao.existingIds(any()) } answers { store.existingIds(firstArg()) }
        coEvery { dao.markSynced(any()) } answers { store.markSynced(firstArg()) }
        coEvery { dao.updateHeaderContent(any(), any(), any(), any(), any(), any()) } answers {
            store.updateHeaderContent(firstArg(), secondArg(), thirdArg(), arg(3), arg(4), arg(5))
        }
        coEvery { dao.updateHeaderContents(any()) } answers {
            firstArg<List<MessageEntity>>().forEach {
                store.updateHeaderContent(it.id, it.sender, it.senderEmail, it.subject, it.timestampMillis, it.uid)
            }
        }
        coEvery { dao.deleteByIds(any()) } answers { store.deleteByIds(firstArg()) }
        coEvery { dao.deleteSyncedByAccountFolder(any(), any()) } answers {
            store.deleteSyncedByAccountFolder(firstArg(), secondArg())
        }
        coEvery { dao.deleteSyncedInWindowNotIn(any(), any(), any(), any()) } answers {
            store.deleteSyncedInWindowNotIn(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        coEvery { dao.lowestSyncedUid(any(), any()) } answers { store.lowestSyncedUid(firstArg(), secondArg()) }
        coEvery { dao.countSynced(any(), any()) } answers { store.countSynced(firstArg(), secondArg()) }
        coEvery { dao.syncedFolders(any()) } answers { store.syncedFolders(firstArg()) }
        coEvery { dao.syncedIdsOlderThan(any(), any()) } answers { store.syncedIdsOlderThan(firstArg(), secondArg()) }
        coEvery { dao.syncedIdsBeyondCountInFolder(any(), any(), any()) } answers {
            store.syncedIdsBeyondCountInFolder(firstArg(), secondArg(), thirdArg())
        }
        return dao
    }

    /**
     * A thread-safe in-memory stand-in for the `messages` table shared by all three actors. Each
     * method reproduces the exact predicate of the corresponding [MessageDao] query, so the
     * disjointness the tests assert is the real SQL semantics, not a simplification. Every access is
     * `synchronized` so the two concurrent actors observe atomic, serialized statements — the same
     * per-statement atomicity real Room provides.
     */
    private class FakeMessageStore {
        private val lock = Any()
        private val rows = LinkedHashMap<String, MessageEntity>()

        fun seed(entities: List<MessageEntity>) = synchronized(lock) {
            entities.forEach { rows[it.id] = it }
        }

        /** Ascending UIDs of the folder-synced rows in [folder] — the assertion projection. */
        fun syncedUids(folder: String = "INBOX"): List<Long> = synchronized(lock) {
            rows.values.filter { it.inInbox && it.folder == folder }.map { it.uid }.sorted()
        }

        fun getSyncedIds(accountId: String, folder: String): List<String> = synchronized(lock) {
            rows.values.filter { it.inInbox && it.accountId == accountId && it.folder == folder }.map { it.id }
        }

        fun insertNew(batch: List<MessageEntity>) = synchronized(lock) {
            batch.forEach { e -> if (!rows.containsKey(e.id)) rows[e.id] = e } // INSERT OR IGNORE
        }

        fun existingIds(ids: List<String>): List<String> = synchronized(lock) { ids.filter { rows.containsKey(it) } }

        fun markSynced(ids: List<String>) = synchronized(lock) {
            ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(inInbox = true) } }
        }

        @Suppress("LongParameterList")
        fun updateHeaderContent(
            id: String,
            sender: String,
            senderEmail: String,
            subject: String,
            timestampMillis: Long,
            uid: Long,
        ) = synchronized(lock) {
            rows[id]?.let {
                rows[id] = it.copy(
                    sender = sender,
                    senderEmail = senderEmail,
                    subject = subject,
                    timestampMillis = timestampMillis,
                    uid = uid,
                )
            }
        }

        fun deleteByIds(ids: List<String>) = synchronized(lock) { ids.forEach { rows.remove(it) } }

        fun deleteSyncedByAccountFolder(accountId: String, folder: String) = synchronized(lock) {
            rows.entries.removeIf { it.value.inInbox && it.value.accountId == accountId && it.value.folder == folder }
        }

        fun deleteSyncedInWindowNotIn(accountId: String, folder: String, minWindowUid: Long, keepIds: List<String>) =
            synchronized(lock) {
                val keep = keepIds.toHashSet()
                rows.entries.removeIf { entry ->
                    val e = entry.value
                    e.inInbox &&
                        e.accountId == accountId &&
                        e.folder == folder &&
                        e.uid >= minWindowUid &&
                        entry.key !in keep
                }
            }

        fun lowestSyncedUid(accountId: String, folder: String): Long? = synchronized(lock) {
            rows.values
                .filter { it.inInbox && it.accountId == accountId && it.folder == folder && it.uid > 0L }
                .minOfOrNull { it.uid }
        }

        fun countSynced(accountId: String, folder: String): Int = synchronized(lock) {
            rows.values.count { it.inInbox && it.accountId == accountId && it.folder == folder }
        }

        fun syncedFolders(accountId: String): List<String> = synchronized(lock) {
            rows.values.filter { it.inInbox && it.accountId == accountId }.map { it.folder }.distinct()
        }

        fun syncedIdsOlderThan(accountId: String, cutoffMillis: Long): List<String> = synchronized(lock) {
            rows.values
                .filter { it.inInbox && it.accountId == accountId && it.timestampMillis < cutoffMillis }
                .map { it.id }
        }

        fun syncedIdsBeyondCountInFolder(accountId: String, folder: String, keep: Int): List<String> =
            synchronized(lock) {
                val inFolder = rows.values.filter { it.inInbox && it.accountId == accountId && it.folder == folder }
                val kept = inFolder.sortedByDescending { it.uid }.take(keep).mapTo(HashSet()) { it.id }
                inFolder.filter { it.id !in kept }.map { it.id }
            }
    }
}
