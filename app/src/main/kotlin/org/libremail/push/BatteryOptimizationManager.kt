// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and deep-links this app's system battery-optimization state, so the user can move LibreMail
 * to "Unrestricted" and keep [IdleService]'s push connection (and periodic sync) from being throttled
 * or torn down by Doze.
 *
 * We deliberately do NOT use the restricted `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission or its
 * one-tap dialog: Google Play limits that permission to an approved set of use cases (rejection risk,
 * see #17). Sending the user to the system screen instead needs no extra permission and is safe on
 * both Play and F-Droid (#16).
 *
 * Note [isIgnoringBatteryOptimizations] reflects the Doze allowlist: it is `true` only for the
 * "Unrestricted" setting and `false` for *both* "Optimized" and "Restricted", so it cannot single out
 * the (most harmful) "Restricted" state on its own.
 */
@Singleton
class BatteryOptimizationManager @Inject constructor(@ApplicationContext private val context: Context) {
    /**
     * Whether the platform exposes battery-optimization control. The Doze allowlist has existed since
     * API 23, so at our minSdk (29) this is always true; the version check keeps [BatteryPromptDecision]
     * honest if the floor ever drops below API 23.
     */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** True when this app is currently exempt from battery optimization ("Unrestricted"). */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent to this app's system details screen, where the user can open **Battery** and choose
     * **Unrestricted**. App-details is targeted (rather than the flat battery-optimization list)
     * because it is the only route to the Unrestricted/Optimized/Restricted setting and lands
     * directly on LibreMail. Always resolvable since API 9.
     */
    fun settingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
}
