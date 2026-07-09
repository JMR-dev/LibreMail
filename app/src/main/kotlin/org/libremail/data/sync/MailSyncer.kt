// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libremail.BuildConfig
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.effectiveRetention
import org.libremail.domain.model.Account
import org.libremail.domain.repository.MailRepository
import org.libremail.mail.ImapClient
import org.libremail.notifications.MailNotifier
import org.libremail.power.BatteryStatusProvider
import org.libremail.reporting.AppLog
import org.libremail.reporting.accountLogRef
import javax.inject.Inject
import javax.inject.Singleton

/** Fetches account folders' headers into Room and notifies about newly-arrived inbox mail. */
@Singleton
class MailSyncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val imapClient: ImapClient,
    private val connectionFactory: MailConnectionFactory,
    private val settingsRepository: SettingsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val batteryStatusProvider: BatteryStatusProvider,
    private val notifier: MailNotifier,
    private val mailRepository: MailRepository,
    private val throttleGate: AccountThrottleGate,
    private val bandwidthTracker: GmailBandwidthTracker,
) : Syncer {
    // Serializes all syncing: syncAll/syncAccount/syncFolder are invoked concurrently by the periodic
    // worker, pull-to-refresh, one-shot syncs, folder opens, and one IDLE watcher per account. Without
    // this, two runs can both compute the same message as "new" (double-notify) or let a stale
    // deleteSyncedInWindowNotIn snapshot delete a row another run just inserted.
    private val syncMutex = Mutex()

    /** Syncs every account's inbox. Succeeds if at least one account synced (or there are none). */
    override suspend fun syncAll(): Result<Int> {
        val accounts = accountDao.getAll().map { it.toDomain() }
        AppLog.i(TAG, "sync all: ${accounts.size} accounts")
        if (accounts.isEmpty()) return Result.success(0)

        val result = syncMutex.withLock {
            var total = 0
            var firstError: Throwable? = null
            var anySuccess = false
            for (account in accounts) {
                syncFolderHeaders(account, INBOX, notify = true).fold(
                    onSuccess = { fetched ->
                        total += fetched
                        anySuccess = true
                    },
                    onFailure = { error -> if (firstError == null) firstError = error },
                )
            }
            if (anySuccess || firstError == null) Result.success(total) else Result.failure(firstError)
        }
        result.onSuccess { total -> AppLog.i(TAG, "sync all done: fetched=$total") }
            .onFailure { error -> AppLog.w(TAG, "sync all failed", error) }
        if (result.isSuccess) accounts.forEach { prefetchIfEnabled(it, INBOX) }
        return result
    }

    /** Syncs one account's inbox — used by the per-account IDLE watcher so a push doesn't re-sync all. */
    override suspend fun syncAccount(accountId: String): Result<Int> {
        val account = accountDao.getById(accountId)?.toDomain() ?: return Result.success(0)
        val result = syncMutex.withLock { syncFolderHeaders(account, INBOX, notify = true) }
        if (result.isSuccess) prefetchIfEnabled(account, INBOX)
        return result
    }

    /** Syncs one (account, folder) on demand — used when a folder is opened or refreshed (no notify). */
    override suspend fun syncFolder(accountId: String, folder: String): Result<Int> {
        val account = accountDao.getById(accountId)?.toDomain() ?: return Result.success(0)
        val result = syncMutex.withLock { syncFolderHeaders(account, folder, notify = false) }
        if (result.isSuccess) prefetchIfEnabled(account, folder)
        return result
    }

    private suspend fun syncFolderHeaders(account: Account, folder: String, notify: Boolean): Result<Int> =
        runCatching {
            val params = connectionFactory.imapParamsFor(account)
            val policy = accountSettingsRepository.effectiveRetention(settingsRepository, account.id)
            // Never fetch more of the recent window than device-only retention (#13) would keep. Without
            // this, a count limit BELOW the window would make foreground sync re-download the same rows
            // the pruner just trimmed, on every sync — an endless re-download/re-prune fight.
            val window = policy.countLimit?.let { minOf(FETCH_LIMIT, it) } ?: FETCH_LIMIT
            val fetched = imapClient.fetchRecent(params, folder, window) // cancellable network I/O
            // Age-based retention (#193): drop anything older than the age cutoff before persisting. On a
            // low-traffic mailbox the newest-N can extend PAST the cutoff, so without this a sync re-inserts
            // rows the age pruner just deleted and the next prune deletes them again — a churn loop. Count/
            // unlimited modes have a null cutoff and keep the full window, so their behavior is unchanged.
            val cutoff = policy.ageCutoffMillis(System.currentTimeMillis())
            val entities = fetched.map { it.toEntity(account.id, folder) }
                .let { mapped -> if (cutoff == null) mapped else mapped.filter { it.timestampMillis >= cutoff } }

            // Persist and notify atomically with respect to cancellation: an IDLE renewal that cancels
            // mid-sync must not drop a notification (the rows would then look "already seen" next time).
            withContext(NonCancellable) {
                val existingIds = messageDao.getSyncedIds(account.id, folder).toHashSet()
                // Don't notify on the very first sync of a folder (would announce everything in it).
                val newMessages = if (existingIds.isEmpty()) {
                    emptyList()
                } else {
                    entities.filter { it.id !in existingIds && !it.isRead }
                }

                if (fetched.isEmpty()) {
                    // An empty recent window means the server folder itself is empty, so nothing (not
                    // even backfilled history) should remain cached for it. Keyed on the raw fetch, not the
                    // age-filtered set: a folder holding only mail older than the age cutoff is NOT empty on
                    // the server, so its stale local rows are left to the pruner rather than wiped here.
                    messageDao.deleteSyncedByAccountFolder(account.id, folder)
                } else {
                    val ids = entities.map { it.id }
                    messageDao.insertNew(entities)
                    // Mark every fetched message as synced (upgrades any former search-only row) and refresh
                    // its display fields — without touching cached bodies or optimistic read/star flags. The
                    // per-row refreshes run in a single transaction (issue #310) so a whole recent window
                    // costs one commit instead of one fsync per message (amplified on the encrypted cache).
                    messageDao.markSynced(ids)
                    messageDao.updateHeaderContents(entities)
                    // Reconcile server-side deletions ONLY within the fetched recent-UID window, so older
                    // history paged in by the background backfill (issue #12) survives each foreground sync
                    // instead of being wiped by a whole-folder "not in the recent 50" delete. Bound the
                    // window by the lowest POSITIVE fetched UID: a message whose UID couldn't be resolved
                    // (UIDFolder.getUID returns -1) must not collapse the bound to <= 0 and turn this into a
                    // whole-folder delete that wipes the backfilled history below the window.
                    val minWindowUid = entities.mapNotNull { entity -> entity.uid.takeIf { it > 0L } }.minOrNull()
                    if (minWindowUid != null) {
                        messageDao.deleteSyncedInWindowNotIn(account.id, folder, minWindowUid, ids)
                    }
                }

                val shouldNotify = notify &&
                    newMessages.isNotEmpty() &&
                    settingsRepository.isNewMailNotificationsEnabled() &&
                    accountSettingsRepository.get(account.id).notificationsEnabled
                if (shouldNotify) {
                    notifier.notifyNewMail(account, newMessages.sortedByDescending { it.timestampMillis })
                }
            }
            val folderLabel = logSafeFolderLabel(folder)
            AppLog.d(TAG, "sync ${accountLogRef(account.id)} folder=$folderLabel fetched=${fetched.size}")
            fetched.size
        }.onFailure { error ->
            // Interactive priority (#360): a foreground sync that hits provider throttling records it so
            // the background backfill backs this account off — but the interactive sync itself is never
            // blocked by the gate, so opening/refreshing mail is never queued behind a backfill backoff.
            ThrottleClassifier.classify(error)?.let { throttleGate.onThrottle(account.id, it) }
        }

    /**
     * Aggressively pre-caches each not-yet-fetched message's full content (body + attachments) per the
     * user's fetch policy, pausing at low battery regardless of policy — see
     * [SyncResourcePolicy.shouldPrefetchContent] (#89). The header sync above is never gated: mail
     * keeps arriving, and a skipped prefetch is simply retried on the next sync once battery/network
     * recover. Runs outside [syncMutex] so these downloads don't block pull-to-refresh or other syncs,
     * and is cancellable between messages so an IDLE renewal stops it promptly.
     */
    private suspend fun prefetchIfEnabled(account: Account, folder: String) {
        // Debug-only fetch gate (issue #393): a test harness pauses proactive body prefetch so a later
        // open does a genuine uncached fetch. Header sync above already ran, so mail still arrives; the
        // skipped prefetch is filled in lazily on open, exactly as the low-battery pause behaves. Compiled
        // out of release (BuildConfig.DEBUG is a compile-time false, so R8 drops the branch).
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
        // Gmail-specific proactive bandwidth pacing (#361): once this account's tracked downloads for
        // today reach Gmail's documented daily budget, defer body/attachment prefetch for the rest of
        // the day instead of continuing to spend it — header sync above is unaffected, and a fresh
        // cycle resumes automatically once the day rolls over (GmailBandwidthTracker). Orthogonal to
        // #360's reactive AccountThrottleGate (only fires once the provider actually rejects a request)
        // and #356's BackfillPacer (paces backfill slice cadence, not bytes) — same graceful-degradation
        // shape as both, composing rather than duplicating either.
        if (GmailSyncLimits.appliesTo(account) && bandwidthTracker.isOverDailyBudget(account.id)) {
            AppLog.i(TAG, "prefetch deferred ${accountLogRef(account.id)}: Gmail daily download budget reached")
            return
        }
        for (id in messageDao.getUnfetchedIds(account.id, folder)) {
            currentCoroutineContext().ensureActive()
            mailRepository.prefetchMessage(id) // best-effort; swallows its own per-message failures
        }
    }

    private companion object {
        const val TAG = "MailSyncer"
        const val INBOX = "INBOX"

        /**
         * Size of the "recent window" each foreground sync / pull-to-refresh fetches (newest N headers).
         * This is no longer the history cap — the background backfill ([MailBackfiller], issue #12)
         * pages in everything older; foreground sync just keeps this recent window fresh and reconciles
         * deletions within it.
         */
        const val FETCH_LIMIT = 50
    }
}
