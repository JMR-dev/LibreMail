// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real [BatteryOptimizationManager.candidateIntents]/[BatteryOptimizationManager.settingsIntent]
 * against a real `Context`/`PackageManager` (#150): `Intent`/`Uri`/`PackageManager.resolveActivity` are
 * unmocked SDK stubs off-device, so this on-device coverage is the only place the actual candidate
 * actions, order, and package scoping can be checked directly. The fallback-selection logic itself
 * (which candidate wins, and that there's always a last resort) is covered Android-free by
 * `BatteryOptimizationManagerTest` in the `test` source set; end-to-end launch-from-the-onboarding-
 * button coverage lives in `BatteryOptimizationStepTest`.
 */
@RunWith(AndroidJUnit4::class)
class BatteryOptimizationManagerIntentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = BatteryOptimizationManager(context)

    @Test
    fun candidateIntents_tryAppDetailsFirst_thenTheBatteryOptimizationList() {
        val candidates = manager.candidateIntents()

        assertEquals(2, candidates.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, candidates[0].action)
        assertEquals(Uri.fromParts("package", context.packageName, null), candidates[0].data)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, candidates[1].action)
    }

    @Test
    fun settingsIntent_landsOnAppDetails_becauseItAlwaysResolvesOnARealDevice() {
        // Every real/emulated Android device ships a Settings app that resolves app-details for any
        // installed package, so the primary (most direct) candidate always wins here. The fallback
        // exists for devices this test environment can't represent (see the class KDoc).
        val intent = manager.settingsIntent()

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.fromParts("package", context.packageName, null), intent.data)
    }
}
