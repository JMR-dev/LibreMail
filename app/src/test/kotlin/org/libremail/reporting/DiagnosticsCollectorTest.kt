// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.FetchPolicy
import org.libremail.data.settings.SettingsRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsCollectorTest {

    private val appVersion = mockk<AppVersionProvider> {
        every { versionName } returns "1.2.3"
        every { versionCode } returns 42L
    }
    private val settingsRepository = mockk<SettingsRepository>()
    private val accountRepository = mockk<AccountRepository> {
        every { observeAccounts() } returns flowOf(emptyList())
    }
    private val logBuffer = RingLogBuffer()
    private val collector = DiagnosticsCollector(appVersion, settingsRepository, accountRepository, logBuffer)

    @Test
    fun `crash report includes stack trace and app version`() = runTest {
        val report = collector.collectCrash(RuntimeException("kaboom"))

        assertEquals(ReportKind.CRASH, report.kind)
        assertEquals("1.2.3", report.appVersionName)
        assertEquals(42L, report.appVersionCode)
        // The trace is captured but scrubbed of message free-text (PII guard, #294): the exception
        // class survives while the free-text message ("kaboom") is dropped.
        assertTrue(report.stackTrace.orEmpty().contains("RuntimeException"))
        assertFalse(report.stackTrace.orEmpty().contains("kaboom"))
    }

    @Test
    fun `crash report scrubs server host, port and email from the stack trace message`() = runTest {
        val boom = RuntimeException("Failed to connect to imap.example.com/93.184.216.34:993 for user@example.com")

        val trace = collector.collectCrash(boom).stackTrace.orEmpty()

        // No server host, IP, port or email leaks out of the captured trace message.
        assertFalse(trace.contains("imap.example.com"))
        assertFalse(trace.contains("93.184.216.34"))
        assertFalse(trace.contains(":993"))
        assertFalse(trace.contains("user@example.com"))
        // Class + frame info survives so the report is still actionable.
        assertTrue(trace.contains("RuntimeException"))
        assertTrue(trace.contains("DiagnosticsCollectorTest"))
    }

    @Test
    fun `manual report includes a minimal non-PII settings summary and no stack trace`() = runTest {
        every { settingsRepository.settings } returns
            flowOf(AppSettings(pushIdle = false, fetchPolicy = FetchPolicy.ON_DEMAND))

        val report = collector.collectManual()

        assertEquals(ReportKind.MANUAL, report.kind)
        assertNull(report.stackTrace)
        assertEquals("false", report.settings["pushIdle"])
        assertEquals("ON_DEMAND", report.settings["fetchPolicy"])
        // The summary is a fixed allow-list of non-PII flags — no account/server fields.
        assertEquals(
            setOf(
                "dynamicColor",
                "newMailNotifications",
                "pushIdle",
                "allowStartTls",
                "loadRemoteImages",
                "encryptCache",
                "fetchPolicy",
            ),
            report.settings.keys,
        )
        assertTrue(report.settings.values.none { it.contains("@") })
    }

    @Test
    fun `crash report includes settings once the cache is warmed`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true))
        collector.warmSettingsCache()

        val report = collector.collectCrash(RuntimeException("x"))

        assertEquals("true", report.settings["encryptCache"])
    }

    @Test
    fun `crash report captures recent in-app log lines`() = runTest {
        logBuffer.record('I', "Startup", "hello-breadcrumb")

        val report = collector.collectCrash(RuntimeException("x"))

        assertTrue(report.logs.any { it.contains("hello-breadcrumb") })
    }

    @Test
    fun `manual report summarizes accounts as PII-free provider labels`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { accountRepository.observeAccounts() } returns flowOf(
            listOf(
                account("a@example.com", AuthType.OAUTH_OUTLOOK, "outlook.office365.com"),
                account("b@gmail.com", AuthType.PASSWORD_IMAP, "imap.gmail.com"),
                account("c@corp.example", AuthType.PASSWORD_IMAP, "mail.corp.example"),
            ),
        )

        val report = collector.collectManual()

        assertEquals(
            listOf("Outlook (OAUTH_OUTLOOK)", "Gmail (PASSWORD_IMAP)", "Other (PASSWORD_IMAP)"),
            report.accounts,
        )
        // No email address or server hostname leaks — a custom host buckets to "Other".
        assertTrue(report.accounts.none { it.contains("@") || it.contains("example") || it.contains(".com") })
    }

    @Test
    fun `provider labels bucket every known imap host and default to Other`() = runTest {
        every { settingsRepository.settings } returns flowOf(AppSettings())
        every { accountRepository.observeAccounts() } returns flowOf(
            listOf(
                account("1@x", AuthType.PASSWORD_IMAP, "imap.gmail.com"),
                account("2@x", AuthType.PASSWORD_IMAP, "imap.googlemail.com"),
                account("3@x", AuthType.PASSWORD_IMAP, "imap.mail.yahoo.com"),
                account("4@x", AuthType.PASSWORD_IMAP, "p01-imap.mail.icloud.com"),
                account("5@x", AuthType.PASSWORD_IMAP, "imap.mail.me.com"),
                account("6@x", AuthType.PASSWORD_IMAP, "imap.mac.com"),
                account("7@x", AuthType.PASSWORD_IMAP, "imap-mail.outlook.com"),
                account("8@x", AuthType.PASSWORD_IMAP, "smtp.office365.com"),
                account("9@x", AuthType.PASSWORD_IMAP, "imap.mail.hotmail.com"),
                account("10@x", AuthType.PASSWORD_IMAP, "imap.live.com"),
                account("11@x", AuthType.PASSWORD_IMAP, "imap.aol.com"),
                account("12@x", AuthType.PASSWORD_IMAP, "mail.custom.example"),
            ),
        )

        val report = collector.collectManual()

        assertEquals(
            listOf(
                "Gmail (PASSWORD_IMAP)",
                "Gmail (PASSWORD_IMAP)",
                "Yahoo (PASSWORD_IMAP)",
                "iCloud (PASSWORD_IMAP)",
                "iCloud (PASSWORD_IMAP)",
                "iCloud (PASSWORD_IMAP)",
                "Outlook (PASSWORD_IMAP)",
                "Outlook (PASSWORD_IMAP)",
                "Outlook (PASSWORD_IMAP)",
                "Outlook (PASSWORD_IMAP)",
                "AOL (PASSWORD_IMAP)",
                "Other (PASSWORD_IMAP)",
            ),
            report.accounts,
        )
    }

    private fun account(email: String, authType: AuthType, imapHost: String) = Account(
        id = "id:$email",
        email = email,
        displayName = email,
        authType = authType,
        imap = ServerConfig(imapHost, 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig(imapHost, 587, MailSecurity.STARTTLS),
    )
}
