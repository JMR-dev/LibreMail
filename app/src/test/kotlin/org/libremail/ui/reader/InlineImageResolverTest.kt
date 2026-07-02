// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import org.junit.Test
import org.libremail.domain.model.InlineImage
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for the reader WebView's `cid:` resolution (issue #133): the pure logic that turns an
 * `<img src="cid:...">` request URL into the matching inline image's bytes, factored out of
 * [HtmlBody] so it needs no WebView. Only `cid:` URLs are served; anything else falls through to the
 * WebView's normal (remote-blockable) loading.
 */
class InlineImageResolverTest {

    private fun image(contentId: String) = InlineImage(contentId, "image/png", byteArrayOf(1, 2, 3))

    @Test
    fun `cidKey extracts and normalizes the Content-ID from a cid URL`() {
        assertEquals("logo1", cidKey("cid:logo1"))
        assertEquals("logo1", cidKey("cid:<logo1>"))
        assertEquals("a@b.example", cidKey("CID:a@b.example")) // scheme is case-insensitive
    }

    @Test
    fun `cidKey rejects non-cid and empty references`() {
        assertNull(cidKey("https://example.com/tracker.png"))
        assertNull(cidKey("data:image/png;base64,AAAA"))
        assertNull(cidKey("cid:"))
    }

    @Test
    fun `resolveInlineImage returns the matching image for a cid reference`() {
        val logo = image("logo1")
        val images = mapOf("logo1" to logo, "banner" to image("banner"))

        assertSame(logo, resolveInlineImage("cid:logo1", images))
        assertSame(logo, resolveInlineImage("cid:<logo1>", images))
    }

    @Test
    fun `resolveInlineImage returns null for remote urls and unknown cids`() {
        val images = mapOf("logo1" to image("logo1"))

        assertNull(resolveInlineImage("https://example.com/pixel.gif", images))
        assertNull(resolveInlineImage("cid:does-not-exist", images))
        assertNull(resolveInlineImage("cid:logo1", emptyMap()))
    }
}
