// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import org.libremail.data.attachmentCacheDir
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.RetentionPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.effectiveRetention
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enforces device-only retention (issue #13): deletes locally cached messages (and, via the Room
 * foreign key, their attachment metadata) that exceed each account's retention limit, then removes
 * their on-disk attachment cache files. It NEVER contacts the server — pruning is purely local, so
 * the mail stays on the server and is re-fetchable later.
 *
 * Precedence with the #12 backfill is guaranteed two ways: backfill stops paging at the same
 * retention floor this pruner deletes below (their working sets are disjoint), and both jobs share
 * [MailMaintenanceGate] so they never run at once.
 */
@Singleton
class MailPruner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val accountSettingsRepository: AccountSettingsRepository,
    private val maintenanceGate: MailMaintenanceGate,
) {
    /** Prunes every account to its effective retention policy. Returns the number of messages removed. */
    suspend fun prune(nowMillis: Long = System.currentTimeMillis()): Int = maintenanceGate.mutex.withLock {
        var removed = 0
        for (account in accountDao.getAll()) {
            currentCoroutineContext().ensureActive()
            val policy = accountSettingsRepository.effectiveRetention(settingsRepository, account.id)
            if (policy.isUnlimited) continue
            removed += pruneAccount(account.id, policy, nowMillis)
        }
        removed
    }

    private suspend fun pruneAccount(accountId: String, policy: RetentionPolicy, nowMillis: Long): Int {
        val victimIds = LinkedHashSet<String>()

        // Age limit: prunable across every folder in one query.
        policy.ageCutoffMillis(nowMillis)?.let { cutoff ->
            victimIds += messageDao.syncedIdsOlderThan(accountId, cutoff)
        }
        // Count limit: keep the newest N of EACH folder; prune the rest.
        policy.countLimit?.let { limit ->
            for (folder in messageDao.syncedFolders(accountId)) {
                victimIds += messageDao.syncedIdsBeyondCountInFolder(accountId, folder, limit)
            }
        }

        if (victimIds.isEmpty()) return 0
        val ids = victimIds.toList()
        // Delete DB rows first (attachment metadata cascades via the foreign key), then their cache files.
        // Chunk the id list so a first prune of a large backfilled mailbox stays under SQLite's
        // host-parameter limit (999 on API 29) for the `IN (...)` clause.
        ids.chunked(DELETE_CHUNK).forEach { chunk -> messageDao.deleteByIds(chunk) }
        ids.forEach { deleteCacheFiles(it) }
        return ids.size
    }

    /** Removes the per-message on-disk attachment cache (keyed the same way MailRepositoryImpl writes it). */
    private fun deleteCacheFiles(messageId: String) {
        runCatching { attachmentCacheDir(context.cacheDir, messageId).deleteRecursively() }
    }

    private companion object {
        /** Ids per DELETE, kept under SQLite's 999-host-parameter limit on older Android. */
        const val DELETE_CHUNK = 500
    }
}
