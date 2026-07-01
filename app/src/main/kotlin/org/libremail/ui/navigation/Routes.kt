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
    const val PROBLEM_REPORTS = "problem_reports"

    // The mailbox can be opened filtered to a single account (used when onboarding lands the user on
    // the first account they added). The bare MAILBOX route resolves here with an empty account arg,
    // so it stays a valid start destination and bottom-tab target.
    const val MAILBOX_ARG_ACCOUNT = "account"
    const val MAILBOX_PATTERN = "mailbox?account={$MAILBOX_ARG_ACCOUNT}"
    fun mailboxForAccount(accountId: String) = "mailbox?account=${Uri.encode(accountId)}"

    // App-password guided setup, parameterized by provider key (see MailProvider). Reused by both
    // onboarding and the standalone "Add account" entry.
    const val APP_PASSWORD_ARG_PROVIDER = "provider"
    const val APP_PASSWORD_PATTERN = "app_password/{$APP_PASSWORD_ARG_PROVIDER}"
    fun appPassword(provider: String) = "app_password/${Uri.encode(provider)}"

    // Onboarding first-run flow (nested graph). ONBOARDING is the graph route; the rest are its
    // destinations. The graph owns the "first account added this session" state via a graph-scoped
    // ViewModel, so the picker/setup screens are registered inside it for onboarding and reused as
    // the top-level ACCOUNT_SETUP / APP_PASSWORD / MANUAL_SETUP routes for "Add account" later.
    const val ONBOARDING = "onboarding"
    const val ONBOARDING_WELCOME = "onboarding/welcome"
    const val ONBOARDING_PICKER = "onboarding/picker"
    const val ONBOARDING_MANUAL = "onboarding/manual"
    const val ONBOARDING_ADD_ANOTHER = "onboarding/add_another"
    const val ONBOARDING_APP_PASSWORD_PATTERN = "onboarding/app_password/{$APP_PASSWORD_ARG_PROVIDER}"
    fun onboardingAppPassword(provider: String) = "onboarding/app_password/${Uri.encode(provider)}"

    const val READER_ARG_ID = "messageId"
    const val READER_PATTERN = "reader/{$READER_ARG_ID}"
    fun reader(messageId: String) = "reader/${Uri.encode(messageId)}"

    const val ACCOUNT_SETTINGS_ARG_ID = "accountId"
    const val ACCOUNT_SETTINGS_PATTERN = "account_settings/{$ACCOUNT_SETTINGS_ARG_ID}"
    fun accountSettings(accountId: String) = "account_settings/${Uri.encode(accountId)}"

    const val REPORT_REVIEW_ARG_ID = "reportId"
    const val REPORT_REVIEW_PATTERN = "report_review/{$REPORT_REVIEW_ARG_ID}"
    fun reportReview(reportId: String) = "report_review/${Uri.encode(reportId)}"

    const val COMPOSE_ARG_TO = "to"
    const val COMPOSE_ARG_SUBJECT = "subject"
    const val COMPOSE_ARG_FROM = "from"
    const val COMPOSE_ARG_DRAFT = "draft"
    const val COMPOSE_PATTERN =
        "compose?to={$COMPOSE_ARG_TO}&subject={$COMPOSE_ARG_SUBJECT}&from={$COMPOSE_ARG_FROM}&draft={$COMPOSE_ARG_DRAFT}"
    fun compose(to: String = "", subject: String = "", from: String = ""): String =
        "compose?to=${Uri.encode(to)}&subject=${Uri.encode(subject)}&from=${Uri.encode(from)}"
    fun composeDraft(draftId: String): String = "compose?draft=${Uri.encode(draftId)}"
}
