// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.repository

import java.io.File
import kotlinx.coroutines.flow.Flow
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutgoingMessage

/**
 * Abstraction over the local message cache (and, in later increments, network sync).
 * The UI always reads from here; the cache is the single source of truth.
 */
interface MailRepository {
    fun observeMessages(): Flow<List<Message>>

    suspend fun getMessage(id: String): Message?

    /** Loads a message for reading: fetches+caches the body if missing, and marks it read. */
    suspend fun openMessage(id: String): Result<Message>

    /** Cached attachment metadata for a message, populated when the message is opened. */
    fun observeAttachments(messageId: String): Flow<List<Attachment>>

    /** Downloads an attachment's bytes to a local cache file and returns it. */
    suspend fun downloadAttachment(messageId: String, partIndex: Int): Result<File>

    suspend fun setStarred(id: String, starred: Boolean): Result<Unit>

    suspend fun deleteMessage(id: String): Result<Unit>

    suspend fun sendMessage(outgoing: OutgoingMessage): Result<Unit>

    /** Drafts: composed-but-unsent messages saved for later. */
    fun observeDrafts(): Flow<List<Draft>>
    suspend fun getDraft(id: String): Draft?
    suspend fun saveDraft(draft: Draft)
    suspend fun deleteDraft(id: String)
}
