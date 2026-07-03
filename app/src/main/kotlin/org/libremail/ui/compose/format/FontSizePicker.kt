// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose.format

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.libremail.R

/** The fixed preset sizes [FontSizePicker] offers, in points. */
internal val FONT_SIZE_PRESETS_PT = listOf(10, 12, 14, 18, 24)

/**
 * A toolbar dropdown for [org.libremail.richtext.RichStyle.FontSize]: the anchor button shows the
 * selection's current size, or "Default" when [selectedPt] is null (no size applied, or a mixed
 * selection), and opens a menu of [FONT_SIZE_PRESETS_PT] plus a leading "Default" entry that clears
 * the style outright. Mirrors [ColorSwatchRow]'s "no color" convention: [onSelect] receives null for
 * "Default" and a preset point size otherwise, so the caller routes the choice straight through the
 * generalized `applyStyle`/`clearStyle` toggle path with no font-size-specific branching of its own.
 */
@Composable
fun FontSizePicker(selectedPt: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.format_size)
    val label = if (selectedPt != null) {
        stringResource(R.string.format_size_pt, selectedPt)
    } else {
        stringResource(R.string.format_size_default)
    }
    val contentColor = if (selectedPt != null) colors.onSecondaryContainer else colors.onSurfaceVariant
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(if (selectedPt != null) colors.secondaryContainer else Color.Transparent)
                .clickable(onClick = { expanded = true }, role = Role.Button, onClickLabel = description)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = contentColor)
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.format_size_default)) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            FONT_SIZE_PRESETS_PT.forEach { pt ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.format_size_pt, pt)) },
                    onClick = {
                        expanded = false
                        onSelect(pt)
                    },
                )
            }
        }
    }
}
