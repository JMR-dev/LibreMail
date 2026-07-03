// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.libremail.R

/** One selectable font: a display [name], its email-safe CSS stack, and the Compose family to render it. */
data class FontChoice(val name: String, val css: String, val fontFamily: FontFamily)

/**
 * The fonts the [FontPicker] offers and the resolver the editor renders them with. Three generic
 * families need no bundled file (they map to the platform defaults and to bare CSS generics); four
 * bundled open-source families (all SIL OFL 1.1 — Inter, Lora, Merriweather, JetBrains Mono, whose
 * license texts live under `THIRD_PARTY_LICENSES/`) each name the family first in their CSS stack and
 * fall back to a generic, so a recipient whose mail client lacks the bundled face still renders a
 * sensible one. The stored [RichStyle.FontFamily] carries only the CSS string, so nothing here leaks
 * into the wire format — [resolveFontFamily] just maps that string back to a Compose [FontFamily] for
 * in-editor display (null → leave the system default).
 */
object FontRegistry {

    val choices: List<FontChoice> = listOf(
        FontChoice("Sans-serif", "sans-serif", FontFamily.SansSerif),
        FontChoice("Serif", "serif", FontFamily.Serif),
        FontChoice("Monospace", "monospace", FontFamily.Monospace),
        FontChoice("Inter", "'Inter', sans-serif", FontFamily(Font(R.font.inter))),
        FontChoice("Lora", "'Lora', Georgia, serif", FontFamily(Font(R.font.lora))),
        FontChoice("Merriweather", "'Merriweather', Georgia, serif", FontFamily(Font(R.font.merriweather))),
        FontChoice("JetBrains Mono", "'JetBrains Mono', monospace", FontFamily(Font(R.font.jetbrains_mono))),
    )

    private val byCss: Map<String, FontChoice> = choices.associateBy { it.css }

    /** The Compose family for a stored CSS stack, or null when it isn't one of ours (→ system font). */
    fun resolveFontFamily(css: String): FontFamily? = byCss[css]?.fontFamily

    /** The display name for a stored CSS stack, or null when it isn't one of ours. */
    fun displayNameFor(css: String): String? = byCss[css]?.name
}
