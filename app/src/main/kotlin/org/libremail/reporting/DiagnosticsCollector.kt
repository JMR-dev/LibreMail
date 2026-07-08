// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.os.Build
import kotlinx.coroutines.flow.first
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.repository.AccountRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles a [DebugReport] from app/device metadata, a stack trace (for crashes), a minimal non-PII
 * settings summary, a PII-free account summary, and the recent in-app log buffer. Only the fields
 * listed in [summarize] / [summarizeAccounts] are captured — deliberately no account emails, server
 * names, or message content.
 */
@Singleton
class DiagnosticsCollector @Inject constructor(
    private val appVersion: AppVersionProvider,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val logBuffer: RingLogBuffer,
) {
    // Cached so a crash report (built synchronously on the crashing thread) can still include
    // settings without touching DataStore. Refreshed whenever settings are read for a manual
    // report or explicitly warmed at startup.
    @Volatile
    private var cachedSettings: Map<String, String> = emptyMap()

    // Same rationale as [cachedSettings]: a crash report can't read the DB-backed account list on the
    // crashing thread, so a warmed snapshot is used. Accounts live in the non-auth AccountDatabase, so
    // reading them never blocks on the encrypted cache.
    @Volatile
    private var cachedAccounts: List<String> = emptyList()

    /** Pre-reads settings + accounts so a later crash report can include them. Safe to call and ignore. */
    suspend fun warmSettingsCache() {
        cachedSettings = summarize(settingsRepository.settings.first())
        cachedAccounts = summarizeAccounts(accountRepository.observeAccounts().first())
    }

    /** Builds a report for a user-initiated ("Report a problem") request; includes live settings. */
    suspend fun collectManual(): DebugReport {
        val settings = summarize(settingsRepository.settings.first())
        val accounts = summarizeAccounts(accountRepository.observeAccounts().first())
        cachedSettings = settings
        cachedAccounts = accounts
        return build(ReportKind.MANUAL, throwable = null, settings = settings, accounts = accounts)
    }

    /** Builds a crash report synchronously; it must not block or throw on the crashing thread. */
    fun collectCrash(throwable: Throwable): DebugReport =
        build(ReportKind.CRASH, throwable = throwable, settings = cachedSettings, accounts = cachedAccounts)

    private fun build(kind: ReportKind, throwable: Throwable?, settings: Map<String, String>, accounts: List<String>) =
        DebugReport(
            id = UUID.randomUUID().toString(),
            createdAtMillis = System.currentTimeMillis(),
            kind = kind,
            appVersionName = appVersion.versionName,
            appVersionCode = appVersion.versionCode,
            androidRelease = Build.VERSION.RELEASE ?: "",
            androidSdkInt = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER ?: "",
            deviceModel = Build.MODEL ?: "",
            // Scrub PII (server host:port, emails) out of the trace before it enters the report (#294).
            stackTrace = throwable?.let { StackTraceScrubber.scrub(it.stackTraceToString()) },
            settings = settings,
            accounts = accounts,
            logs = logBuffer.snapshot().map { it.formatted() },
        )

    private fun summarize(settings: AppSettings): Map<String, String> = linkedMapOf(
        "dynamicColor" to settings.dynamicColor.toString(),
        "newMailNotifications" to settings.newMailNotifications.toString(),
        "pushIdle" to settings.pushIdle.toString(),
        "allowStartTls" to settings.allowStartTls.toString(),
        "loadRemoteImages" to settings.loadRemoteImages.toString(),
        "encryptCache" to settings.encryptCache.toString(),
        "fetchPolicy" to settings.fetchPolicy.name,
    )

    /**
     * One PII-free "<provider> (<authType>)" entry per account (issue #235); the account count is the
     * list size. Deliberately NO email address or server hostname: [providerLabel] buckets the IMAP host
     * to a coarse known-provider name (or "Other" for custom domains), so a custom mail host never leaks.
     */
    private fun summarizeAccounts(accounts: List<Account>): List<String> =
        accounts.map { "${providerLabel(it)} (${it.authType.name})" }
}

/** Coarse, non-PII provider bucket for an account — never the raw host or email (issue #235). */
private fun providerLabel(account: Account): String = when (account.authType) {
    AuthType.OAUTH_OUTLOOK -> "Outlook"
    AuthType.PASSWORD_IMAP -> imapProviderLabel(account.imap.host)
}

/**
 * Buckets an IMAP host to a coarse provider by matching brand tokens at DNS-label boundaries rather
 * than as raw substrings, so a custom domain that merely contains a brand name — e.g.
 * `mail.notgmail.example` — is no longer mislabeled (here it would have read as Gmail) (#298). The
 * short, common tokens (`me`/`mac`/`live`) match only as a registrable-domain suffix, never as a bare
 * label, so an innocent `me.company.example` doesn't read as iCloud either.
 */
private fun imapProviderLabel(host: String): String {
    val h = host.lowercase()
    val labels = h.split('.')
    fun hasLabel(vararg brands: String) = brands.any { it in labels }
    fun hasDomain(vararg domains: String) = domains.any { h == it || h.endsWith(".$it") }
    return when {
        hasLabel("gmail", "googlemail") -> "Gmail"
        hasLabel("yahoo") -> "Yahoo"
        hasLabel("icloud") || hasDomain("me.com", "mac.com") -> "iCloud"
        hasLabel("outlook", "office365", "hotmail") || hasDomain("live.com") -> "Outlook"
        hasLabel("aol") -> "AOL"
        else -> "Other"
    }
}
