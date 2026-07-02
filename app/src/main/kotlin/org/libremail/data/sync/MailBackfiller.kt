// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.entity.BackfillProgressEntity
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.RetentionPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.effectiveRetention
import org.libremail.domain.model.Account
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.ImapClient
import org.libremail.power.BatteryStatusProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pages the *entire* history of every account's synced folders into the local cache (issue #12),
 * newest-to-oldest, one bounded slice per invocation so a single WorkManager run stays comfortably
 * under the OS time limit. Progress is a persisted per-folder UID boundary
 * ([BackfillProgressEntity]), so a run interrupted by process death or network loss resumes exactly
 * where it left off; foreground sync / pull-to-refresh are never blocked (this runs off the sync
 * mutex).
 *
 * Backfill only ever *inserts* older headers — it performs no deletion reconcile — and it stops
 * paging a folder once the account's device-only retention floor (#13) is reached, so it can never
 * fight the pruner over the same messages.
 */
@Singleton
class MailBackfiller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val backfillProgressDao: BackfillProgressDao,
    private val imapClient: ImapClient,
    private val connectionFactory: MailConnectionFactory,
    private val settingsRepository: SettingsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val batteryStatusProvider: BatteryStatusProvider,
    private val mailRepository: MailRepository,
    private val maintenanceGate: MailMaintenanceGate,
) {
    private data class FolderResult(val batches: Int, val complete: Boolean)

    /**
     * Runs one bounded slice of backfill across all accounts and their synced folders. Does at most
     * [maxBatches] server pages total, persisting progress after each, then returns whether any
     * folder still has history left to fetch (so the caller may schedule another run sooner).
     */
    suspend fun runBackfill(maxBatches: Int = DEFAULT_MAX_BATCHES): Boolean = maintenanceGate.mutex.withLock {
        var remaining = maxBatches
        var moreWork = false
        for (account in accountDao.getAll().map { it.toDomain() }) {
            val params = runCatching { connectionFactory.imapParamsFor(account) }.getOrNull() ?: continue
            val policy = accountSettingsRepository.effectiveRetention(settingsRepository, account.id)
            for (folder in messageDao.syncedFolders(account.id)) {
                if (remaining <= 0) return@withLock true
                // Per-folder failures (e.g. a transient server error) must not abort the whole slice.
                val result = runCatching { backfillFolder(account, params, folder, policy, remaining) }
                    .getOrElse { FolderResult(batches = 0, complete = false) }
                remaining -= result.batches
                if (!result.complete) moreWork = true
            }
        }
        moreWork
    }

    private suspend fun backfillFolder(
        account: Account,
        params: ImapConnectionParams,
        folder: String,
        policy: RetentionPolicy,
        maxBatches: Int,
    ): FolderResult {
        val progress = backfillProgressDao.get(account.id, folder)
        if (progress?.complete == true) {
            return FolderResult(batches = 0, complete = true)
        }

        // Resume from the persisted low-water mark so paging is monotonic: it never re-descends into a
        // region an earlier run already reached, even after the pruner deletes rows above it. Falls back
        // to the lowest currently-cached UID on the very first run, before any progress is persisted.
        var beforeUid = progress?.nextBeforeUid
            ?: messageDao.lowestSyncedUid(account.id, folder)
            ?: Long.MAX_VALUE
        var batches = 0
        while (batches < maxBatches) {
            currentCoroutineContext().ensureActive()
            // Retention floor (#13 precedence): once the folder holds everything retention keeps, mark it
            // complete and stop. Marking complete (rather than pausing) is what keeps backfill and the
            // pruner from fighting: otherwise the pruner deleting aged-out rows would raise the oldest
            // cached timestamp back above the age cutoff and re-open paging on the next run, forever. A
            // retention change resets progress (AccountRepository.resetBackfillProgress) so loosening
            // still resumes paging.
            if (reachedRetentionFloor(account.id, folder, policy)) {
                markComplete(account.id, folder, beforeUid)
                return FolderResult(batches, complete = true)
            }
            val fetched = imapClient.fetchOlderThan(params, folder, beforeUid, BACKFILL_BATCH_SIZE)
            batches++
            if (fetched.isEmpty()) {
                // Genuine end of the folder — mark complete so it is skipped on future runs.
                markComplete(account.id, folder, beforeUid)
                return FolderResult(batches, complete = true)
            }
            val entities = fetched.map { it.toEntity(account.id, folder) }
            persistBatch(entities)
            beforeUid = entities.minOf { it.uid }
            backfillProgressDao.upsert(BackfillProgressEntity(account.id, folder, beforeUid, complete = false))
            prefetchIfEnabled(entities.map { it.id })
            // Breathe between pages so a large mailbox doesn't hammer the server.
            delay(BACKFILL_BATCH_DELAY_MS)
        }
        return FolderResult(batches, complete = false)
    }

    /** True once the folder already holds as much as the retention policy would keep (or more). */
    private suspend fun reachedRetentionFloor(accountId: String, folder: String, policy: RetentionPolicy): Boolean {
        if (policy.isUnlimited) return false
        policy.countLimit?.let { limit ->
            if (messageDao.countSynced(accountId, folder) >= limit) return true
        }
        policy.ageCutoffMillis(System.currentTimeMillis())?.let { cutoff ->
            val oldest = messageDao.oldestSyncedTimestamp(accountId, folder)
            if (oldest != null && oldest < cutoff) return true
        }
        return false
    }

    /** Inserts backfilled headers; never deletes. Uncancellable so a persisted boundary always has its rows. */
    private suspend fun persistBatch(entities: List<MessageEntity>) = withContext(NonCancellable) {
        val ids = entities.map { it.id }
        // insertNew (IGNORE) writes brand-new rows in full — headers, uid, and inInbox = 1 — so only rows
        // that ALREADY existed (e.g. a former search-only row) need their membership/header refreshed.
        // Limiting the updates to those avoids a redundant per-row UPDATE for every freshly-inserted row.
        val preexisting = messageDao.existingIds(ids).toHashSet()
        messageDao.insertNew(entities)
        val toRefresh = entities.filter { it.id in preexisting }
        if (toRefresh.isNotEmpty()) {
            messageDao.markSynced(toRefresh.map { it.id })
            toRefresh.forEach {
                messageDao.updateHeaderContent(
                    id = it.id,
                    sender = it.sender,
                    senderEmail = it.senderEmail,
                    subject = it.subject,
                    timestampMillis = it.timestampMillis,
                    uid = it.uid,
                )
            }
        }
    }

    private suspend fun markComplete(accountId: String, folder: String, nextBeforeUid: Long) {
        backfillProgressDao.upsert(BackfillProgressEntity(accountId, folder, nextBeforeUid, complete = true))
    }

    /**
     * Pre-caches each backfilled message's body/attachments per the user's fetch policy (headers
     * first, bodies per policy — issue #12), pausing at low battery regardless of policy — the same
     * [SyncResourcePolicy.shouldPrefetchContent] gate as [MailSyncer]'s prefetch, so the foreground
     * and backfill content paths can never disagree (#88/#89). Header paging above is not gated here:
     * WorkManager's battery-not-low constraint on the backfill work is the scheduler-level control.
     * Best-effort and cancellable between messages so an interruption stops promptly; anything not
     * fetched is filled in lazily when the message is opened.
     */
    private suspend fun prefetchIfEnabled(ids: List<String>) {
        val shouldPrefetch = SyncResourcePolicy.shouldPrefetchContent(
            policy = settingsRepository.fetchPolicy(),
            unmetered = { context.isActiveNetworkUnmetered() },
            battery = batteryStatusProvider.current(),
        )
        if (!shouldPrefetch) return
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            mailRepository.prefetchMessage(id)
        }
    }

    private companion object {
        /** Headers fetched per server page. */
        const val BACKFILL_BATCH_SIZE = 50

        /** Server pages per WorkManager run, keeping one run well under the OS execution limit. */
        const val DEFAULT_MAX_BATCHES = 20

        /** Pause between pages to spread server load on large mailboxes. */
        const val BACKFILL_BATCH_DELAY_MS = 250L
    }
}
