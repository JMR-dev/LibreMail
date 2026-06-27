// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** A message waiting in the outbox. [lastError] is null while queued, set after a failed attempt. */
data class OutboxMessage(
    val id: String,
    val to: String,
    val subject: String,
    val body: String,
    val createdAt: Long,
    val lastError: String?,
)
