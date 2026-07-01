// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R

/**
 * Final onboarding step (shown only when needed, see [OnboardingViewModel.batteryPromptNeeded]):
 * invites the user to allow unrestricted background/battery usage so push and periodic sync aren't
 * throttled by Doze. **Take me there** deep-links to the system screen (no restricted permission);
 * **Not now** skips. Either way [onFinish] proceeds to the inbox. On returning from Settings the
 * status is re-read and, if the app is now unrestricted, the screen reflects that with a "done" state.
 *
 * @param viewModel the graph-scoped onboarding view model (holds live battery status + the flag).
 * @param onFinish leaves onboarding for the inbox; the caller also marks the prompt handled.
 */
@Composable
fun BatteryOptimizationScreen(viewModel: OnboardingViewModel, onFinish: () -> Unit) {
    val unrestricted by viewModel.batteryUnrestricted.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-check on every resume so returning from the system settings screen reflects the new state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshBatteryStatus() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (unrestricted) Icons.Filled.CheckCircle else Icons.Filled.Notifications,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(
                    if (unrestricted) R.string.onboarding_battery_done_title else R.string.onboarding_battery_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (unrestricted) R.string.onboarding_battery_done_body else R.string.onboarding_battery_body,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            if (unrestricted) {
                Button(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                ) {
                    Text(stringResource(R.string.onboarding_battery_continue))
                }
            } else {
                Text(
                    text = stringResource(R.string.onboarding_battery_guidance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        // Mark handled up front: the user is leaving for Settings and might not return
                        // to this screen. Launching app-details always resolves; guard defensively.
                        viewModel.markBatteryPromptHandled()
                        runCatching { context.startActivity(viewModel.batterySettingsIntent()) }
                    },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                ) {
                    Text(stringResource(R.string.onboarding_battery_take_me))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                ) {
                    Text(stringResource(R.string.onboarding_battery_not_now))
                }
            }
        }
    }
}
