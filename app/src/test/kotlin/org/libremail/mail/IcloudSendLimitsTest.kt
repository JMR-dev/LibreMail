// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.MailProvider
import org.libremail.domain.model.OutgoingMessage
import org.libremail.reporting.AppLog
import org.libremail.reporting.RingLogBuffer
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [IcloudSendLimits] (issue #363) must enforce Apple's documented ~20 MB outgoing message-size cap for
 * iCloud accounts only, estimating the *encoded* wire size (base64 inflates binary attachment bytes by
 * ~4/3) rather than comparing raw file bytes directly — so a message can be rejected even when every
 * individual attachment's raw size looks like it fits — and it must never touch any other provider.
 */
class IcloudSendLimitsTest {

    private val logBuffer = RingLogBuffer()

    private val icloudAccount = MailProvider.ICLOUD.createAccount("me@icloud.com")
    private val gmailAccount = MailProvider.GMAIL.createAccount("me@gmail.com")

    @Before
    fun setUp() {
        // AppLog forwards to android.util.Log, a throwing no-op stub under plain JVM unit tests; fully
        // qualified (no import) per the ForbiddenImport style already used by GraphSenderSendTest.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        AppLog.install(logBuffer)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun message(body: String = "hi") =
        OutgoingMessage(accountId = icloudAccount.id, to = "bob@example.org", subject = "Hi", body = body)

    /** A sparse file of exactly [bytes] — allocates the size without writing content (fast, no disk churn). */
    private fun fileOfSize(bytes: Long): File {
        val file = File.createTempFile("icloud-limit", ".bin")
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
        file.deleteOnExit()
        return file
    }

    @Test
    fun `a small iCloud message passes`() {
        val attachment = SendableAttachment(fileOfSize(1024))

        IcloudSendLimits.requireWithinLimit(icloudAccount, message(), listOf(attachment))
        // No exception: reaching this line is the assertion.
    }

    @Test
    fun `an attachment exactly at the encoded boundary passes`() {
        // Empty body so only the attachment's encoded size is in play. Raw bytes chosen so ceil(raw/3)*4
        // lands EXACTLY on the documented cap (a multiple of 3, so the base64 group count divides evenly):
        // 15_728_640 * 4 / 3 = 20_971_520 = DOCUMENTED_LIMIT_BYTES.
        val attachment = SendableAttachment(fileOfSize(15_728_640L))

        IcloudSendLimits.requireWithinLimit(icloudAccount, message(body = ""), listOf(attachment))
    }

    @Test
    fun `an attachment one base64 group past the boundary fails`() {
        // One byte over the exact-boundary raw size above rolls the base64 group count up by one full
        // 4-byte group, pushing the estimate just past the cap. Empty body keeps the math exact.
        val attachment = SendableAttachment(fileOfSize(15_728_641L))

        val ex = assertFailsWithMessageTooLarge {
            IcloudSendLimits.requireWithinLimit(icloudAccount, message(body = ""), listOf(attachment))
        }
        assertTrue(ex.estimatedBytes > ex.limitBytes)
        assertEquals(IcloudSendLimits.DOCUMENTED_LIMIT_BYTES, ex.limitBytes)
    }

    @Test
    fun `base64 inflation alone can push a raw-under-cap attachment over the encoded cap`() {
        // 15.8 MB raw is comfortably UNDER the 20 MB documented cap, but base64 (~4/3 inflation) encodes
        // it to just over 20 MB — proving the guard checks the encoded size, not the raw file size.
        val rawBytes = 15_800_000L
        assertTrue(rawBytes < IcloudSendLimits.DOCUMENTED_LIMIT_BYTES, "the raw size must look like it fits")
        val attachment = SendableAttachment(fileOfSize(rawBytes))

        assertFailsWithMessageTooLarge {
            IcloudSendLimits.requireWithinLimit(icloudAccount, message(), listOf(attachment))
        }
    }

    @Test
    fun `a large body alone, with no attachments, can exceed the cap`() {
        val hugeBody = "a".repeat(21_000_000) // ~21 MB of body text, over the 20 MB cap on its own

        assertFailsWithMessageTooLarge {
            IcloudSendLimits.requireWithinLimit(icloudAccount, message(hugeBody), emptyList())
        }
    }

    @Test
    fun `body bytes count toward the total alongside attachments`() {
        // Neither the body nor the attachment alone would trip the cap, but together they do — proving
        // body bytes are added to the estimate rather than the check considering attachments only.
        val body = "a".repeat(2_000_000) // 2 MB
        val attachment = SendableAttachment(fileOfSize(14_300_000L)) // encodes to ~18.2 MB, alone under cap

        // The attachment alone (empty body) must NOT trip the cap.
        IcloudSendLimits.requireWithinLimit(icloudAccount, message(), listOf(attachment))

        // The same attachment WITH the 2 MB body pushes the estimate over the cap.
        assertFailsWithMessageTooLarge {
            IcloudSendLimits.requireWithinLimit(icloudAccount, message(body), listOf(attachment))
        }
    }

    @Test
    fun `every other provider is never gated, even wildly over iCloud's cap`() {
        val attachment = SendableAttachment(fileOfSize(50L * 1024 * 1024)) // 50 MB, far over iCloud's cap

        IcloudSendLimits.requireWithinLimit(gmailAccount, message(), listOf(attachment))
        // No exception for Gmail: reaching this line is the assertion.
    }

    @Test
    fun `an oversized send logs a PII-free breadcrumb naming only byte counts`() {
        val attachment = SendableAttachment(fileOfSize(21L * 1024 * 1024))

        assertFailsWithMessageTooLarge {
            IcloudSendLimits.requireWithinLimit(icloudAccount, message(), listOf(attachment))
        }

        val messages = logBuffer.snapshot().map { it.message }
        assertTrue(messages.any { it.contains("over iCloud size cap") }, "messages=$messages")
        messages.forEach { line ->
            assertFalse(line.contains("@icloud.com"), line)
            assertFalse(line.contains("me@"), line)
        }
    }

    @Test
    fun `the exception message names iCloud Mail and both sizes in whole megabytes, with no PII`() {
        val attachment = SendableAttachment(fileOfSize(25L * 1024 * 1024))

        val ex = assertFailsWithMessageTooLarge {
            IcloudSendLimits.requireWithinLimit(icloudAccount, message(), listOf(attachment))
        }

        val text = requireNotNull(ex.message)
        assertTrue(text.contains("iCloud Mail"), text)
        assertTrue(text.contains("MB"), text)
        assertFalse(text.contains("@"), text)
    }

    private fun assertFailsWithMessageTooLarge(block: () -> Unit): MessageTooLargeException {
        val failure = runCatching(block)
        assertTrue(failure.isFailure, "expected a MessageTooLargeException")
        val exception = failure.exceptionOrNull()
        assertTrue(exception is MessageTooLargeException, "expected MessageTooLargeException, was $exception")
        return exception
    }
}
