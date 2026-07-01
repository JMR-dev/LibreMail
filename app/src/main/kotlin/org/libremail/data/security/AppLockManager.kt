// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a usable device authenticator (strong biometric or device credential) is available. */
enum class AppLockAvailability {
    /** A biometric or device credential can be presented right now. */
    AVAILABLE,

    /** Hardware exists but nothing (biometric or PIN/pattern/password) is enrolled. */
    NONE_ENROLLED,

    /** No suitable authentication hardware, or it is temporarily unavailable. */
    NO_HARDWARE,

    /** Availability could not be determined (unknown status / security update required). */
    UNAVAILABLE,
}

/**
 * Queries device-lock and biometric availability for the app-lock feature. Real behavior depends on
 * the device keyguard and BiometricManager, so it is validated on-device, not in JVM tests.
 */
interface AppLockManager {

    /** True when the device has a secure lock screen (PIN, pattern, password, or biometric). */
    fun isDeviceSecure(): Boolean

    /** Whether a biometric or device-credential prompt can currently be shown. */
    fun availability(): AppLockAvailability

    companion object {
        /** Accept a strong biometric OR the device credential (PIN/pattern/password) as fallback. */
        const val AUTHENTICATORS: Int = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    }
}

@Singleton
class AndroidAppLockManager @Inject constructor(@ApplicationContext private val context: Context) : AppLockManager {

    private val keyguardManager: KeyguardManager?
        get() = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    override fun isDeviceSecure(): Boolean = keyguardManager?.isDeviceSecure == true

    override fun availability(): AppLockAvailability =
        when (BiometricManager.from(context).canAuthenticate(AppLockManager.AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AppLockAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AppLockAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> AppLockAvailability.NO_HARDWARE
            else -> AppLockAvailability.UNAVAILABLE
        }
}
