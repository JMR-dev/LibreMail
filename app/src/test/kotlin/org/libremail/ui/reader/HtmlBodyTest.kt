// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import androidx.compose.ui.graphics.Color
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.libremail.ui.contrastRatio

/**
 * Pins the readability contract of the reader's HTML wrapper: every email is rendered with an
 * explicit, theme-derived background, text, and link color that meet WCAG AA contrast. This is the
 * deterministic guard against the dark-mode "black on black" bug, where a transparent WebView over
 * the near-black app surface left color-less emails showing the browser-default black text.
 */
class HtmlBodyTest {

    private fun rgb(hex: String): Int = hex.removePrefix("#").toInt(16)

    @Test
    fun `dark theme email is wrapped with readable, explicit colors`() {
        val bg = "#121212"
        val text = "#E6E1E5"
        val link = "#A8C7FA"
        val html = wrapHtml(body = "<p>hello</p>", backgroundHex = bg, textHex = text, linkHex = link, dark = true)

        assertTrue(html.contains("background-color: $bg"), "missing explicit background:\n$html")
        assertTrue(html.contains("color: $text"), "missing explicit text color:\n$html")
        assertTrue(html.contains("a { color: $link; }"), "missing explicit link color:\n$html")
        assertTrue(html.contains("color-scheme: dark"), "missing dark color-scheme:\n$html")
        assertTrue(html.contains("<p>hello</p>"), "email body dropped:\n$html")

        assertTrue(contrastRatio(rgb(text), rgb(bg)) >= 4.5, "text/background contrast below AA")
        assertTrue(contrastRatio(rgb(link), rgb(bg)) >= 4.5, "link/background contrast below AA")
    }

    @Test
    fun `light theme email is wrapped with readable, explicit colors`() {
        val bg = "#FFFBFE"
        val text = "#1C1B1F"
        val link = "#0B57D0"
        val html = wrapHtml(body = "<p>hi</p>", backgroundHex = bg, textHex = text, linkHex = link, dark = false)

        assertTrue(html.contains("background-color: $bg"), "missing explicit background:\n$html")
        assertTrue(html.contains("color: $text"), "missing explicit text color:\n$html")
        assertTrue(html.contains("color-scheme: light"), "missing light color-scheme:\n$html")

        assertTrue(contrastRatio(rgb(text), rgb(bg)) >= 4.5, "text/background contrast below AA")
        assertTrue(contrastRatio(rgb(link), rgb(bg)) >= 4.5, "link/background contrast below AA")
    }

    @Test
    fun `toCssHex formats opaque colors as #RRGGBB`() {
        assertEquals("#121212", Color(0xFF121212).toCssHex())
        assertEquals("#A8C7FA", Color(0xFFA8C7FA).toCssHex())
    }
}
