// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Encrypted secret for an account: either an OAuth [net.openid.appauth.AuthState] JSON
 * blob or an IMAP password. The value is AES-GCM ciphertext (see KeystoreCrypto), never
 * plaintext.
 */
@Entity(tableName = "credentials")
data class CredentialEntity(@PrimaryKey val accountId: String, val encryptedSecret: String)
