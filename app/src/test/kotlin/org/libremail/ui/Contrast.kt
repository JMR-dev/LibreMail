// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui

import kotlin.math.pow

/**
 * WCAG 2.x relative luminance of an opaque sRGB color packed as ARGB (alpha ignored).
 * See https://www.w3.org/TR/WCAG21/#dfn-relative-luminance.
 */
fun relativeLuminance(argb: Int): Double {
    val r = linearize((argb shr 16) and 0xFF)
    val g = linearize((argb shr 8) and 0xFF)
    val b = linearize(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun linearize(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

/**
 * WCAG 2.x contrast ratio in [1.0, 21.0] between two opaque colors.
 * See https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio.
 */
fun contrastRatio(foreground: Int, background: Int): Double {
    val l1 = relativeLuminance(foreground)
    val l2 = relativeLuminance(background)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}
