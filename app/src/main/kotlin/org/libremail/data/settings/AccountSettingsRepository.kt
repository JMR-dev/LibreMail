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

    /** Per-account device-only retention overrides (null = inherit the global default; 0 = keep everything). */
    suspend fun setRetentionCount(accountId: String, count: Int?) = update(accountId) {
        it.copy(retentionCount = count?.coerceAtLeast(0))
    }

    suspend fun setRetentionMonths(accountId: String, months: Int?) = update(accountId) {
        it.copy(retentionMonths = months?.coerceAtLeast(0))
    }

    /**
     * Read-modify-writes an account's settings row through the DAO's single-transaction helper so a
     * concurrent per-field setter can't clobber the read-modify-write (issue #313). A missing row is
     * transformed from the account's defaults, preserving the "not configured yet = defaults" contract.
     */
    private suspend fun update(accountId: String, transform: (AccountSettings) -> AccountSettings) {
        dao.readModifyWrite(accountId) { stored ->
            transform(stored?.toDomain() ?: AccountSettings(accountId)).toEntity()
        }
    }
}
