// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.repository

import kotlinx.coroutines.flow.Flow
import org.libremail.domain.model.Account
import org.libremail.domain.model.ImapConnectionParams

/** Account management: list, add (with a live connection test), and remove accounts. */
interface AccountRepository {
    fun observeAccounts(): Flow<List<Account>>

    /** Attempt an IMAP login and return the server's folder names. */
    suspend fun testConnection(params: ImapConnectionParams): Result<List<String>>

    /** Verify, then persist, a password/app-password IMAP account. Returns the folders found. */
    suspend fun addImapAccount(account: Account, password: String): Result<List<String>>

    /** Verify (via XOAUTH2), then persist, an Outlook account. Returns the folders found. */
    suspend fun addOutlookAccount(email: String, accessToken: String, authStateJson: String): Result<List<String>>

    suspend fun deleteAccount(id: String)
}
