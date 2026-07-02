// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.Part
import jakarta.mail.internet.MimeBodyPart
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the MIME-part classification behind issue #133. An inline image (a `Content-ID` that
 * the HTML body references via `cid:`) carries a filename AND a `Content-ID` under
 * `Content-Disposition: inline`; it must NOT be swept into the downloadable-attachment list, while
 * genuine attachments and filename-only parts still must.
 */
class MimePartClassificationTest {

    private fun part(
        contentType: String,
        disposition: String? = null,
        filename: String? = null,
        contentId: String? = null,
    ): MimeBodyPart = MimeBodyPart().apply {
        setHeader("Content-Type", contentType)
        if (disposition != null || filename != null) {
            val header = buildString {
                append(disposition ?: Part.INLINE)
                if (filename != null) append("; filename=\"").append(filename).append("\"")
            }
            setHeader("Content-Disposition", header)
        }
        if (contentId != null) setHeader("Content-ID", contentId)
    }

    @Test
    fun `an inline image with a Content-ID is not a downloadable attachment`() {
        val inline = part("image/jpeg", disposition = "inline", filename = "mailer-1.jpg", contentId = "<cid-1@usps>")

        assertFalse(isAttachmentPart(inline), "inline+Content-ID must be excluded from attachments")
        assertTrue(isInlineImagePart(inline), "inline+Content-ID image must be collected for cid: rendering")
    }

    @Test
    fun `a real attachment with a filename is kept as an attachment`() {
        val attachment = part("application/pdf", disposition = "attachment", filename = "invoice.pdf")

        assertTrue(isAttachmentPart(attachment))
        assertFalse(isInlineImagePart(attachment))
    }

    @Test
    fun `a part with attachment disposition and no filename is kept as an attachment`() {
        val attachment = part("application/octet-stream", disposition = "attachment")

        assertTrue(isAttachmentPart(attachment))
        assertFalse(isInlineImagePart(attachment))
    }

    @Test
    fun `an image with a filename but no Content-ID is kept as an attachment`() {
        // No cid means nothing references it from the body, so it is a genuine download, not inline.
        val image = part("image/png", disposition = "inline", filename = "photo.png")

        assertTrue(isAttachmentPart(image), "filename without a Content-ID stays a downloadable attachment")
        assertFalse(isInlineImagePart(image))
    }

    @Test
    fun `an inline image without an explicit disposition is still classified inline by its Content-ID`() {
        // Some mailers omit Content-Disposition entirely and rely on the Content-ID + cid: reference.
        val inline = part("image/gif", contentId = "logo@example.com")

        assertFalse(isAttachmentPart(inline))
        assertTrue(isInlineImagePart(inline))
    }

    @Test
    fun `Content-ID is normalized by stripping surrounding angle brackets`() {
        assertEquals("cid-1@usps", inlineContentId(part("image/jpeg", contentId = "<cid-1@usps>")))
        assertEquals("bare@id", inlineContentId(part("image/jpeg", contentId = "bare@id")))
        assertNull(inlineContentId(part("application/pdf", disposition = "attachment", filename = "x.pdf")))
    }
}
