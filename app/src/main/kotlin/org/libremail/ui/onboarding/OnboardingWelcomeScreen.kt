// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.libremail.R

/**
 * First-run welcome. Invites the user to connect their first mailbox and hands off to the vendor
 * picker. Shown once the user has agreed to the GPL-3.0 license (see `LicenseScreen.kt`, #172): the
 * onboarding graph's start destination when no accounts exist and the license hasn't been accepted
 * yet; an already-accepted user lands here directly, same as before #172.
 */
@Composable
fun OnboardingWelcomeScreen(onAddAccount: () -> Unit) {
    // Requested from here, rather than the Activity root, so the system permission dialog appears
    // once this screen (with onboarding context behind it) is actually visible instead of racing
    // the cold-start/splash transition (#151). Already-onboarded users skip onboarding entirely, so
    // this composable — and the request — never runs for them.
    //
    // #172 inserted the license-agreement screen ahead of this one, so this is now the SECOND
    // onboarding screen, not the first — but the request stays here rather than moving earlier: it
    // must fire only once onboarding context is visible, which now additionally means only after the
    // user has agreed to the license. Do not call NotificationPermissionEffect() from
    // LicenseScreen.kt.
    NotificationPermissionEffect()
    Scaffold { padding ->
        WelcomeContent(
            onAddAccount = onAddAccount,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/**
 * The welcome body: a headline, a short subtitle, and the "Add account" call to action. Extracted so
 * the mailbox's empty state (when the last account is removed) reuses the exact same invitation
 * instead of a separate blank-inbox screen.
 */
@Composable
fun WelcomeContent(onAddAccount: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Email,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onAddAccount,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp),
        ) {
            Text(stringResource(R.string.onboarding_add_account))
        }
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
