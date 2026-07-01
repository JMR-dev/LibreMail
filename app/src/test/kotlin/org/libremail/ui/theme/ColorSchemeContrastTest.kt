// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Test
import org.libremail.ui.contrastRatio
import kotlin.test.assertTrue

/**
 * Guards the fallback (non-dynamic) color schemes — the ones LibreMail ships for pre-Android-12
 * devices and when Material You is disabled — so a low-contrast palette change can't make UI text
 * unreadable, in dark mode in particular. Dynamic (wallpaper-derived) schemes are generated and
 * contrast-managed by the platform (and need a Context), so they are out of scope here.
 */
class ColorSchemeContrastTest {

    private fun assertReadable(label: String, foreground: Color, background: Color) {
        val ratio = contrastRatio(foreground.toArgb(), background.toArgb())
        assertTrue(ratio >= 4.5, "$label contrast ${"%.2f".format(ratio)} is below WCAG AA (4.5:1)")
    }

    private fun ColorScheme.assertTextPairsReadable(label: String) {
        assertReadable("$label onSurface/surface", onSurface, surface)
        assertReadable("$label onSurfaceVariant/surface", onSurfaceVariant, surface)
        assertReadable("$label onBackground/background", onBackground, background)
        assertReadable("$label onPrimary/primary", onPrimary, primary)
        assertReadable("$label onPrimaryContainer/primaryContainer", onPrimaryContainer, primaryContainer)
        assertReadable("$label onSecondary/secondary", onSecondary, secondary)
        assertReadable("$label onSecondaryContainer/secondaryContainer", onSecondaryContainer, secondaryContainer)
    }

    @Test
    fun `dark fallback scheme keeps text readable`() {
        DarkColors.assertTextPairsReadable("dark")
    }

    @Test
    fun `light fallback scheme keeps text readable`() {
        LightColors.assertTextPairsReadable("light")
    }
}
