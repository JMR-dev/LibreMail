// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

data class Attachment(
    val messageId: String,
    val partIndex: Int,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)
