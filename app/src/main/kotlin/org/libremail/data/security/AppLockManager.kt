// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports whether the device has a secure lock, for the app-lock feature. Real behavior depends on
 * the device keyguard, so it is validated on-device, not in JVM tests.
 */
interface AppLockManager {

    /** True when the device has a secure lock screen (PIN, pattern, password, or biometric). */
    fun isDeviceSecure(): Boolean

    companion object {
        /**
         * `BiometricPrompt` authenticators (a strong biometric OR the device credential). Derived
         * from the single [AuthenticatorPolicy] source of truth so it can never drift from the
         * auth-bound Keystore key's authenticators in [DatabaseKeyCipher] — a drift that would let a
         * prompt succeed against an authenticator the key rejects with `UserNotAuthenticatedException`.
         */
        val AUTHENTICATORS: Int = AuthenticatorPolicy.biometricPromptAuthenticators
    }
}

@Singleton
class AndroidAppLockManager @Inject constructor(@ApplicationContext private val context: Context) : AppLockManager {

    private val keyguardManager: KeyguardManager?
        get() = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    override fun isDeviceSecure(): Boolean = keyguardManager?.isDeviceSecure == true
}
