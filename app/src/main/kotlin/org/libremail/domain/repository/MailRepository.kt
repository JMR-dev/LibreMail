// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.repository

import kotlinx.coroutines.flow.Flow
import org.libremail.domain.model.Message

/**
 * Abstraction over the local message cache (and, in later increments, network sync).
 * The UI always reads from here; the cache is the single source of truth.
 */
interface MailRepository {
    fun observeMessages(): Flow<List<Message>>

    suspend fun getMessage(id: String): Message?
}
