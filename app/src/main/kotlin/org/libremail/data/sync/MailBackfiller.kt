// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import org.libremail.mail.AuthThrottleGate
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
    private val throttleGate: AccountThrottleGate,
    private val interactiveGate: InteractiveImapGate,
    private val bandwidthTracker: GmailBandwidthTracker,
    private val authGate: AuthThrottleGate,
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
            // Skip an account backing off (throttle #360 / auth #362) or with unresolvable credentials —
            // a null return does NOT set moreWork, so a slice whose only work is a backing-off account
            // reports "done" instead of tight-looping over the skip. See [paramsForBackfill].
            val params = paramsForBackfill(account) ?: continue@accounts
            val policy = accountSettingsRepository.effectiveRetention(settingsRepository, account.id)
            for (folder in messageDao.syncedFolders(account.id)) {
                if (remaining <= 0) {
                    // The budget ran out before every folder was visited, so there is very likely more
                    // work left even though nothing here reported it directly.
                    moreWork = true
                    break@accounts
                }
                // null == this folder throttled/locked the account (already recorded): stop paging the
                // account for the rest of the slice — graceful degradation, not a hard failure, and not a
                // moreWork spin against a server that just told us to slow down.
                val result = pageFolder(account, params, folder, policy, remaining) ?: continue@accounts
                remaining -= result.batches
                // A page landed, so the account is healthy again — clear any lingering backoff (no-op and
                // silent when it was never throttled).
                if (result.batches > 0) throttleGate.onSuccess(account.id)
                if (result.moreWork) moreWork = true
            }
        }
        AppLog.i(TAG, "backfill slice done: moreWork=$moreWork")
        moreWork
    }

    /**
     * Resolves [account] to its IMAP connection params for this slice, or **null when the account must be
     * skipped without paging** — it is inside a reactive throttle-backoff window (#360) or a proactive
     * auth-backoff window (#362), or its credentials could not be resolved. Both backoffs are graceful
     * degradation with per-account isolation: we don't page a provider that just rate-limited or locked us
     * (hammering makes throttling worse — the on-device perf finding), and we don't drive a login that
     * [org.libremail.mail.ImapClient] would only skip anyway, nudging a Yahoo/AOL account toward its
     * ~1-hour lockout. Each window elapses on its own so a later scheduled slice resumes; a skip logs a
     * PII-free breadcrumb and its caller does NOT set moreWork, so a slice whose only outstanding work is a
     * backing-off account reports "done" rather than tight-looping over the skip.
     */
    private suspend fun paramsForBackfill(account: Account): ImapConnectionParams? {
        val throttleRemaining = throttleGate.remainingBackoffMillis(account.id)
        if (throttleRemaining > 0L) {
            AppLog.i(TAG, "backfill skip ${accountLogRef(account.id)}: throttled, remaining=${throttleRemaining}ms")
            return null
        }
        val params = runCatching { connectionFactory.imapParamsFor(account) }.getOrNull() ?: return null
        val authBlock = authGate.remainingAuthBlockMillis(params)
        if (authBlock > 0L) {
            AppLog.i(TAG, "backfill skip ${accountLogRef(account.id)}: auth backing off, remaining=${authBlock}ms")
            return null
        }
        return params
    }

    /**
     * Pages one folder, translating a failure into the slice's control flow (issue #360). Returns the
     * [FolderResult] on success — or, for an ordinary transient error, a zero-page result whose
     * [FolderResult.moreWork] asks for a follow-up slice (unchanged behaviour). Returns **null** when the
     * failure classifies as provider throttling/lockout ([ThrottleClassifier]): the backoff is recorded
     * against the account (exponential + jitter, via [AccountThrottleGate]) and the caller stops paging
     * this account for the rest of the slice, so we degrade gracefully instead of hammering a server that
     * just told us to slow down (the on-device perf finding, `docs/perf/issue-125-*`). Cancellation
     * propagates so a WorkManager stop / IDLE renewal ends the run promptly.
     */
    private suspend fun pageFolder(
        account: Account,
        params: ImapConnectionParams,
        folder: String,
        policy: RetentionPolicy,
        maxBatches: Int,
    ): FolderResult? = try {
        backfillFolder(account, params, folder, policy, maxBatches)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val signal = ThrottleClassifier.classify(e)
        if (signal == null) {
            FolderResult(batches = 0, moreWork = true)
        } else {
            throttleGate.onThrottle(account.id, signal)
            null
        }
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
            // Yield to any in-flight interactive fetch (#355) BEFORE starting the next page: opening a
            // message must win the account's IMAP throughput, so park here while a user fetch holds the
            // gate and resume the instant it clears. A page already fetched (if any) finished above; the
            // interactive fetch simply wins the next server round-trip. This is also the slice's first
            // yield point — iteration 0 runs it before the very first page — so a slice never begins a
            // page while the user is waiting on a body.
            yieldToInteractive(account)
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
            prefetchIfEnabled(account, entities.map { it.id })
            // Breathe between pages so a large mailbox doesn't hammer the server.
            delay(BACKFILL_BATCH_DELAY_MS)
        }
        if (complete) markComplete(account.id, folder, beforeUid)
        val folderLabel = logSafeFolderLabel(folder)
        AppLog.d(TAG, "backfill ${accountLogRef(account.id)} folder=$folderLabel pages=$batches complete=$complete")
        return FolderResult(batches, moreWork = !complete && !stalled)
    }

    /**
     * Parks the backfill (suspends) while an interactive, user-facing IMAP fetch is in flight (#355), so
     * the reader's on-demand body fetch wins the account's throughput instead of queuing behind the
     * background storm. Checks first so the common idle case adds no cost and logs nothing; only an actual
     * yield brackets a PII-free park/resume breadcrumb (hashed account ref only — see #358). The gate's
     * counter is released by [InteractiveImapGate.withInteractive]'s `finally` even when the interactive
     * fetch errors, so this can never deadlock.
     */
    private suspend fun yieldToInteractive(account: Account) {
        if (!interactiveGate.isInteractiveActive()) return
        AppLog.i(TAG, "backfill parking ${accountLogRef(account.id)}: interactive fetch in flight")
        interactiveGate.awaitInteractiveIdle()
        AppLog.i(TAG, "backfill resumed ${accountLogRef(account.id)}: interactive fetch cleared")
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
    private suspend fun prefetchIfEnabled(account: Account, ids: List<String>) {
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
        // Gmail-specific proactive bandwidth pacing (#361): once this account's tracked downloads for
        // today reach Gmail's documented daily budget, defer body/attachment prefetch for the rest of
        // the day instead of continuing to spend it — header paging above is unaffected, and a fresh
        // cycle resumes automatically once the day rolls over (GmailBandwidthTracker). Orthogonal to
        // the #360 throttle skip above (which only fires once the provider actually rejects a request)
        // and #356's BackfillPacer (which paces slice cadence, not bytes) — same graceful-degradation
        // shape as both, composing rather than duplicating either.
        if (GmailSyncLimits.appliesTo(account) && bandwidthTracker.isOverDailyBudget(account.id)) {
            AppLog.i(TAG, "prefetch deferred ${accountLogRef(account.id)}: Gmail daily download budget reached")
            return
        }
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
