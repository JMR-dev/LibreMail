// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.data.security.AppLockManager

/**
 * Wraps the whole app in the screen-lock gate. While the app is locked it shows [LockScreen] and
 * presents a `BiometricPrompt` (strong biometric with device-credential fallback); [content] — the
 * real app — is composed only once [AppLockViewModel] reports [AppLockUiState.Unlocked], which also
 * guarantees the encrypted cache's passphrase has been unwrapped before any DB-backed screen opens.
 *
 * Must be hosted by a [FragmentActivity]; `BiometricPrompt` requires one.
 */
@Composable
fun AppLockGateHost(viewModel: AppLockViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = context.findFragmentActivity()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val promptTitle = stringResource(R.string.app_lock_prompt_title)
    val promptSubtitle = stringResource(R.string.app_lock_prompt_subtitle)
    val authenticate: () -> Unit = authenticate@{
        val host = activity ?: run {
            viewModel.onAuthError(null)
            return@authenticate
        }
        host.showAppLockPrompt(
            title = promptTitle,
            subtitle = promptSubtitle,
            onSuccess = viewModel::onAuthenticated,
            onError = { message -> viewModel.onAuthError(message) },
        )
    }

    when (val state = uiState) {
        AppLockUiState.Checking ->
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}

        AppLockUiState.Unlocked -> content()

        is AppLockUiState.Locked -> {
            LockScreen(error = state.error, onUnlock = authenticate)
            // Auto-present the prompt the first time the lock screen appears; the button covers retries.
            LaunchedEffect(Unit) { authenticate() }
        }
    }
}

/**
 * Presents a `BiometricPrompt` accepting a strong biometric OR the device credential. No negative
 * button is set because that is disallowed when [DEVICE_CREDENTIAL][AppLockManager.AUTHENTICATORS] is
 * an allowed authenticator. DEVICE-ONLY behavior; cannot be exercised in JVM unit tests.
 */
private fun FragmentActivity.showAppLockPrompt(
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String?) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(this)
    val prompt = BiometricPrompt(
        this,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(AppLockManager.AUTHENTICATORS)
        .build()
    prompt.authenticate(info)
}

private tailrec fun android.content.Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
