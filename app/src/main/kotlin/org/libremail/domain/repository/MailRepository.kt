// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.repository

import kotlinx.coroutines.flow.Flow
import org.libremail.domain.model.Attachment
import org.libremail.domain.model.Draft
import org.libremail.domain.model.Folder
import org.libremail.domain.model.Message
import org.libremail.domain.model.OutboxMessage
import org.libremail.domain.model.OutgoingMessage
import org.libremail.domain.model.ReplyMode
import org.libremail.domain.model.UnreadCount
import java.io.File

/**
 * Abstraction over the local message cache (and, in later increments, network sync).
 * The UI always reads from here; the cache is the single source of truth.
 */
interface MailRepository {
    /**
     * The cached messages of one account's [folder], newest-first — the scoped source for the mailbox
     * list (issue #86). Includes transient server-search rows so search can surface them; the caller
     * filters `inInbox`/query. Re-emits only when that folder's rows change, not on every cache write.
     */
    fun observeFolderMessages(accountId: String, folder: String): Flow<List<Message>>

    /** Like [observeFolderMessages] but for [folder] across every account (the unified inbox). */
    fun observeUnifiedFolderMessages(folder: String): Flow<List<Message>>

    /** The account's cached IMAP folders for the navigation drawer. */
    fun observeFolders(accountId: String): Flow<List<Folder>>

    /**
     * Live per-(account, folder) unread counts across every account, for the drawer's folder badges
     * and the bold styling of accounts with unread mail. Only (account, folder) pairs that currently
     * hold unread, folder-synced mail are emitted.
     */
    fun observeUnreadCounts(): Flow<List<UnreadCount>>

    /** Refreshes the account's folder list from the server into the cache. */
    suspend fun refreshFolders(accountId: String): Result<Unit>

    suspend fun getMessage(id: String): Message?

    /** Loads a message for reading: fetches+caches the body if missing, and marks it read. */
    suspend fun openMessage(id: String): Result<Message>

    /** Cached attachment metadata for a message, populated when the message is opened. */
    fun observeAttachments(messageId: String): Flow<List<Attachment>>

    /** Downloads an attachment's bytes to a local cache file and returns it. */
    suspend fun downloadAttachment(messageId: String, partIndex: Int): Result<File>

    /** Part indexes of a message whose attachment bytes are already cached on disk (openable offline). */
    suspend fun downloadedAttachmentParts(messageId: String): Set<Int>

    /**
     * Pre-caches a message's full content (body + every attachment's bytes) without marking it read.
     * Used by aggressive sync so opening the message — and its attachments — is instant and works offline.
     */
    suspend fun prefetchMessage(messageId: String): Result<Unit>

    suspend fun setStarred(id: String, starred: Boolean): Result<Unit>

    suspend fun deleteMessage(id: String): Result<Unit>

    /** Moves messages to each account's Archive folder. Fails if an account has no archive folder. */
    suspend fun archive(ids: List<String>): Result<Unit>

    /** Moves messages to each account's Spam/Junk folder. Fails if an account has no spam folder. */
    suspend fun reportSpam(ids: List<String>): Result<Unit>

    /** Moves messages to each account's Trash folder, falling back to a permanent delete if there is none. */
    suspend fun trash(ids: List<String>): Result<Unit>

    /** Permanently deletes messages from their current folder (IMAP delete + expunge). */
    suspend fun expunge(ids: List<String>): Result<Unit>

    /** Moves messages to a specific destination folder (used by the explicit "Move" picker). */
    suspend fun moveToFolder(ids: List<String>, destFolderFullName: String): Result<Unit>

    /**
     * Fetches the original message and builds a pre-filled reply/forward draft, returning its id so the
     * caller can open the compose screen on that draft.
     */
    suspend fun buildReplyDraft(messageId: String, mode: ReplyMode): Result<String>

    suspend fun sendMessage(outgoing: OutgoingMessage): Result<Unit>

    /** Drafts: composed-but-unsent messages saved for later. */
    fun observeDrafts(): Flow<List<Draft>>
    suspend fun getDraft(id: String): Draft?
    suspend fun saveDraft(draft: Draft)
    suspend fun deleteDraft(id: String)

    /** The outbox: messages queued for sending. */
    fun observeOutbox(): Flow<List<OutboxMessage>>
    suspend fun cancelOutboxMessage(id: String)
    suspend fun retryOutbox()

    /**
     * Fetches server-side search matches into the cache so the message list can surface them.
     * Scoped to [folder]; [accountId] null searches every account (e.g. the unified inbox).
     */
    suspend fun searchServer(query: String, accountId: String?, folder: String)

    /** Drops transient server-search hits from the cache (called when search is dismissed). */
    suspend fun clearSearchResults()
}
