// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.libremail.data.local.dao.AccountSettingsDao
import org.libremail.data.local.toDomain
import org.libremail.data.local.toEntity
import org.libremail.domain.model.AccountSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes per-account preferences. A missing row is treated as the defaults for that
 * account, so callers never have to special-case "not configured yet".
 */
@Singleton
class AccountSettingsRepository @Inject constructor(private val dao: AccountSettingsDao) {
    fun observe(accountId: String): Flow<AccountSettings> = dao.observe(accountId).map {
        it?.toDomain()
            ?: AccountSettings(accountId)
    }

    suspend fun get(accountId: String): AccountSettings = dao.get(accountId)?.toDomain() ?: AccountSettings(accountId)

    /** Inserts a default settings row for a freshly-added account (no-op if one already exists). */
    suspend fun ensureDefaults(accountId: String) {
        if (dao.get(accountId) == null) dao.upsert(AccountSettings(accountId).toEntity())
    }

    suspend fun setSignature(accountId: String, signature: String) = update(accountId) {
        it.copy(signature = signature)
    }

    suspend fun setSignatureEnabled(accountId: String, enabled: Boolean) = update(accountId) {
        it.copy(signatureEnabled = enabled)
    }

    suspend fun setNotificationsEnabled(accountId: String, enabled: Boolean) = update(accountId) {
        it.copy(notificationsEnabled = enabled)
    }

    private suspend inline fun update(accountId: String, transform: (AccountSettings) -> AccountSettings) {
        dao.upsert(transform(get(accountId)).toEntity())
    }
}
