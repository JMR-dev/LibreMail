// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.navigation

import android.net.Uri

/** Navigation routes. String-based to avoid extra serialization setup for the MVP. */
object Routes {
    const val MAILBOX = "mailbox"
    const val SETTINGS = "settings"
    const val ACCOUNT_SETUP = "account_setup"
    const val MANUAL_SETUP = "manual_setup"
    const val DRAFTS = "drafts"
    const val OUTBOX = "outbox"

    const val READER_ARG_ID = "messageId"
    const val READER_PATTERN = "reader/{$READER_ARG_ID}"
    fun reader(messageId: String) = "reader/${Uri.encode(messageId)}"

    const val ACCOUNT_SETTINGS_ARG_ID = "accountId"
    const val ACCOUNT_SETTINGS_PATTERN = "account_settings/{$ACCOUNT_SETTINGS_ARG_ID}"
    fun accountSettings(accountId: String) = "account_settings/${Uri.encode(accountId)}"

    const val COMPOSE_ARG_TO = "to"
    const val COMPOSE_ARG_CC = "cc"
    const val COMPOSE_ARG_BCC = "bcc"
    const val COMPOSE_ARG_SUBJECT = "subject"
    const val COMPOSE_ARG_BODY = "body"
    const val COMPOSE_ARG_FROM = "from"
    const val COMPOSE_ARG_DRAFT = "draft"
    const val COMPOSE_PATTERN =
        "compose?to={$COMPOSE_ARG_TO}&cc={$COMPOSE_ARG_CC}&bcc={$COMPOSE_ARG_BCC}" +
            "&subject={$COMPOSE_ARG_SUBJECT}&body={$COMPOSE_ARG_BODY}" +
            "&from={$COMPOSE_ARG_FROM}&draft={$COMPOSE_ARG_DRAFT}"

    /**
     * Builds a compose route. Every field is URL-encoded so recipients, subjects and bodies that
     * contain `&`, `=`, spaces or newlines (e.g. from a `mailto:` link) round-trip through the
     * NavHost into the compose form intact.
     */
    fun compose(
        to: String = "",
        subject: String = "",
        from: String = "",
        cc: String = "",
        bcc: String = "",
        body: String = "",
    ): String = "compose?to=${Uri.encode(to)}&cc=${Uri.encode(cc)}&bcc=${Uri.encode(bcc)}" +
        "&subject=${Uri.encode(subject)}&body=${Uri.encode(body)}&from=${Uri.encode(from)}"

    fun composeDraft(draftId: String): String = "compose?draft=${Uri.encode(draftId)}"
}
