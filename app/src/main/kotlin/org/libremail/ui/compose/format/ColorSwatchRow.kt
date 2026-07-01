// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.libremail.R

/**
 * A horizontal row of color swatches with a leading "no color" entry, shared by the font-color and
 * highlight pickers. The selected swatch (or the "no color" entry when [selectedArgb] is null)
 * shows a primary-colored ring; every swatch is a labeled button for TalkBack.
 */
@Composable
fun ColorSwatchRow(
    swatches: List<ColorSwatch>,
    selectedArgb: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    noneLabel: String = stringResource(R.string.format_color_none),
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(color = null, label = noneLabel, isSelected = selectedArgb == null, onClick = { onSelect(null) })
        swatches.forEach { swatch ->
            Swatch(
                color = Color(swatch.argb),
                label = swatch.label,
                isSelected = selectedArgb == swatch.argb,
                onClick = { onSelect(swatch.argb) },
            )
        }
    }
}

@Composable
private fun Swatch(color: Color?, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ring = if (isSelected) colors.primary else colors.outlineVariant
    val slash = colors.outline
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color ?: Color.Transparent)
            .border(if (isSelected) 3.dp else 1.dp, ring, CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics {
                contentDescription = label
                selected = isSelected
            },
    ) {
        if (color == null) {
            Canvas(Modifier.matchParentSize()) {
                drawLine(
                    color = slash,
                    start = Offset(size.width * 0.25f, size.height * 0.75f),
                    end = Offset(size.width * 0.75f, size.height * 0.25f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}
