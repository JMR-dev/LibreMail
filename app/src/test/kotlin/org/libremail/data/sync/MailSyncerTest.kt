// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.FetchedMessage
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier
import org.libremail.power.BatteryStatus
import org.libremail.power.BatteryStatusProvider
import kotlin.test.assertEquals

class MailSyncerTest {

    private val account = AccountEntity(
        id = "acct",
        email = "a@example.org",
        displayName = "A",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
        smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
    )

    /** The IMAP client of the most recently built [syncer], for verifying the fetch window size. */
    private lateinit var lastImapClient: ImapClient

    /** A syncer whose header sync is a no-op (no server messages) so tests focus on the prefetch step. */
    private fun syncer(
        policy: FetchPolicy,
        mailRepository: MailRepository,
        context: Context = mockk(relaxed = true),
        accountSettings: AccountSettings = AccountSettings("acct"),
        globalSettings: AppSettings = AppSettings(),
        battery: BatteryStatus = BatteryStatus(percent = 100, isCharging = false),
    ): MailSyncer {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getById("acct") } returns account
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.getSyncedIds(any(), any()) } returns emptyList()
        coEvery { messageDao.getUnfetchedIds("acct", "INBOX") } returns listOf("acct:INBOX:1")
        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchRecent(any(), any(), any()) } returns emptyList()
        lastImapClient = imapClient
        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns mockk<ImapConnectionParams>()
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
        )
    }

    private fun batteryProvider(battery: BatteryStatus): BatteryStatusProvider =
        mockk<BatteryStatusProvider> { every { current() } returns battery }

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
        coEvery { connectionFactory.imapParamsFor(any()) } returns mockk<ImapConnectionParams>()
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
}
