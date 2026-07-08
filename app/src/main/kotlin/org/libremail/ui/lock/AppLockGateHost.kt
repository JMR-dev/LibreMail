// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.lock

import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }

    // BiometricPrompt must be hosted by a FragmentActivity; LocalActivity is the hosting Activity.
    val activity = LocalActivity.current
    val fragmentActivity = remember(activity) { activity as? FragmentActivity }

    val promptTitle = stringResource(R.string.app_lock_prompt_title)
    val promptSubtitle = stringResource(R.string.app_lock_prompt_subtitle)
    val authenticate: () -> Unit = remember(fragmentActivity, viewModel, promptTitle, promptSubtitle) {
        authenticate@{
            val host = fragmentActivity ?: run {
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
    }

    // Once unlocked, keep [content] in the composition across later re-locks so its state (navigation
    // position, in-progress compose drafts, scroll) survives — the lock screen is drawn OVER it rather
    // than replacing it. Content is never composed before the first unlock, so no DB-backed screen
    // opens while the cache is still locked; a fresh process starts locked, so [remember] (not
    // rememberSaveable) is intentional — content stays uncomposed until this session authenticates.
    var hasEverUnlocked by remember { mutableStateOf(uiState is AppLockUiState.Unlocked) }
    LaunchedEffect(uiState) { if (uiState is AppLockUiState.Unlocked) hasEverUnlocked = true }

    Box(modifier = Modifier.fillMaxSize()) {
        // While the app is covered (Checking/Locked) the opaque LockCover hides the content visually and
        // blocks its input, but the content stays in the composition (so its state survives a re-lock).
        // Clear the covered subtree out of the semantics/accessibility tree so an accessibility service
        // (e.g. TalkBack) can't traverse the occluded mailbox/compose nodes behind the cover (#308). When
        // Unlocked the modifier is dropped, restoring the content's full semantics.
        val contentCovered = uiState !is AppLockUiState.Unlocked
        Box(
            modifier = if (contentCovered) {
                Modifier.fillMaxSize().clearAndSetSemantics { }
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            if (hasEverUnlocked) content()
        }

        when (val state = uiState) {
            AppLockUiState.Unlocked -> Unit

            AppLockUiState.Checking -> LockCover()

            is AppLockUiState.Locked -> {
                LockCover { LockScreen(error = state.error, onUnlock = authenticate) }
                // Auto-present the prompt when the lock screen (re)appears from an unlocked/checking
                // state; the button covers manual retries. Keyed to entering the Locked branch, so a
                // retry (which stays in this branch) shows its error without re-triggering the prompt.
                LaunchedEffect(Unit) { authenticate() }
            }
        }
    }
}

/**
 * Opaque, input-blocking overlay drawn on top of [content] while the app is locked or still
 * resolving, so nothing underneath is visible or interactable. Screenshot / recents protection is the
 * window's FLAG_SECURE (set by MainActivity while app-lock is on), which the composition can't do.
 */
@Composable
private fun LockCover(content: @Composable () -> Unit = {}) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) { content() }
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
