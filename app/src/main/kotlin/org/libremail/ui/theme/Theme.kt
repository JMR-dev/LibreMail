// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Exposed as internal (not private) so the color-scheme contrast test can audit the role pairs.
internal val LightColors = lightColorScheme(
    primary = BrandBlue,
    secondary = BrandTeal,
)

internal val DarkColors = darkColorScheme(
    primary = BrandBlueLight,
    secondary = BrandTealLight,
)

/**
 * Material You theme. On Android 12+ (API 31+) it uses the wallpaper-derived
 * dynamic color scheme; otherwise it falls back to the LibreMail brand palette.
 */
@Composable
fun LibreMailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = LibreMailTypography,
        content = content,
    )
}
