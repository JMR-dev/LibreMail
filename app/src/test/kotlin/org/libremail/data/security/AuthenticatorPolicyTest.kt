// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the single-source-of-truth authenticator mapping. [AuthenticatorPolicy.ACCEPTED] is the one
 * definition; the two derived flag sets translate it into each Android API's own vocabulary. If a
 * future change loosens or tightens one, both must move together — these assertions catch the drift
 * that is otherwise only observable on a device (a BiometricPrompt that succeeds but a Keystore key
 * that then throws UserNotAuthenticatedException at use).
 *
 * The referenced SDK constants are Java compile-time constants, so they inline into this test on the
 * plain JVM — no Android runtime is needed to compare the values.
 */
class AuthenticatorPolicyTest {

    @Test
    fun `accepted set is a strong biometric or the device credential`() {
        assertEquals(
            setOf(AppAuthenticator.STRONG_BIOMETRIC, AppAuthenticator.DEVICE_CREDENTIAL),
            AuthenticatorPolicy.ACCEPTED,
        )
    }

    @Test
    fun `maps to the BiometricManager vocabulary for the BiometricPrompt`() {
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            AuthenticatorPolicy.biometricPromptAuthenticators,
        )
    }

    @Test
    fun `maps to the KeyProperties vocabulary for the KeyGenParameterSpec`() {
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            AuthenticatorPolicy.keyGenAuthenticators,
        )
    }

    @Test
    fun `AppLockManager AUTHENTICATORS is the biometric-prompt mapping, not an independent copy`() {
        assertEquals(AuthenticatorPolicy.biometricPromptAuthenticators, AppLockManager.AUTHENTICATORS)
    }

    @Test
    fun `the two API vocabularies are genuinely different bit sets`() {
        // Why a single shared Int would be a bug: the same concept has different flag values in each
        // API, so the policy has to be mapped, not copied.
        assertNotEquals(
            AuthenticatorPolicy.biometricPromptAuthenticators,
            AuthenticatorPolicy.keyGenAuthenticators,
        )
    }
}
