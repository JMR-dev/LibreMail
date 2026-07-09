// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import org.libremail.mail.AuthThrottleGate
import org.libremail.mail.FetchedMessage
import org.libremail.mail.ImapClient
import org.libremail.power.BatteryStatus
import org.libremail.power.BatteryStatusProvider
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * The [MailMaintenanceGate]'s job is to serialize the two background maintenance jobs that both touch
 * older cached history — the full-history backfill ([MailBackfiller], #12) and the retention pruner
 * ([MailPruner], #13) — so they can never interleave (defence in depth behind the disjoint retention
 * floor). The `MailBackfiller`/`MailPruner` unit tests each construct a throwaway, *uncontended* gate,
 * so the serialization itself is never exercised there. These tests do: one asserts the gate's core
 * contract (a single exclusive lock), the other wires a real backfiller and a real pruner to the SAME
 * gate — the only object they share — and proves a concurrent prune blocks until the backfill releases.
 */
class MailMaintenanceGateTest {

    private val accountEntity = AccountEntity(
        id = "acct",
        email = "alice@example.org",
        displayName = "Alice",
        authType = "PASSWORD_IMAP",
        imap = ServerConfigEmbedded("127.0.0.1", 993, "NONE"),
        smtp = ServerConfigEmbedded("127.0.0.1", 465, "NONE"),
    )

    // issue #329: MailBackfiller/MailPruner now breadcrumb through AppLog, whose Logcat forwarding
    // (`android.util.Log`) is a no-op stub under plain JVM unit tests — mock it statically, mirroring
    // org.libremail.reporting.AppLogTest. The breadcrumbs themselves are asserted in
    // MailBackfillerTest/MailPrunerTest; this class only needs to not crash.
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

    /**
     * The gate's contract: everyone who acquires the *same* gate takes the *same* exclusive lock, so no
     * two critical sections overlap. Guards against a regression that hands out a fresh lock per access
     * (e.g. `val mutex get() = Mutex()`), which would compile but silently drop all serialization.
     */
    @Test
    fun oneGateSerializesConcurrentCriticalSections() = runBlocking {
        val gate = MailMaintenanceGate()
        val inside = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val jobs = (1..50).map {
            launch(Dispatchers.Default) {
                gate.mutex.withLock {
                    val depth = inside.incrementAndGet()
                    maxObserved.getAndUpdate { current -> maxOf(current, depth) }
                    yield() // invite a sibling to (wrongly) enter while we still hold the lock
                    inside.decrementAndGet()
                }
            }
        }
        jobs.joinAll()

        assertEquals(1, maxObserved.get(), "one gate must never allow two critical sections at once")
    }

    /**
     * A real backfill slice holds the gate across its (here, suspended) server fetch; a prune launched
     * concurrently must not enter its own critical section until the backfill releases the gate. The
     * backfiller and pruner share *only* the [MailMaintenanceGate], so a pass is attributable solely to
     * the gate.
     */
    @Test
    fun aConcurrentPruneWaitsForAnInFlightBackfillToReleaseTheGate() = runBlocking {
        val gate = MailMaintenanceGate()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val backfillInside = CompletableDeferred<Unit>()
        val releaseBackfill = CompletableDeferred<Unit>()

        val backfiller = backfiller(gate, events, backfillInside, releaseBackfill)
        val pruner = pruner(gate, events)

        // Backfill grabs the gate and parks inside it (its fetch is suspended on releaseBackfill).
        val backfillJob = launch(Dispatchers.Default) { backfiller.runBackfill() }
        backfillInside.await()

        // Prune now tries to run; it must block on the gate the backfill still holds.
        val pruneJob = launch(Dispatchers.Default) { pruner.prune() }
        delay(200)
        assertEquals(
            listOf("backfill-in"),
            events.toList(),
            "prune must not enter its critical section while backfill holds the gate",
        )

        // Release the backfill; only now may prune acquire the gate and run.
        releaseBackfill.complete(Unit)
        backfillJob.join()
        pruneJob.join()

        assertEquals(
            listOf("backfill-in", "prune-in"),
            events.toList(),
            "prune runs only after backfill releases the gate — never interleaved",
        )
    }

    /**
     * A [MailBackfiller] whose single server fetch parks inside the gate: it logs `backfill-in`, signals
     * [backfillInside], then suspends on [releaseBackfill] while still holding the lock. Everything else
     * is stubbed so exactly one bounded page is attempted.
     */
    private fun backfiller(
        gate: MailMaintenanceGate,
        events: MutableList<String>,
        backfillInside: CompletableDeferred<Unit>,
        releaseBackfill: CompletableDeferred<Unit>,
    ): MailBackfiller {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns listOf(accountEntity)

        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { messageDao.syncedFolders("acct") } returns listOf("INBOX")

        val imapClient = mockk<ImapClient>()
        coEvery { imapClient.fetchOlderThan(any(), any(), any(), any()) } coAnswers {
            events.add("backfill-in")
            backfillInside.complete(Unit)
            releaseBackfill.await()
            emptyList<FetchedMessage>()
        }

        val connectionFactory = mockk<MailConnectionFactory>()
        coEvery { connectionFactory.imapParamsFor(any()) } returns ImapConnectionParams(
            host = "h",
            port = 143,
            security = MailSecurity.NONE,
            username = "u",
            secret = "s",
            useXoauth2 = false,
        )

        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.settings } returns flowOf(AppSettings())

        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns AccountSettings("acct")

        return MailBackfiller(
            context = mockk(relaxed = true),
            accountDao = accountDao,
            messageDao = messageDao,
            backfillProgressDao = mockk(relaxed = true),
            imapClient = imapClient,
            connectionFactory = connectionFactory,
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            batteryStatusProvider = mockk<BatteryStatusProvider> {
                every { current() } returns BatteryStatus(percent = 100, isCharging = false)
            },
            mailRepository = mockk(relaxed = true),
            maintenanceGate = gate,
            throttleGate = AccountThrottleGate(),
            interactiveGate = InteractiveImapGate(),
            bandwidthTracker = GmailBandwidthTracker(),
            authGate = AuthThrottleGate(),
        )
    }

    /**
     * A [MailPruner] that logs `prune-in` the instant it enters its critical section, then no-ops
     * because retention is unlimited.
     */
    private fun pruner(gate: MailMaintenanceGate, events: MutableList<String>): MailPruner {
        val accountDao = mockk<AccountDao>()
        coEvery { accountDao.getAll() } returns listOf(accountEntity)

        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.settings } answers {
            events.add("prune-in") // first read happens inside prune()'s withLock
            flowOf(AppSettings())
        }

        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get("acct") } returns AccountSettings("acct")

        return MailPruner(
            context = mockk(relaxed = true),
            accountDao = accountDao,
            messageDao = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            accountSettingsRepository = accountSettingsRepository,
            maintenanceGate = gate,
        )
    }
}
