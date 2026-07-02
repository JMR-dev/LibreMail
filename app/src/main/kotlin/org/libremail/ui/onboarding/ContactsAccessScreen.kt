// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R

/**
 * Optional onboarding step (shown only when needed, see [OnboardingViewModel.contactsPromptNeeded]):
 * invites the user to allow contacts access for on-device recipient autocomplete. The rationale is
 * on-screen up front — contacts are used **only** for suggesting recipients while composing and are
 * never uploaded (#128) — and the step is clearly skippable (#127): **Not now** proceeds without it.
 *
 * The `READ_CONTACTS` request fires **once**, from here — the compose screen no longer prompts. After
 * a grant the screen shows a "done" state; a later change of heart is handled by the Settings entry
 * (#129). On returning from anywhere the grant is re-read so the screen reflects the current state.
 *
 * @param viewModel the graph-scoped onboarding view model (holds the live grant + the handled flag).
 * @param onFinish leaves the step for the next destination; the caller also marks the prompt handled.
 */
@Composable
fun ContactsAccessScreen(viewModel: OnboardingViewModel, onFinish: () -> Unit) {
    val granted by viewModel.contactsGranted.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var showRationale by remember { mutableStateOf(false) }

    fun refreshRationale() {
        showRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CONTACTS)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        viewModel.onContactsPermissionResult(result)
        refreshRationale()
    }

    // Re-check the grant (and whether a rationale is now owed) on resume so a change made elsewhere —
    // e.g. the user granted from system settings — is reflected when this step comes back to the fore.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshContactsStatus()
        refreshRationale()
    }

    ContactsAccessContent(
        granted = granted,
        showRationale = showRationale,
        onAllow = {
            // Persist "the dialog was shown" up front so a permanent denial is later distinguishable
            // from "never asked" in Settings, even if the process dies before the result arrives.
            viewModel.markContactsPermissionRequested()
            launcher.launch(Manifest.permission.READ_CONTACTS)
        },
        onSkip = onFinish,
        onContinue = onFinish,
    )
}

/**
 * Presentational body of the contacts-access step, split out so its three paths — skip, grant (the
 * "done" state), and request (with the [showRationale] re-ask explanation) — are driven deterministically
 * in tests without a live system permission dialog.
 */
@Composable
fun ContactsAccessContent(
    granted: Boolean,
    showRationale: Boolean,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
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
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(
                    if (granted) R.string.onboarding_contacts_done_title else R.string.onboarding_contacts_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (granted) R.string.onboarding_contacts_done_body else R.string.onboarding_contacts_body,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            if (granted) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                ) {
                    Text(stringResource(R.string.onboarding_contacts_continue))
                }
            } else {
                if (showRationale) {
                    Text(
                        text = stringResource(R.string.onboarding_contacts_rationale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                }
                Button(
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                ) {
                    Text(stringResource(R.string.onboarding_contacts_allow))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                ) {
                    Text(stringResource(R.string.onboarding_contacts_not_now))
                }
            }
        }
    }
}
