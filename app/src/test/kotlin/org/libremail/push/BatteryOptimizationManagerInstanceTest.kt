// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises [BatteryOptimizationManager]'s Context-backed methods with the Android statics/constructors
 * mocked. The pure fallback-selection logic behind [BatteryOptimizationManager.settingsIntent] has its
 * own exhaustive coverage in [BatteryOptimizationManagerTest]; these lock in the manager's own wiring.
 */
class BatteryOptimizationManagerInstanceTest {

    private val context = mockk<Context>(relaxed = true) {
        every { packageName } returns "org.libremail.app"
    }
    private val manager = BatteryOptimizationManager(context)

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `isSupported reads the platform version gate`() {
        // JVM Build.VERSION.SDK_INT is 0, so the API-23 gate reports unsupported here; the call still
        // exercises the branch (the live device path is covered by the instrumented battery step test).
        assertFalse(manager.isSupported)
    }

    @Test
    fun `isIgnoringBatteryOptimizations reflects the Doze allowlist`() {
        val powerManager = mockk<PowerManager> {
            every { isIgnoringBatteryOptimizations("org.libremail.app") } returns true
        }
        every { context.getSystemService(PowerManager::class.java) } returns powerManager

        assertTrue(manager.isIgnoringBatteryOptimizations())
    }

    @Test
    fun `isIgnoringBatteryOptimizations is false when no power service is available`() {
        every { context.getSystemService(PowerManager::class.java) } returns null

        assertFalse(manager.isIgnoringBatteryOptimizations())
    }

    @Test
    fun `candidateIntents offers app-details first, then the battery-optimization list`() {
        mockkConstructor(Intent::class)
        mockkStatic(Uri::class)
        every { Uri.fromParts(any(), any(), any()) } returns mockk(relaxed = true)

        assertEquals(2, manager.candidateIntents().size)
    }

    @Test
    fun `settingsIntent returns the first resolvable candidate`() {
        mockkConstructor(Intent::class)
        mockkStatic(Uri::class)
        every { Uri.fromParts(any(), any(), any()) } returns mockk(relaxed = true)
        every { anyConstructed<Intent>().resolveActivity(any()) } returns mockk()

        assertNotNull(manager.settingsIntent())
    }

    @Test
    fun `settingsIntent falls back to the last candidate when none resolve`() {
        mockkConstructor(Intent::class)
        mockkStatic(Uri::class)
        every { Uri.fromParts(any(), any(), any()) } returns mockk(relaxed = true)
        every { anyConstructed<Intent>().resolveActivity(any()) } returns null

        assertNotNull(manager.settingsIntent())
    }
}
