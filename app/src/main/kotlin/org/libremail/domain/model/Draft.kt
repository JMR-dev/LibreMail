// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

data class Draft(
    val id: String,
    val accountId: String?,
    val to: String,
    val cc: String,
    val bcc: String = "",
    val subject: String,
    val body: String,
    val updatedAt: Long,
    val attachments: List<OutgoingAttachment> = emptyList(),
)
