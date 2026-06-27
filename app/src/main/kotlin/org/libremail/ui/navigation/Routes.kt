// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.navigation

import android.net.Uri

/** Navigation routes. String-based to avoid extra serialization setup for the MVP. */
object Routes {
    const val MAILBOX = "mailbox"
    const val SETTINGS = "settings"
    const val ACCOUNT_SETUP = "account_setup"
    const val MANUAL_SETUP = "manual_setup"

    const val READER_ARG_ID = "messageId"
    const val READER_PATTERN = "reader/{$READER_ARG_ID}"
    fun reader(messageId: String) = "reader/$messageId"

    const val COMPOSE_ARG_TO = "to"
    const val COMPOSE_ARG_SUBJECT = "subject"
    const val COMPOSE_ARG_FROM = "from"
    const val COMPOSE_PATTERN = "compose?to={$COMPOSE_ARG_TO}&subject={$COMPOSE_ARG_SUBJECT}&from={$COMPOSE_ARG_FROM}"
    fun compose(to: String = "", subject: String = "", from: String = ""): String =
        "compose?to=${Uri.encode(to)}&subject=${Uri.encode(subject)}&from=${Uri.encode(from)}"
}
