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
import org.libremail.BuildConfig
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
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
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
    /** One folder's slice outcome: pages fetched, and whether an immediate follow-up slice has work to do. */
    private data class FolderResult(val batches: Int, val moreWork: Boolean)

    /**
     * Runs one bounded slice of backfill across all accounts and their synced folders. Does at most
     * [maxBatches] server pages total, persisting progress after each, then returns whether an
     * immediate follow-up slice has more work to do (so the caller may chain another run). A folder
     * that stalled (see the unresolved-UID guard in [backfillFolder]) stays incomplete but does not
     * count as more work — it is retried on a future scheduled run instead of spun on back-to-back.
     */
    suspend fun runBackfill(maxBatches: Int = DEFAULT_MAX_BATCHES): Boolean = maintenanceGate.mutex.withLock {
        AppLog.i(TAG, "backfill slice: maxBatches=$maxBatches")
        var remaining = maxBatches
        var moreWork = false
        accounts@ for (account in accountDao.getAll().map { it.toDomain() }) {
            val params = runCatching { connectionFactory.imapParamsFor(account) }.getOrNull() ?: continue
            val policy = accountSettingsRepository.effectiveRetention(settingsRepository, account.id)
            for (folder in messageDao.syncedFolders(account.id)) {
                if (remaining <= 0) {
                    // The budget ran out before every folder was visited, so there is very likely more
                    // work left even though nothing here reported it directly.
                    moreWork = true
                    break@accounts
                }
                // Per-folder failures (e.g. a transient server error) must not abort the whole slice.
                val result = runCatching { backfillFolder(account, params, folder, policy, remaining) }
                    .getOrElse { FolderResult(batches = 0, moreWork = true) }
                remaining -= result.batches
                if (result.moreWork) moreWork = true
            }
        }
        AppLog.i(TAG, "backfill slice done: moreWork=$moreWork")
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
            return FolderResult(batches = 0, moreWork = false)
        }

        // Resume from the persisted low-water mark so paging is monotonic: it never re-descends into a
        // region an earlier run already reached, even after the pruner deletes rows above it. Falls back
        // to the lowest currently-cached UID on the very first run, before any progress is persisted.
        // Both sources are guarded against unresolved-UID placeholders (#95): lowestSyncedUid excludes
        // `uid <= 0` rows at the SQL level, and a stale persisted boundary `<= 0` is discarded rather
        // than trusted — fetchOlderThan treats such a bound as "nothing older", which would falsely
        // mark the folder fully backfilled.
        var beforeUid = progress?.nextBeforeUid?.takeIf { it > 0L }
            ?: messageDao.lowestSyncedUid(account.id, folder)
            ?: Long.MAX_VALUE
        var batches = 0
        var complete = false
        var stalled = false
        while (batches < maxBatches) {
            currentCoroutineContext().ensureActive()
            // Count floor (#13 precedence): once the folder holds as many messages as retention keeps,
            // stop before fetching another page. Unlike the age floor below, this can be decided from
            // the cache alone: the count pruner keeps the newest-N by UID — exactly the order paging
            // descends in — so a Date/UID inversion cannot make it stop early.
            if (reachedCountFloor(account.id, folder, policy)) {
                complete = true
                break
            }
            val fetched = imapClient.fetchOlderThan(params, folder, beforeUid, BACKFILL_BATCH_SIZE)
            batches++
            val entities = fetched.map { it.toEntity(account.id, folder) }
            // Stop at the genuine end of the folder, or at the age floor (#13): a page ENTIRELY older
            // than the Date cutoff. The age decision is made from the page actually fetched, NOT from
            // the oldest cached timestamp — paging is by UID (arrival order) while the floor cuts by
            // the Date header, so a single high-UID message with an old Date (moved/imported mail)
            // would drag the cached minimum below the cutoff and end paging while within-retention
            // history is still unfetched (#94). An entirely-old page is pure prune-fodder, so it is
            // not persisted either. Both cases mark the folder complete (rather than pausing): the
            // pruner deleting aged-out rows must never re-open paging on the next run, forever
            // re-downloading what was just pruned. A retention change resets progress
            // (AccountRepository.resetBackfillProgress) so loosening still resumes paging.
            if (fetched.isEmpty() || entirelyBeyondAgeFloor(entities, policy)) {
                complete = true
                break
            }
            persistBatch(entities)
            // Derive the next boundary only from resolved UIDs (#95): a row whose UID the server
            // failed to resolve (UIDFolder.getUID returns -1) must not collapse the boundary to <= 0,
            // where fetchOlderThan reads "nothing older" and the folder would be FALSELY marked fully
            // backfilled. If a whole page came back unresolved, stall the folder: not complete (so a
            // later run retries once the server behaves) but claiming no more work either — an
            // immediate follow-up slice would just spin on the same page.
            val nextBeforeUid = entities.mapNotNull { entity -> entity.uid.takeIf { it > 0L } }.minOrNull()
            if (nextBeforeUid == null) {
                stalled = true
                break
            }
            beforeUid = nextBeforeUid
            backfillProgressDao.upsert(BackfillProgressEntity(account.id, folder, beforeUid, complete = false))
            prefetchIfEnabled(entities.map { it.id })
            // Breathe between pages so a large mailbox doesn't hammer the server.
            delay(BACKFILL_BATCH_DELAY_MS)
        }
        if (complete) markComplete(account.id, folder, beforeUid)
        val folderLabel = logSafeFolderLabel(folder)
        AppLog.d(TAG, "backfill ${accountLogRef(account.id)} folder=$folderLabel pages=$batches complete=$complete")
        return FolderResult(batches, moreWork = !complete && !stalled)
    }

    /** True once the folder already holds as many messages as the count retention keeps (or more). */
    private suspend fun reachedCountFloor(accountId: String, folder: String, policy: RetentionPolicy): Boolean {
        val limit = policy.countLimit ?: return false
        return messageDao.countSynced(accountId, folder) >= limit
    }

    /**
     * True when a fetched page sits entirely below the age retention floor — every message on it is
     * older than the policy's Date cutoff. Deciding per page (rather than from the single oldest
     * cached timestamp) makes the floor robust to Date/UID inversions (#94): one old-Dated high-UID
     * message ends paging only if a whole page around it is old too. The trade is deliberate — a
     * pathologically interleaved mailbox may over-fetch (the pruner reclaims the excess), but
     * backfill never silently gaps within-retention history.
     */
    private fun entirelyBeyondAgeFloor(page: List<MessageEntity>, policy: RetentionPolicy): Boolean {
        val cutoff = policy.ageCutoffMillis(System.currentTimeMillis()) ?: return false
        return page.isNotEmpty() && page.all { it.timestampMillis < cutoff }
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
            // Refresh every pre-existing row's header in ONE transaction (issue #322) rather than a per-row
            // UPDATE each in its own implicit transaction — the same batched path foreground sync uses
            // (issue #310). N per-row commits fsync the journal once per message (amplified on the
            // encrypted cache); routing the whole batch through updateHeaderContents collapses them into a
            // single commit per page. Semantically identical: it applies updateHeaderContent to each row in
            // list order, so the same rows get the same values (and the same casefold columns).
            messageDao.updateHeaderContents(toRefresh)
        }
        AppLog.d(TAG, "backfill persist: fetched=${entities.size} refreshed=${toRefresh.size}")
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
        // Debug-only fetch gate (issue #393): pause proactive body prefetch so a later open is a genuine
        // uncached fetch. Header paging above is untouched (its own gate is the BackfillWorker entry), so
        // history still lands; a skipped body is filled in lazily on open. Compiled out of release
        // (BuildConfig.DEBUG is a compile-time false, so R8 drops the branch).
        if (BuildConfig.DEBUG && DebugFetchGate.isPaused(FetchScope.PREFETCH)) {
            AppLog.i(TAG, "prefetch skipped: fetch-gate paused")
            return
        }
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
        const val TAG = "MailBackfiller"

        /** Headers fetched per server page. */
        const val BACKFILL_BATCH_SIZE = 50

        /** Server pages per WorkManager run, keeping one run well under the OS execution limit. */
        const val DEFAULT_MAX_BATCHES = 20

        /** Pause between pages to spread server load on large mailboxes. */
        const val BACKFILL_BATCH_DELAY_MS = 250L
    }
}
