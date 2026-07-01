// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.entity.CredentialEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Stores one encrypted secret per account (OAuth AuthState JSON or IMAP password). */
@Singleton
class CredentialStore @Inject constructor(
    private val crypto: KeystoreCrypto,
    private val credentialDao: CredentialDao,
) {
    suspend fun saveSecret(accountId: String, secret: String) {
        credentialDao.upsert(CredentialEntity(accountId, crypto.encrypt(secret)))
    }

    suspend fun loadSecret(accountId: String): String? = credentialDao.getById(accountId)?.let {
        crypto.decrypt(it.encryptedSecret)
    }

    suspend fun delete(accountId: String) = credentialDao.deleteById(accountId)
}
