// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.libremail.R
import org.libremail.richtext.RichAlign

/** The glyphs the three alignment buttons show; shared with the control's test so they can't drift. */
internal const val ALIGN_START_GLYPH = "⇤"
internal const val ALIGN_CENTER_GLYPH = "↔"
internal const val ALIGN_END_GLYPH = "⇥"

/**
 * A three-state paragraph-alignment control (start / center / end) for the formatting toolbar. Each
 * button is a bare glyph whose accessible meaning rides on its `onClickLabel` (there is no separate
 * contentDescription), matching the toolbar's other buttons. [selected] lights up the matching button
 * — pass [RichTextEditing.alignmentAt]'s result, where null (a mixed selection) lights up none — and
 * [onSelect] reports the tapped alignment so the caller routes it through `setAlignment`.
 */
@Composable
fun ParagraphAlignmentControl(selected: RichAlign?, onSelect: (RichAlign) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlignButton(
            glyph = ALIGN_START_GLYPH,
            description = stringResource(R.string.format_align_start),
            active = selected == RichAlign.START,
            onClick = { onSelect(RichAlign.START) },
        )
        AlignButton(
            glyph = ALIGN_CENTER_GLYPH,
            description = stringResource(R.string.format_align_center),
            active = selected == RichAlign.CENTER,
            onClick = { onSelect(RichAlign.CENTER) },
        )
        AlignButton(
            glyph = ALIGN_END_GLYPH,
            description = stringResource(R.string.format_align_end),
            active = selected == RichAlign.END,
            onClick = { onSelect(RichAlign.END) },
        )
    }
}

@Composable
private fun AlignButton(glyph: String, description: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) colors.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = description)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant)
    }
}
