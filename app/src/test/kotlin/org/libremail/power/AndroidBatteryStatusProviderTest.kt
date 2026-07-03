// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidBatteryStatusProviderTest {

    private val context = mockk<Context>(relaxed = true)
    private val provider = AndroidBatteryStatusProvider(context)

    @After
    fun tearDown() = unmockkAll()

    private fun batteryManager(percent: Int, charging: Boolean) = mockk<BatteryManager> {
        every { getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } returns percent
        every { isCharging } returns charging
    }

    private fun batteryIntent(level: Int, scale: Int, status: Int) = mockk<Intent> {
        every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns level
        every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns scale
        every { getIntExtra(BatteryManager.EXTRA_STATUS, -1) } returns status
    }

    @Test
    fun `current reads the one-shot capacity and charging state`() {
        every { context.getSystemService(BatteryManager::class.java) } returns batteryManager(55, charging = true)

        assertEquals(BatteryStatus(55, isCharging = true), provider.current())
    }

    @Test
    fun `current degrades to ASSUME_OK when the reported capacity is out of range`() {
        every { context.getSystemService(BatteryManager::class.java) } returns batteryManager(-1, charging = false)

        assertEquals(BatteryStatus.ASSUME_OK.percent, provider.current().percent)
    }

    @Test
    fun `current degrades to ASSUME_OK when there is no battery service`() {
        every { context.getSystemService(BatteryManager::class.java) } returns null

        assertEquals(BatteryStatus.ASSUME_OK, provider.current())
    }

    @Test
    fun `status emits the sticky snapshot immediately, then each live battery change`() = runTest {
        mockkStatic(ContextCompat::class)
        mockkConstructor(IntentFilter::class)
        val receiver = slot<BroadcastReceiver>()
        every { ContextCompat.registerReceiver(any(), capture(receiver), any(), any()) } returns
            batteryIntent(level = 50, scale = 100, status = BatteryManager.BATTERY_STATUS_CHARGING)

        provider.status().test {
            assertEquals(BatteryStatus(50, isCharging = true), awaitItem())

            receiver.captured.onReceive(
                context,
                batteryIntent(level = 80, scale = 100, status = BatteryManager.BATTERY_STATUS_DISCHARGING),
            )
            assertEquals(BatteryStatus(80, isCharging = false), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        // The receiver is unregistered when collection stops so the registration lasts exactly as long
        // as the flow is collected.
        verify { context.unregisterReceiver(any()) }
    }

    @Test
    fun `status maps unreadable battery extras to the ASSUME_OK percent and treats FULL as charging`() = runTest {
        mockkStatic(ContextCompat::class)
        mockkConstructor(IntentFilter::class)
        every { ContextCompat.registerReceiver(any(), any(), any(), any()) } returns
            batteryIntent(level = -1, scale = 0, status = BatteryManager.BATTERY_STATUS_FULL)

        provider.status().test {
            val status = awaitItem()
            assertEquals(BatteryStatus.ASSUME_OK.percent, status.percent)
            assertTrue(status.isCharging)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `status falls back to a one-shot read when there is no sticky broadcast`() = runTest {
        mockkStatic(ContextCompat::class)
        mockkConstructor(IntentFilter::class)
        every { ContextCompat.registerReceiver(any(), any(), any(), any()) } returns null
        every { context.getSystemService(BatteryManager::class.java) } returns batteryManager(42, charging = false)

        provider.status().test {
            assertEquals(BatteryStatus(42, isCharging = false), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
