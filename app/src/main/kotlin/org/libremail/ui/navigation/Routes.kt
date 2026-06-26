// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.navigation

/** Navigation routes. String-based to avoid extra serialization setup for the MVP. */
object Routes {
    const val MAILBOX = "mailbox"
    const val SETTINGS = "settings"
    const val COMPOSE = "compose"
    const val ACCOUNT_SETUP = "account_setup"

    const val READER_ARG_ID = "messageId"
    const val READER_PATTERN = "reader/{$READER_ARG_ID}"
    fun reader(messageId: String) = "reader/$messageId"
}
