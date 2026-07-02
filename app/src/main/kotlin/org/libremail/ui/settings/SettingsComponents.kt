// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.libremail.R

/** Shared row/header composables used by both the global and per-account settings screens. */

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun ClickRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun RadioRow(title: String, subtitle: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A preset retention choice: its persisted value ([value], null = "use global default") and its label. */
private data class RetentionOption(val value: Int?, val labelRes: Int)

private val COUNT_OPTIONS = listOf(
    RetentionOption(0, R.string.retention_keep_all),
    RetentionOption(500, R.string.retention_count_500),
    RetentionOption(1000, R.string.retention_count_1000),
    RetentionOption(5000, R.string.retention_count_5000),
)

private val AGE_OPTIONS = listOf(
    RetentionOption(0, R.string.retention_keep_all),
    RetentionOption(3, R.string.retention_age_3m),
    RetentionOption(6, R.string.retention_age_6m),
    RetentionOption(12, R.string.retention_age_1y),
    RetentionOption(24, R.string.retention_age_2y),
)

/**
 * Device-only retention controls (issue #13): a message-count group and an age group. When
 * [includeUseDefault] is true (the per-account screen) each group also offers "use the global
 * default" (persisted as null); the global screen omits it. Copy makes clear this never touches the
 * server.
 */
@Composable
internal fun RetentionSection(
    count: Int?,
    months: Int?,
    includeUseDefault: Boolean,
    onCountChange: (Int?) -> Unit,
    onMonthsChange: (Int?) -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_retention))
    Text(
        text = stringResource(R.string.settings_retention_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    RetentionGroup(R.string.retention_count_title, COUNT_OPTIONS, count, includeUseDefault, onCountChange)
    RetentionGroup(R.string.retention_age_title, AGE_OPTIONS, months, includeUseDefault, onMonthsChange)
}

@Composable
private fun RetentionGroup(
    titleRes: Int,
    options: List<RetentionOption>,
    current: Int?,
    includeUseDefault: Boolean,
    onChange: (Int?) -> Unit,
) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
    if (includeUseDefault) {
        RadioRow(
            title = stringResource(R.string.retention_use_default),
            selected = current == null,
            onClick = { onChange(null) },
        )
    }
    options.forEach { option ->
        RadioRow(
            title = stringResource(option.labelRes),
            selected = current == option.value,
            onClick = { onChange(option.value) },
        )
    }
}
