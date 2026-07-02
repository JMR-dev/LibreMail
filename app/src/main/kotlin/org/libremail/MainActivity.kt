// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.libremail.data.settings.SettingsRepository
import org.libremail.notifications.NotificationIntents
import org.libremail.ui.LibreMailApp
import org.libremail.ui.compose.ComposePrefill
import org.libremail.ui.compose.IntentComposeParser
import org.libremail.ui.lock.AppLockGateHost
import org.libremail.ui.theme.LibreMailTheme
import javax.inject.Inject

// FragmentActivity (not ComponentActivity) because BiometricPrompt requires one for the app-lock
// flow. FragmentActivity extends androidx.activity.ComponentActivity, so setContent / enableEdgeToEdge
// and Hilt injection keep working unchanged.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /**
     * A pending compose request parsed from a `mailto:` / share intent, consumed once by the NavHost.
     * Held as Compose state so [onNewIntent] can re-trigger it while the activity is alive.
     */
    private val pendingCompose = mutableStateOf<ComposePrefill?>(null)

    /**
     * The message a tapped new-mail notification asks to open, consumed once by the NavHost. Compose
     * state for the same reason as [pendingCompose].
     */
    private val pendingOpenMessageId = mutableStateOf<String?>(null)

    override fun onStart() {
        super.onStart()
        // Foreground: recover IDLE push if a background start was previously blocked.
        (application as? LibreMailApplication)?.ensurePushStarted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Block screenshots and the recents-switcher snapshot while app-lock is on — the Compose gate
        // can't stop the system's task snapshot (captured around background). Gated on the setting
        // because FLAG_SECURE also blocks the user's own screenshots.
        lifecycleScope.launch {
            settingsRepository.settings.map { it.appLock }.distinctUntilChanged().collect { secure ->
                if (secure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        // Only on a fresh launch — on a config-change recreation the NavHost restores the compose /
        // reader destination itself, so re-parsing the (unchanged) intent would open a duplicate.
        if (savedInstanceState == null) {
            pendingCompose.value = IntentComposeParser.parse(intent)
            pendingOpenMessageId.value = NotificationIntents.messageId(intent)
        }
        setContent {
            val dynamicColor by settingsRepository.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            LibreMailTheme(dynamicColor = dynamicColor) {
                NotificationPermissionEffect()
                // Gate the whole app behind the screen-lock when app-lock is enabled. When it is off
                // the gate resolves straight to the content, so this is a no-op for most users.
                AppLockGateHost {
                    LibreMailApp(
                        pendingCompose = pendingCompose.value,
                        onComposeHandled = { pendingCompose.value = null },
                        pendingOpenMessageId = pendingOpenMessageId.value,
                        onOpenMessageHandled = { pendingOpenMessageId.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IntentComposeParser.parse(intent)?.let { pendingCompose.value = it }
        NotificationIntents.messageId(intent)?.let { pendingOpenMessageId.value = it }
    }
}

/** Requests POST_NOTIFICATIONS once on first launch (no-op if already granted). */
@Composable
private fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        // POST_NOTIFICATIONS is a runtime permission only on Android 13 (API 33)+. On older
        // versions notifications are enabled by default, so there's nothing to request.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
