// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [IntentComposeParser] turns inbound `mailto:` and share intents into a [ComposePrefill]. The
 * `mailto:` parsing itself is [MailtoParser]'s job (covered in [MailtoParserTest]); these lock in the
 * action routing, the URI-vs-extras precedence, and the `String[]`/`String` address-extra handling.
 */
class IntentComposeParserTest {

    private fun intent(
        action: String?,
        data: String? = null,
        email: Array<String>? = null,
        emailSingle: String? = null,
        cc: Array<String>? = null,
        bcc: Array<String>? = null,
        subject: String? = null,
        text: CharSequence? = null,
    ): Intent = mockk {
        every { this@mockk.action } returns action
        every { dataString } returns data
        every { getStringArrayExtra(any()) } returns null
        every { getStringExtra(any()) } returns null
        every { getCharSequenceExtra(any()) } returns null
        email?.let { every { getStringArrayExtra(Intent.EXTRA_EMAIL) } returns it }
        emailSingle?.let { every { getStringExtra(Intent.EXTRA_EMAIL) } returns it }
        cc?.let { every { getStringArrayExtra(Intent.EXTRA_CC) } returns it }
        bcc?.let { every { getStringArrayExtra(Intent.EXTRA_BCC) } returns it }
        subject?.let { every { getStringExtra(Intent.EXTRA_SUBJECT) } returns it }
        text?.let { every { getCharSequenceExtra(Intent.EXTRA_TEXT) } returns it }
    }

    @Test
    fun `a null intent parses to nothing`() {
        assertNull(IntentComposeParser.parse(null))
    }

    @Test
    fun `an unrelated action parses to nothing`() {
        assertNull(IntentComposeParser.parse(intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun `a mailto VIEW intent is parsed and its fields win over the extras`() {
        val prefill = IntentComposeParser.parse(
            intent(
                Intent.ACTION_VIEW,
                data = "mailto:alice@example.org?subject=From%20URI",
                subject = "From Extra",
            ),
        )

        assertEquals("alice@example.org", prefill?.to)
        // The URI's subject takes precedence; the extra only fills genuinely-blank fields.
        assertEquals("From URI", prefill?.subject)
    }

    @Test
    fun `a mailto SENDTO intent fills blank fields from the email extras`() {
        val prefill = IntentComposeParser.parse(
            intent(
                Intent.ACTION_SENDTO,
                data = "mailto:alice@example.org",
                subject = "Subject From Extra",
                text = "Body from extra",
            ),
        )

        assertEquals("alice@example.org", prefill?.to)
        assertEquals("Subject From Extra", prefill?.subject)
        assertEquals("Body from extra", prefill?.body)
    }

    @Test
    fun `a VIEW intent without a mailto URI is not a mail intent`() {
        assertNull(IntentComposeParser.parse(intent(Intent.ACTION_VIEW, data = "https://example.org")))
        assertNull(IntentComposeParser.parse(intent(Intent.ACTION_VIEW, data = null)))
    }

    @Test
    fun `a SEND share reads the standard email extras, joining an address array`() {
        val prefill = IntentComposeParser.parse(
            intent(
                Intent.ACTION_SEND,
                email = arrayOf("a@example.org", "  ", "b@example.org"),
                cc = arrayOf("c@example.org"),
                bcc = arrayOf("d@example.org"),
                subject = "Shared",
                text = "Shared body",
            ),
        )

        // Blank entries in the address array are dropped; the rest are comma-joined.
        assertEquals("a@example.org, b@example.org", prefill?.to)
        assertEquals("c@example.org", prefill?.cc)
        assertEquals("d@example.org", prefill?.bcc)
        assertEquals("Shared", prefill?.subject)
        assertEquals("Shared body", prefill?.body)
    }

    @Test
    fun `a SEND share tolerates a single-string email extra`() {
        val prefill = IntentComposeParser.parse(
            intent(Intent.ACTION_SEND, emailSingle = "solo@example.org", subject = "Hi"),
        )

        assertEquals("solo@example.org", prefill?.to)
        assertEquals("Hi", prefill?.subject)
    }

    @Test
    fun `a SEND share that also carries a mailto URI honours the URI first`() {
        val prefill = IntentComposeParser.parse(
            intent(
                Intent.ACTION_SEND,
                data = "mailto:from-uri@example.org",
                email = arrayOf("from-extra@example.org"),
                text = "body",
            ),
        )

        assertEquals("from-uri@example.org", prefill?.to)
        assertEquals("body", prefill?.body)
    }

    @Test
    fun `a SEND_MULTIPLE with nothing to compose parses to nothing`() {
        assertNull(IntentComposeParser.parse(intent(Intent.ACTION_SEND_MULTIPLE)))
    }

    @Test
    fun `ComposePrefill reports emptiness and carries value semantics`() {
        assertTrue(ComposePrefill().isEmpty)
        assertTrue(ComposePrefill(subject = "   ").isEmpty)
        val full = ComposePrefill(to = "a@b.org", cc = "c@b.org", bcc = "d@b.org", subject = "s", body = "b")
        assertTrue(!full.isEmpty)
        assertEquals(full, full.copy())
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("a@b.org"))
        assertNotEquals(full, full.copy(to = "x@b.org"))
        assertNotEquals(full, full.copy(body = ""))
    }
}
