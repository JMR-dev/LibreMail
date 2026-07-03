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
import androidx.compose.material3.LocalTextStyle
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

/**
 * A toolbar dropdown for [org.libremail.richtext.RichStyle.FontFamily]. The anchor shows the
 * selection's current font by display name (via [FontRegistry]), or "Default" when [selectedCss] is
 * null (no font applied, or a mixed selection). The menu lists a leading "Default" entry that clears
 * the style, then every [FontRegistry] choice rendered in its own face as a preview. Mirrors
 * [FontSizePicker]'s "Default" convention: [onSelect] receives null to clear and a CSS stack
 * otherwise, so the caller routes the choice straight through the generalized `applyStyle`/`clearStyle`
 * path with no font-specific branching.
 */
@Composable
fun FontPicker(selectedCss: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val label = when {
        selectedCss == null -> stringResource(R.string.format_font_default)
        else -> FontRegistry.displayNameFor(selectedCss) ?: selectedCss
    }
    val contentColor = if (selectedCss != null) colors.onSecondaryContainer else colors.onSurfaceVariant
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(if (selectedCss != null) colors.secondaryContainer else Color.Transparent)
                .clickable(
                    onClick = { expanded = true },
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.format_font),
                )
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
                text = { Text(stringResource(R.string.format_font_default)) },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            FontRegistry.choices.forEach { choice ->
                DropdownMenuItem(
                    // Each entry previews itself in its own face so the picker reads like a font menu.
                    text = { Text(choice.name, style = LocalTextStyle.current.copy(fontFamily = choice.fontFamily)) },
                    onClick = {
                        expanded = false
                        onSelect(choice.css)
                    },
                )
            }
        }
    }
}
