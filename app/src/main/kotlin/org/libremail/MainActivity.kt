// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import org.libremail.ui.security.CacheEncryptionGate
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
        handleIntent(intent)
        setContent {
            val dynamicColor by settingsRepository.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            LibreMailTheme(dynamicColor = dynamicColor) {
                // Gate the whole app behind the screen-lock when app-lock is enabled. When it is off
                // the gate resolves straight to the content, so this is a no-op for most users.
                AppLockGateHost {
                    // Inside the app-lock gate (so the auth-bound passphrase is already unlocked): fail
                    // closed if the encrypted cache's SQLCipher library won't load (#359), showing the
                    // error gate instead of ever opening the cache unencrypted. Resolves straight to the
                    // content when the cache is openable, so it is a no-op for most users.
                    CacheEncryptionGate {
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Parses [intent] for a pending compose ([IntentComposeParser]) or open-message
     * ([NotificationIntents]) request — unless [IntentHandledMarker] says this exact intent instance
     * was already parsed.
     *
     * This used to be gated on `savedInstanceState == null` in [onCreate]: a non-null value was
     * assumed to mean "config-change recreation" — where Android redelivers the very same,
     * already-parsed intent and the NavHost restores its own compose/reader destination itself, so
     * re-parsing would only navigate to a duplicate. But Android *also* passes a restored, non-null
     * savedInstanceState when it recreates this activity after the process was killed in the
     * background and is then relaunched — e.g. by tapping a notification. There, `intent` is the new
     * tap, not a replay, but the old guard swallowed it exactly like a rotation: `pendingOpenMessageId`
     * was never set, so the tap silently landed wherever the restored back stack was, never the
     * message. That regression is #157.
     *
     * Marking the [Intent] instance itself — rather than branching on savedInstanceState, which can't
     * tell a config change and a process-death relaunch apart — is correct for both: a config-change
     * recreation redelivers the very same intent this activity already marked, so it's recognized and
     * skipped; a genuinely new intent — a fresh notification tap or mailto/share, whether delivered
     * warm via [onNewIntent] or cold via [onCreate] after a process-death relaunch — is never marked
     * yet, so it's always (re)parsed.
     */
    private fun handleIntent(intent: Intent) {
        if (!IntentHandledMarker.markIfUnhandled(intent)) return
        IntentComposeParser.parse(intent)?.let { pendingCompose.value = it }
        NotificationIntents.messageId(this, intent)?.let { pendingOpenMessageId.value = it }
    }
}

/**
 * Marks an [Intent] as already parsed by [MainActivity.handleIntent], so a redelivery of the very same
 * instance — which is what Android does when it recreates an Activity for a configuration change — is
 * recognized and skipped instead of re-triggering a duplicate navigation. A freshly constructed intent
 * (a new notification tap or mailto/share, however it arrives) is never marked yet, so it is always
 * treated as unhandled — including right after a process-death relaunch, where `savedInstanceState` is
 * restored (non-null) but the intent itself is new. Internal (not private) so androidTest can verify
 * the marking contract directly. See #157.
 */
internal object IntentHandledMarker {
    private const val EXTRA_HANDLED = "org.libremail.extra.INTENT_HANDLED"

    /** Marks [intent] handled and returns `true` — but only the first time this instance is seen. */
    fun markIfUnhandled(intent: Intent): Boolean {
        if (intent.getBooleanExtra(EXTRA_HANDLED, false)) return false
        intent.putExtra(EXTRA_HANDLED, true)
        return true
    }
}
