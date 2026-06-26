// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.libremail.ui.LibreMailApp
import org.libremail.ui.theme.LibreMailTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibreMailTheme {
                LibreMailApp()
            }
        }
    }
}
