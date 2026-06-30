// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

/**
 * Abstraction over mailbox syncing so UI callers (and their tests) depend on this rather than the
 * concrete [MailSyncer], whose many collaborators make it impractical to fake.
 */
interface Syncer {
    suspend fun syncAll(): Result<Int>
    suspend fun syncAccount(accountId: String): Result<Int>
    suspend fun syncFolder(accountId: String, folder: String): Result<Int>
}
