// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import java.io.File

/**
 * The per-message on-disk attachment cache directory, keyed by a filesystem-safe form of the message
 * id. Shared by the writer ([org.libremail.data.repository.MailRepositoryImpl]) and the retention
 * pruner ([org.libremail.data.sync.MailPruner]) so the two can never disagree on where a message's
 * attachments live — a divergence would silently leak orphaned files that the pruner no longer finds.
 */
internal fun attachmentCacheDir(cacheDir: File, messageId: String): File {
    val safeId = messageId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return File(cacheDir, "attachments/$safeId")
}
