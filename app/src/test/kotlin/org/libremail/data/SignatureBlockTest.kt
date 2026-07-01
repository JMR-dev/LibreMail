// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import org.junit.Test
import org.libremail.domain.model.Signature
import org.libremail.richtext.RichTextHtml
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignatureBlockTest {

    private fun signature(html: String) =
        Signature(id = "s", accountId = "a", name = "Sig", html = html, isDefault = true)

    @Test
    fun `plain form opens with the RFC 3676 delimiter`() {
        val block = SignatureBlock.of(signature("Cheers, Alice"))
        assertEquals("\n\n-- \nCheers, Alice", block.plain)
    }

    @Test
    fun `html form parses back to exactly the plain form`() {
        val block = SignatureBlock.of(signature("Cheers, Alice"))
        assertEquals(block.plain, RichTextHtml.fromHtml(block.html).text)
    }

    @Test
    fun `a rich signature keeps its formatting in the html form`() {
        val block = SignatureBlock.of(signature("Cheers, <b>Alice</b>"))
        assertTrue(block.html.endsWith("Cheers, <b>Alice</b>"), block.html)
        assertEquals("\n\n-- \nCheers, Alice", block.plain)
    }

    @Test
    fun `null or blank signature yields the empty block`() {
        assertTrue(SignatureBlock.of(null).isEmpty)
        assertTrue(SignatureBlock.of(signature("")).isEmpty)
    }
}
