// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** A message the user is sending. [to]/[cc] are comma-separated address lists. */
data class OutgoingMessage(
    val accountId: String,
    val to: String,
    val cc: String = "",
    val subject: String,
    val body: String,
)
