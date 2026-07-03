// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import org.junit.Test
import org.libremail.richtext.RichSpan
import org.libremail.richtext.RichStyle
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextHtml
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FontRegistry] — the font-family picker's source of truth. These run on the JVM:
 * the registry's CSS stacks and lookups are plain data, so no emulator is needed to prove the wire
 * format (the stored CSS) round-trips and that recipients fall back down the stack.
 */
class FontRegistryTest {

    private val generics = setOf("sans-serif", "serif", "monospace")

    @Test
    fun `every css stack survives an html round-trip`() {
        FontRegistry.choices.forEach { choice ->
            val content = RichTextContent("x", spans = listOf(RichSpan(0, 1, RichStyle.FontFamily(choice.css))))
            val restored = RichTextHtml.fromHtml(RichTextHtml.toHtml(content))
            assertEquals(content.spans, restored.spans, "round-trip of ${choice.name}")
        }
    }

    @Test
    fun `resolveFontFamily maps a known css stack and rejects an unknown one`() {
        FontRegistry.choices.forEach { choice ->
            assertNotNull(FontRegistry.resolveFontFamily(choice.css), "resolve ${choice.name}")
        }
        assertNull(FontRegistry.resolveFontFamily("'Nonexistent', fantasy"))
    }

    @Test
    fun `bundled stacks name the family first then end in a generic fallback`() {
        // A recipient whose client lacks the bundled face falls back down the CSS stack to a generic.
        FontRegistry.choices.filter { it.css !in generics }.forEach { choice ->
            val parts = choice.css.split(",").map { it.trim() }
            assertTrue(parts.size >= 2, "${choice.name} needs at least one fallback")
            assertTrue(parts.first().startsWith("'"), "${choice.name} must name the family first")
            assertTrue(parts.last().lowercase() in generics, "${choice.name} must end in a generic family")
        }
    }

    @Test
    fun `the three generic families need no bundled file`() {
        assertEquals(generics, FontRegistry.choices.map { it.css }.filter { it in generics }.toSet())
    }

    @Test
    fun `displayNameFor resolves a known stack and is null otherwise`() {
        val choice = FontRegistry.choices.last()
        assertEquals(choice.name, FontRegistry.displayNameFor(choice.css))
        assertNull(FontRegistry.displayNameFor("'Nonexistent', fantasy"))
    }
}
