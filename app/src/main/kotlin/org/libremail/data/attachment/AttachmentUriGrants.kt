// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.attachment

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.toOutgoingAttachments
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Releases the persistable read grants the compose pickers take on attachment / inline-image URIs.
 * `ComposeScreen` calls `takePersistableUriPermission` on every pick, but nothing released them, so
 * the app accumulated indefinite read access to every file/photo ever attached and could hit the
 * per-app persisted-grant cap — after which a silently-swallowed take fails a later draft's image
 * reload (post-batch security review, Low).
 *
 * The picked bytes are copied into the app cache when a message is enqueued
 * (`MailRepositoryImpl.copyAttachments`), so a grant is only truly needed to reload an image when a
 * *draft* is reopened. Callers therefore invoke [releaseUnreferenced] once the referencing row is
 * gone — a draft is deleted, or an outbox message is sent or cancelled — passing that row's URIs.
 * A URI still referenced by another live draft or outbox row is kept; releasing a grant we do not
 * actually hold is expected and swallowed.
 */
@Singleton
class AttachmentUriGrants @Inject constructor(
    @ApplicationContext private val context: Context,
    private val draftDao: DraftDao,
    private val outboxDao: OutboxDao,
) {
    /**
     * Releases the persistable grant of each URI in [uris] that no *remaining* draft or outbox row
     * still references. Call it after deleting the row that referenced them, so that row no longer
     * counts toward the "still referenced" check.
     */
    suspend fun releaseUnreferenced(uris: Collection<String>) {
        if (uris.isEmpty()) return
        unreferencedUris(uris, referencedUris()).forEach(::release)
    }

    /** Every attachment / inline-image URI still referenced by a draft or a queued outbox message. */
    private suspend fun referencedUris(): Set<String> {
        val fromDrafts = draftDao.getAll().flatMap { it.attachments.toOutgoingAttachments() }
        val fromOutbox = outboxDao.getAll().flatMap { it.attachments.toOutgoingAttachments() }
        return (fromDrafts + fromOutbox).mapTo(HashSet()) { it.uri }
    }

    private fun release(uri: String) {
        // Only a grant we actually hold can be released; a URI never persisted (or already released)
        // throws SecurityException, which is expected here and deliberately ignored.
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}

/**
 * The distinct URIs in [candidates] not present in [referenced] — i.e. the grants safe to release.
 * Kept as a pure top-level function so the release decision is unit-testable without Android types.
 */
internal fun unreferencedUris(candidates: Collection<String>, referenced: Set<String>): List<String> =
    candidates.distinct().filterNot { it in referenced }
