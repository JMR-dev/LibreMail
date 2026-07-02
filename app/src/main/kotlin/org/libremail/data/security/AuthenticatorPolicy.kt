// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager

/** A user-presence proof the app accepts to unlock the app lock and authorize the auth-bound key. */
enum class AppAuthenticator { STRONG_BIOMETRIC, DEVICE_CREDENTIAL }

/**
 * THE single source of truth for which authenticators gate the app lock. The same [ACCEPTED] set is
 * mapped into each Android API's own flag vocabulary — androidx [BiometricManager] (for
 * `BiometricPrompt.setAllowedAuthenticators`, via [AppLockManager.AUTHENTICATORS]) and platform
 * [KeyProperties] (for `KeyGenParameterSpec.setUserAuthenticationParameters`, via
 * [DatabaseKeyCipher]) — because the two APIs use *different* bit constants for the same concept
 * (`BIOMETRIC_STRONG` is `0xF` here, `AUTH_BIOMETRIC_STRONG` is `1` there).
 *
 * Deriving both flag sets from one [ACCEPTED] set — with an exhaustive `when` that the compiler forces
 * to cover every [AppAuthenticator] — makes it impossible to loosen or tighten one without the other.
 * That drift is otherwise invisible until a device hits it: the `BiometricPrompt` would accept an
 * authenticator the key does not, so the prompt succeeds but the key then throws
 * `UserNotAuthenticatedException` at use.
 */
object AuthenticatorPolicy {

    /** Accept a strong biometric OR the device credential (PIN / pattern / password) as fallback. */
    val ACCEPTED: Set<AppAuthenticator> =
        setOf(AppAuthenticator.STRONG_BIOMETRIC, AppAuthenticator.DEVICE_CREDENTIAL)

    /** [ACCEPTED] as androidx `BiometricManager.Authenticators` flags for a `BiometricPrompt`. */
    val biometricPromptAuthenticators: Int = ACCEPTED.toFlags {
        when (it) {
            AppAuthenticator.STRONG_BIOMETRIC -> BiometricManager.Authenticators.BIOMETRIC_STRONG
            AppAuthenticator.DEVICE_CREDENTIAL -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    /** [ACCEPTED] as platform `KeyProperties.AUTH_*` flags for a `KeyGenParameterSpec`. */
    val keyGenAuthenticators: Int = ACCEPTED.toFlags {
        when (it) {
            AppAuthenticator.STRONG_BIOMETRIC -> KeyProperties.AUTH_BIOMETRIC_STRONG
            AppAuthenticator.DEVICE_CREDENTIAL -> KeyProperties.AUTH_DEVICE_CREDENTIAL
        }
    }

    private inline fun Set<AppAuthenticator>.toFlags(flagOf: (AppAuthenticator) -> Int): Int =
        fold(0) { acc, authenticator -> acc or flagOf(authenticator) }
}
