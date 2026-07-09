// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import jakarta.mail.MessagingException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.AuthThrottleGate
import org.libremail.mail.FetchedMessage
import org.libremail.mail.ImapClient
import org.libremail.mail.ProviderAuthPolicy
import org.libremail.notifications.MailNotifier
import org.libremail.power.BatteryStatus
import org.libremail.power.BatteryStatusProvider
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Also covers issue #329's net-new [AppLog] breadcrumbs: every test exercises real sync code, so
 * `android.util.Log` (a no-op stub under plain JVM unit tests) is statically mocked here for the whole
 * class, mirroring [org.libremail.reporting.AppLogTest]. [logBuffer] is fresh per test (a new
 * [MailSyncerTest] instance per `@Test`, per JUnit4) and installed before every test so the dedicated
 * breadcrumb tests below can assert against it directly.
 */
class MailSyncerTest {

    private val logBuffer = RingLogBuffer()

    private val account = AccountEntity(
        id = "acct",
        email = "a@example.org",
        displayName = "A",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    @Before
    fun setUp() {
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
        unmockkAll()
        DebugFetchGate.reset() // the gate is a process-global object; don't leak a pause to other tests
    }

    /** The IMAP client of the most recently built [syncer], for verifying the fetch window size. */
    private lateinit var lastImapClient: ImapClient

    /** The MessageDao of the most recently built [syncer], for verifying what got persisted. */
    private lateinit var lastMessageDao: MessageDao

    /** A syncer whose header sync is a no-op (no server messages) so tests focus on the prefetch step. */
    private fun syncer(
        policy: FetchPolicy,
        mailRepository: MailRepository,
        context: Context = mockk(relaxed = true),
        accountSettings: AccountSettings = AccountSettings("acct"),
        globalSettings: AppSettings = AppSettings(),
        battery: BatteryStatus = BatteryStatus(percent = 100, isCharging = false),
        fetched: List<FetchedMessage> = emptyList(),
        throttleGate: AccountThrottleGate = AccountThrottleGate(),
        bandwidthTracker: GmailBandwidthTracker = GmailBandwidthTracker(),
        authGate: AuthThrottleGate = AuthThrottleGate(),
        accountEntity: AccountEntity = account,
    ): MailSyncer {
        val accountDao = mockk<AccountDao>(relaxed = true)
        coEvery { accountDao.getById("acct") } returns accountEntity
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getSyncedIds(any(), any()) } returns emptyList()
        coEvery { messageDao.getUnfetchedIds("acct", "INBOX") } returns listOf("acct:INBOX:1")
        lastMessageDao = messageDao
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchRecent(any(), any(), any()) } returns fetched
        lastImapClient = imapClient
        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns realParams()
        val settingsRepository = mockk<SettingsRepository>()
        coEvery { settingsRepository.fetchPolicy() } returns policy
        every { settingsRepository.settings } returns flowOf(globalSettings)
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get(any()) } returns accountSettings
        return MailSyncer(
            context = context,
            accountDao = accountDao,
            messageDao = messageDao,
            imapClient = imapClient,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            batteryStatusProvider = batteryProvider(battery),
            notifier = mockk<MailNotifier>(relaxed = true),
            mailRepository = mailRepository,
            throttleGate = throttleGate,
            bandwidthTracker = bandwidthTracker,
            authGate = authGate,
        )
    }

    /** A real (non-mock) params object, so the proactive auth gate can key by host|port|username (#362). */
    private fun realParams() = ImapConnectionParams(
        host = "imap.example.org",
        port = 993,
        security = MailSecurity.SSL_TLS,
        username = "a@example.org",
        secret = "secret",
        useXoauth2 = false,
    )

    private fun batteryProvider(battery: BatteryStatus): BatteryStatusProvider =
        mockk<BatteryStatusProvider> { every { current() } returns battery }

    /**
     * Issue #362 fail-loud stop: an account already persisted as errored (its Yahoo/AOL auth circuit has
     * latched) is skipped entirely — no login attempt, no fetch — durably across restarts, since the skip
     * reads the persisted [AccountEntity.authError] rather than the in-memory gate. The sync still reports
     * success (contributes 0) so a healthy sibling account is unaffected.
     */
    @Test
    fun `an errored account is skipped without any fetch`() = runTest {
        val syncer = syncer(
            FetchPolicy.ON_DEMAND,
            mockk(relaxed = true),
            accountEntity = account.copy(authError = "Please remove and re-add this account with valid credentials"),
        )

        val result = syncer.syncFolder("acct", "INBOX")

        assertEquals(0, result.getOrNull(), "an errored account contributes nothing and does not fail the sync")
        coVerify(exactly = 0) { lastImapClient.fetchRecent(any(), any(), any()) }
        assertTrue(
            logBuffer.snapshot().any { it.message.startsWith("sync skip acct:") && it.message.contains("errored") },
            "a PII-free skip breadcrumb is recorded",
        )
    }

    /**
     * The mid-sync latch (#362): when the gate has already latched (e.g. via a prior IDLE/backfill failure)
     * but the account row is not yet stamped, the next sync marks it errored via [markAccountErroredIfLatched]
     * and skips the login rather than driving one the gate would only refuse.
     */
    @Test
    fun `a freshly latched account is marked errored and skipped without a fetch`() = runTest {
        val gate = AuthThrottleGate(
            nowMillis = { 0L },
            random = { 0.0 },
            policyForHost = { ProviderAuthPolicy.forHost("imap.mail.yahoo.com") },
        )
        repeat(ProviderAuthPolicy.YAHOO_AUTH_CIRCUIT_OPEN_THRESHOLD) { gate.onAuthFailure(realParams()) }
        val syncer = syncer(FetchPolicy.ON_DEMAND, mockk(relaxed = true), authGate = gate)

        val result = syncer.syncFolder("acct", "INBOX")

        assertEquals(0, result.getOrNull())
        coVerify(exactly = 0) { lastImapClient.fetchRecent(any(), any(), any()) }
        assertTrue(
            logBuffer.snapshot().any { it.message.startsWith("sync skip acct:") && it.message.contains("latched") },
        )
    }

    @Test
    fun `ALWAYS policy prefetches unfetched messages after the header sync`() = runTest {
        val repo = mockk<MailRepository>()
        coEvery { repo.prefetchMessage(any()) } returns Result.success(Unit)

        syncer(FetchPolicy.ALWAYS, repo).syncFolder("acct", "INBOX")

        coVerify { repo.prefetchMessage("acct:INBOX:1") }
    }

    @Test
    fun `ON_DEMAND policy never prefetches`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)

        syncer(FetchPolicy.ON_DEMAND, repo).syncFolder("acct", "INBOX")

        coVerify(exactly = 0) { repo.prefetchMessage(any()) }
    }

    @Test
    fun `caps the recent fetch window to a retention count below the default`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)

        syncer(FetchPolicy.ON_DEMAND, repo, accountSettings = AccountSettings("acct", retentionCount = 20))
            .syncFolder("acct", "INBOX")

        // Foreground must not fetch more than retention keeps, or it would re-download pruned rows.
        coVerify { lastImapClient.fetchRecent(any(), "INBOX", 20) }
    }

    @Test
    fun `uses the full window when the retention count exceeds it`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)

        syncer(FetchPolicy.ON_DEMAND, repo, accountSettings = AccountSettings("acct", retentionCount = 5000))
            .syncFolder("acct", "INBOX")

        coVerify { lastImapClient.fetchRecent(any(), "INBOX", 50) }
    }

    @Test
    fun `age-only retention leaves the full recent window intact`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)

        syncer(FetchPolicy.ON_DEMAND, repo, accountSettings = AccountSettings("acct", retentionMonths = 6))
            .syncFolder("acct", "INBOX")

        coVerify { lastImapClient.fetchRecent(any(), "INBOX", 50) }
    }

    @Test
    fun `age retention does not re-insert fetched messages older than the cutoff`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val recentTs = now - 10 * day // well within a 6-month window
        val oldTs = now - 400 * day // well past a 6-month window — the pruner would delete it
        val fetched = listOf(
            FetchedMessage("2", "New", "new@example.org", "recent", recentTs, isRead = true, isFlagged = false),
            FetchedMessage("1", "Old", "old@example.org", "stale", oldTs, isRead = true, isFlagged = false),
        )

        syncer(
            FetchPolicy.ON_DEMAND,
            repo,
            accountSettings = AccountSettings("acct", retentionMonths = 6),
            fetched = fetched,
        ).syncFolder("acct", "INBOX")

        // Only the in-window message is persisted; the past-cutoff one is never re-inserted, so the age
        // pruner won't just delete it again next cycle (#193 — no re-download/re-prune churn loop).
        coVerify { lastMessageDao.insertNew(match { batch -> batch.map { it.timestampMillis } == listOf(recentTs) }) }
    }

    @Test
    fun `WIFI_ONLY prefetches on an unmetered network`() = runTest {
        val repo = mockk<MailRepository>()
        coEvery { repo.prefetchMessage(any()) } returns Result.success(Unit)

        syncer(FetchPolicy.WIFI_ONLY, repo, context = networkContext(unmetered = true)).syncFolder("acct", "INBOX")

        coVerify { repo.prefetchMessage("acct:INBOX:1") }
    }

    @Test
    fun `WIFI_ONLY does not prefetch on a metered network`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)

        syncer(FetchPolicy.WIFI_ONLY, repo, context = networkContext(unmetered = false)).syncFolder("acct", "INBOX")

        coVerify(exactly = 0) { repo.prefetchMessage(any()) }
    }

    @Test
    fun `low battery pauses prefetch even for ALWAYS, leaving the header sync untouched`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)
        val lowBattery = BatteryStatus(percent = 15, isCharging = false)

        val result = syncer(FetchPolicy.ALWAYS, repo, battery = lowBattery).syncFolder("acct", "INBOX")

        assertEquals(0, result.getOrNull()) // header sync still ran and succeeded
        coVerify(exactly = 0) { repo.prefetchMessage(any()) }
    }

    // --- issue #393: debug-only fetch gate ------------------------------------------------------

    @Test
    fun `a paused prefetch gate skips prefetch while the header sync still runs`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)
        DebugFetchGate.pause(setOf(FetchScope.PREFETCH))

        // ALWAYS + healthy battery: without the gate this WOULD prefetch, so the gate is the only cause.
        val result = syncer(FetchPolicy.ALWAYS, repo).syncFolder("acct", "INBOX")

        assertEquals(0, result.getOrNull()) // header sync still ran and succeeded
        coVerify { lastImapClient.fetchRecent(any(), "INBOX", any()) } // headers still fetched
        coVerify(exactly = 0) { repo.prefetchMessage(any()) }
        assertTrue(logBuffer.snapshot().any { it.message == "prefetch skipped: fetch-gate paused" })
    }

    @Test
    fun `at exactly the 20 percent threshold prefetch is paused`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)
        val threshold = BatteryStatus(percent = 20, isCharging = false)

        syncer(FetchPolicy.ALWAYS, repo, battery = threshold).syncFolder("acct", "INBOX")

        coVerify(exactly = 0) { repo.prefetchMessage(any()) }
    }

    @Test
    fun `a low battery on the charger still prefetches`() = runTest {
        val repo = mockk<MailRepository>()
        coEvery { repo.prefetchMessage(any()) } returns Result.success(Unit)
        val chargingLow = BatteryStatus(percent = 15, isCharging = true)

        syncer(FetchPolicy.ALWAYS, repo, battery = chargingLow).syncFolder("acct", "INBOX")

        coVerify { repo.prefetchMessage("acct:INBOX:1") }
    }

    @Test
    fun `prefetch resumes on the next sync once battery recovers`() = runTest {
        val repo = mockk<MailRepository>()
        coEvery { repo.prefetchMessage(any()) } returns Result.success(Unit)
        val recovered = BatteryStatus(percent = 21, isCharging = false)

        syncer(FetchPolicy.ALWAYS, repo, battery = recovered).syncFolder("acct", "INBOX")

        coVerify { repo.prefetchMessage("acct:INBOX:1") }
    }

    // --- issue #361: Gmail bandwidth-aware prefetch pacing ----------------------------------------

    @Test
    fun `gmail prefetch defers once the daily download budget is reached, leaving the header sync untouched`() =
        runTest {
            val repo = mockk<MailRepository>(relaxed = true)
            val gmailAccount = account.copy(imap = ServerConfigEmbedded("imap.gmail.com", 993, "SSL_TLS"))
            val tracker = GmailBandwidthTracker().apply {
                recordDownload("acct", GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES)
            }

            val result = syncer(
                FetchPolicy.ALWAYS,
                repo,
                accountEntity = gmailAccount,
                bandwidthTracker = tracker,
            ).syncFolder("acct", "INBOX")

            assertEquals(0, result.getOrNull()) // header sync still ran and succeeded
            coVerify(exactly = 0) { repo.prefetchMessage(any()) }
            assertTrue(logBuffer.snapshot().any { it.message.startsWith("prefetch deferred acct:") })
        }

    @Test
    fun `a non-gmail account's prefetch is unaffected by an over-budget gmail bandwidth tracker`() = runTest {
        val repo = mockk<MailRepository>()
        coEvery { repo.prefetchMessage(any()) } returns Result.success(Unit)
        val tracker = GmailBandwidthTracker().apply {
            recordDownload("acct", GmailSyncLimits.DAILY_DOWNLOAD_BUDGET_BYTES)
        }

        // accountEntity defaults to the fixture's non-Gmail (imap.example.org) host.
        syncer(FetchPolicy.ALWAYS, repo, bandwidthTracker = tracker).syncFolder("acct", "INBOX")

        coVerify { repo.prefetchMessage("acct:INBOX:1") }
    }

    @Test
    fun `notifies for new mail when both global and per-account notifications are enabled`() = runTest {
        val notifier = mockk<MailNotifier>(relaxed = true)
        notifyingSyncer(globalEnabled = true, accountEnabled = true, notifier = notifier).syncAccount("acct")

        coVerify { notifier.notifyNewMail(any(), any()) }
    }

    @Test
    fun `does not notify when the account has notifications disabled`() = runTest {
        val notifier = mockk<MailNotifier>(relaxed = true)
        notifyingSyncer(globalEnabled = true, accountEnabled = false, notifier = notifier).syncAccount("acct")

        coVerify(exactly = 0) { notifier.notifyNewMail(any(), any()) }
    }

    @Test
    fun `does not notify when the global master toggle is off`() = runTest {
        val notifier = mockk<MailNotifier>(relaxed = true)
        notifyingSyncer(globalEnabled = false, accountEnabled = true, notifier = notifier).syncAccount("acct")

        coVerify(exactly = 0) { notifier.notifyNewMail(any(), any()) }
    }

    /**
     * A syncer whose INBOX already has a synced row (so it's not a first sync) and whose next fetch
     * returns one new unread message — so the notify path is reached, gated only by the toggles.
     */
    private fun notifyingSyncer(globalEnabled: Boolean, accountEnabled: Boolean, notifier: MailNotifier): MailSyncer {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getById("acct") } returns account
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getSyncedIds("acct", "INBOX") } returns listOf("acct:INBOX:0")
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchRecent(any(), any(), any()) } returns listOf(
            FetchedMessage("1", "Ada", "ada@example.org", "Hi", 1_000L, isRead = false, isFlagged = false),
        )
        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns realParams()
        val settingsRepository = mockk<SettingsRepository>()
        coEvery { settingsRepository.isNewMailNotificationsEnabled() } returns globalEnabled
        coEvery { settingsRepository.fetchPolicy() } returns FetchPolicy.ON_DEMAND
        every { settingsRepository.settings } returns flowOf(AppSettings())
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns
            AccountSettings("acct", notificationsEnabled = accountEnabled)
        return MailSyncer(
            context = mockk(relaxed = true),
            accountDao = accountDao,
            messageDao = messageDao,
            imapClient = imapClient,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            batteryStatusProvider = batteryProvider(BatteryStatus(percent = 100, isCharging = false)),
            notifier = notifier,
            mailRepository = mockk(relaxed = true),
            throttleGate = AccountThrottleGate(),
            bandwidthTracker = GmailBandwidthTracker(),
            authGate = AuthThrottleGate(),
        )
    }

    @Test
    fun `syncAll succeeds with a zero total when there are no accounts`() = runTest {
        val syncer = syncAllSyncer(accounts = emptyList())

        val result = syncer.syncAll()

        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `syncAll syncs every account inbox and sums the fetched counts`() = runTest {
        val syncer = syncAllSyncer(accounts = listOf(accountEntity("one"), accountEntity("two")))

        val result = syncer.syncAll()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()) // one message fetched per account
    }

    @Test
    fun `syncAll still succeeds when one account fails but another syncs`() = runTest {
        val syncer = syncAllSyncer(
            accounts = listOf(accountEntity("ok"), accountEntity("bad")),
            failingIds = setOf("bad"),
        )

        val result = syncer.syncAll()

        assertTrue(result.isSuccess, "at least one account synced")
        assertEquals(1, result.getOrNull())
    }

    @Test
    fun `syncAll fails only when every account fails`() = runTest {
        val syncer = syncAllSyncer(
            accounts = listOf(accountEntity("bad1"), accountEntity("bad2")),
            failingIds = setOf("bad1", "bad2"),
        )

        assertTrue(syncer.syncAll().isFailure)
    }

    private fun accountEntity(id: String) = account.copy(id = id, email = "$id@example.org")

    /**
     * A syncer for the [MailSyncer.syncAll] path: [accountDao.getAll] returns [accounts], each inbox
     * fetch yields one message (so a successful account contributes 1 to the total), and any account
     * in [failingIds] fails its connection so its per-account sync errors out.
     */
    private fun syncAllSyncer(accounts: List<AccountEntity>, failingIds: Set<String> = emptySet()): MailSyncer {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns accounts
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getSyncedIds(any(), any()) } returns emptyList()
        coEvery { messageDao.getUnfetchedIds(any(), any()) } returns emptyList()
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchRecent(any(), any(), any()) } returns listOf(
            FetchedMessage("1", "Ada", "ada@example.org", "Hi", 1_000L, isRead = true, isFlagged = false),
        )
        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(match { it.id in failingIds }) } throws IOException("no network")
        coEvery { connectionFactory.imapParamsFor(match { it.id !in failingIds }) } returns realParams()
        val settingsRepository = mockk<SettingsRepository>()
        coEvery { settingsRepository.fetchPolicy() } returns FetchPolicy.ON_DEMAND
        coEvery { settingsRepository.isNewMailNotificationsEnabled() } returns false
        every { settingsRepository.settings } returns flowOf(AppSettings())
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get(any()) } returns AccountSettings("acct")
        return MailSyncer(
            context = mockk(relaxed = true),
            accountDao = accountDao,
            messageDao = messageDao,
            imapClient = imapClient,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            batteryStatusProvider = batteryProvider(BatteryStatus(percent = 100, isCharging = false)),
            notifier = mockk(relaxed = true),
            mailRepository = mockk(relaxed = true),
            throttleGate = AccountThrottleGate(),
            bandwidthTracker = GmailBandwidthTracker(),
            authGate = AuthThrottleGate(),
        )
    }

    /** A context whose active network reports the given metered state via ConnectivityManager. */
    private fun networkContext(unmetered: Boolean): Context {
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns unmetered
        val network = mockk<Network>()
        val manager = mockk<ConnectivityManager>()
        every { manager.activeNetwork } returns network
        every { manager.getNetworkCapabilities(network) } returns capabilities
        return mockk<Context>(relaxed = true).also {
            every { it.getSystemService(ConnectivityManager::class.java) } returns manager
        }
    }

    // --- issue #329: AppLog breadcrumbs ---------------------------------------------------------

    @Test
    fun `syncFolder records a debug breadcrumb with the account ref, folder, and fetched count`() = runTest {
        val repo = mockk<MailRepository>(relaxed = true)
        val fetched = listOf(
            FetchedMessage("1", "Ada", "ada@example.org", "Secret subject", 1_000L, isRead = true, isFlagged = false),
        )

        syncer(FetchPolicy.ON_DEMAND, repo, fetched = fetched).syncFolder("acct", "INBOX")

        val entry = logBuffer.snapshot().single()
        assertEquals('D', entry.level)
        assertTrue(entry.message.contains("folder=INBOX"), entry.message)
        assertTrue(entry.message.contains("fetched=1"), entry.message)
        assertNoPii(entry.message)
    }

    @Test
    fun `syncAll logs a start breadcrumb and a done breadcrumb with the fetched total`() = runTest {
        val syncer = syncAllSyncer(accounts = listOf(accountEntity("one"), accountEntity("two")))

        syncer.syncAll()

        val snapshot = logBuffer.snapshot()
        val start = snapshot.first { it.message.startsWith("sync all:") }
        val done = snapshot.first { it.message.startsWith("sync all done:") }
        assertEquals('I', start.level)
        assertEquals("sync all: 2 accounts", start.message)
        assertEquals('I', done.level)
        assertEquals("sync all done: fetched=2", done.message)
        snapshot.forEach { assertNoPii(it.message) }
    }

    @Test
    fun `syncAll logs a warning breadcrumb with the scrubbed failure when every account fails`() = runTest {
        val syncer = syncAllSyncer(
            accounts = listOf(accountEntity("bad1"), accountEntity("bad2")),
            failingIds = setOf("bad1", "bad2"),
        )

        syncer.syncAll()

        val entry = logBuffer.snapshot().first { it.message.startsWith("sync all failed") }
        assertEquals('W', entry.level)
        // The throwable's class survives the scrub; its free-text message does not (issue #325).
        assertTrue(entry.message.contains("IOException"), entry.message)
        assertFalse(entry.message.contains("no network"), entry.message)
        logBuffer.snapshot().forEach { assertNoPii(it.message) }
    }

    // --- issue #360: foreground sync feeds the throttle gate (interactive priority) -------------

    /**
     * A foreground sync that hits provider throttling records a per-account backoff (so the background
     * backfill defers that account) but is itself never blocked — the failure still surfaces to the
     * caller. This is the interactive-priority half of #360: interactive work informs the gate, it is
     * never gated by it.
     */
    @Test
    fun `a foreground sync hitting throttling records a backoff without being blocked`() = runTest {
        val gate = AccountThrottleGate()
        val syncer = syncer(FetchPolicy.ON_DEMAND, mockk(relaxed = true), throttleGate = gate)
        coEvery { lastImapClient.fetchRecent(any(), any(), any()) } throws
            MessagingException("A2 NO [THROTTLED] Too many requests")

        val result = syncer.syncFolder("acct", "INBOX")

        assertTrue(result.isFailure, "the throttle failure still surfaces to the caller")
        assertTrue(gate.isThrottled("acct"), "and it records a backoff so background backfill defers")
    }

    /** An ordinary (non-throttle) sync failure must NOT arm a backoff. */
    @Test
    fun `a foreground sync failing on an ordinary error does not record a backoff`() = runTest {
        val gate = AccountThrottleGate()
        val syncer = syncer(FetchPolicy.ON_DEMAND, mockk(relaxed = true), throttleGate = gate)
        coEvery { lastImapClient.fetchRecent(any(), any(), any()) } throws IOException("Connection reset")

        assertTrue(syncer.syncFolder("acct", "INBOX").isFailure)
        assertFalse(gate.isThrottled("acct"))
    }

    /** No test fixture's email address or host may ever reach a log line — the hard PII rule. */
    private fun assertNoPii(message: String) {
        assertFalse(message.contains("@example.org"), message)
        assertFalse(message.contains("example.org"), message)
    }
}
