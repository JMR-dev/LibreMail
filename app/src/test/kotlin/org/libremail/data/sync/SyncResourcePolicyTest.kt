// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.settings.FetchPolicy
import org.libremail.power.BatteryStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncResourcePolicyTest {

    private fun battery(percent: Int, charging: Boolean = false) = BatteryStatus(percent, charging)

    private fun prefetch(policy: FetchPolicy, unmetered: Boolean = true, battery: BatteryStatus = battery(80)) =
        SyncResourcePolicy.shouldPrefetchContent(policy, { unmetered }, battery)

    // ---- isBatteryLow: the shared threshold (#89/#90) ----

    @Test
    fun `battery is low at exactly 20 percent and below, but not at 21`() {
        assertTrue(SyncResourcePolicy.isBatteryLow(battery(20)))
        assertTrue(SyncResourcePolicy.isBatteryLow(battery(1)))
        assertFalse(SyncResourcePolicy.isBatteryLow(battery(21)))
        assertFalse(SyncResourcePolicy.isBatteryLow(battery(100)))
    }

    @Test
    fun `charging exempts from the low-battery state at any percent`() {
        assertFalse(SyncResourcePolicy.isBatteryLow(battery(20, charging = true)))
        assertFalse(SyncResourcePolicy.isBatteryLow(battery(1, charging = true)))
    }

    // ---- shouldPrefetchContent: FetchPolicy x network x battery (#88/#89) ----

    @Test
    fun `healthy battery applies the fetch policy as-is`() {
        assertTrue(prefetch(FetchPolicy.ALWAYS, unmetered = false))
        assertTrue(prefetch(FetchPolicy.WIFI_ONLY, unmetered = true))
        assertFalse(prefetch(FetchPolicy.WIFI_ONLY, unmetered = false))
        assertFalse(prefetch(FetchPolicy.ON_DEMAND, unmetered = true))
    }

    @Test
    fun `low battery pauses prefetch for every policy`() {
        val low = battery(20)
        assertFalse(prefetch(FetchPolicy.ALWAYS, unmetered = true, battery = low))
        assertFalse(prefetch(FetchPolicy.WIFI_ONLY, unmetered = true, battery = low))
        assertFalse(prefetch(FetchPolicy.ON_DEMAND, unmetered = true, battery = low))
    }

    @Test
    fun `prefetch resumes one percent above the threshold`() {
        assertTrue(prefetch(FetchPolicy.ALWAYS, battery = battery(21)))
    }

    @Test
    fun `charging at low percent still prefetches`() {
        assertTrue(prefetch(FetchPolicy.ALWAYS, battery = battery(10, charging = true)))
    }

    @Test
    fun `charging does not override the network gate`() {
        assertFalse(prefetch(FetchPolicy.WIFI_ONLY, unmetered = false, battery = battery(10, charging = true)))
    }

    @Test
    fun `network state is only consulted for WIFI_ONLY on a healthy battery`() {
        val forbidden: () -> Boolean = { error("network must not be consulted") }
        assertTrue(SyncResourcePolicy.shouldPrefetchContent(FetchPolicy.ALWAYS, forbidden, battery(80)))
        assertFalse(SyncResourcePolicy.shouldPrefetchContent(FetchPolicy.ON_DEMAND, forbidden, battery(80)))
        // The battery gate short-circuits even the WIFI_ONLY network check.
        assertFalse(SyncResourcePolicy.shouldPrefetchContent(FetchPolicy.WIFI_ONLY, forbidden, battery(10)))
    }

    // ---- pushMode: IDLE vs POLLING with hysteresis (#90) ----

    @Test
    fun `push drops to polling at exactly 20 percent`() {
        assertEquals(PushMode.POLLING, SyncResourcePolicy.pushMode(battery(20), previous = PushMode.IDLE))
        assertEquals(PushMode.POLLING, SyncResourcePolicy.pushMode(battery(5), previous = PushMode.IDLE))
    }

    @Test
    fun `push stays on idle above the threshold`() {
        assertEquals(PushMode.IDLE, SyncResourcePolicy.pushMode(battery(21), previous = PushMode.IDLE))
    }

    @Test
    fun `inside the hysteresis band the previous mode is kept`() {
        for (percent in 21 until SyncResourcePolicy.PUSH_RESUME_PERCENT) {
            assertEquals(PushMode.POLLING, SyncResourcePolicy.pushMode(battery(percent), previous = PushMode.POLLING))
            assertEquals(PushMode.IDLE, SyncResourcePolicy.pushMode(battery(percent), previous = PushMode.IDLE))
        }
    }

    @Test
    fun `push resumes idle at the recovery threshold`() {
        assertEquals(
            PushMode.IDLE,
            SyncResourcePolicy.pushMode(battery(SyncResourcePolicy.PUSH_RESUME_PERCENT), previous = PushMode.POLLING),
        )
    }

    @Test
    fun `plugging in resumes idle immediately at any percent`() {
        val plugged = battery(5, charging = true)
        assertEquals(PushMode.IDLE, SyncResourcePolicy.pushMode(plugged, previous = PushMode.POLLING))
    }

    // ---- pushModes: the folded stream the IDLE service consumes (#90) ----

    @Test
    fun `stream starts in idle on a healthy battery and drops to polling at the threshold`() = runTest {
        val updates = MutableSharedFlow<BatteryStatus>()
        SyncResourcePolicy.pushModes(initial = battery(50), updates = updates).test {
            assertEquals(PushMode.IDLE, awaitItem())
            updates.emit(battery(20))
            assertEquals(PushMode.POLLING, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stream starts in polling when collection begins on an already-low battery`() = runTest {
        val updates = MutableSharedFlow<BatteryStatus>()
        SyncResourcePolicy.pushModes(initial = battery(12), updates = updates).test {
            assertEquals(PushMode.POLLING, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `jitter around the threshold does not flap the stream`() = runTest {
        val updates = MutableSharedFlow<BatteryStatus>()
        SyncResourcePolicy.pushModes(initial = battery(50), updates = updates).test {
            assertEquals(PushMode.IDLE, awaitItem())
            updates.emit(battery(20))
            assertEquals(PushMode.POLLING, awaitItem())
            // Bouncing 21 <-> 20 <-> 24 stays inside the hysteresis band: nothing is emitted.
            updates.emit(battery(21))
            updates.emit(battery(20))
            updates.emit(battery(24))
            expectNoEvents()
            // Only reaching the recovery threshold flips back.
            updates.emit(battery(SyncResourcePolicy.PUSH_RESUME_PERCENT))
            assertEquals(PushMode.IDLE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `plugging in while polling resumes idle without waiting for the recovery threshold`() = runTest {
        val updates = MutableSharedFlow<BatteryStatus>()
        SyncResourcePolicy.pushModes(initial = battery(10), updates = updates).test {
            assertEquals(PushMode.POLLING, awaitItem())
            updates.emit(battery(10, charging = true))
            assertEquals(PushMode.IDLE, awaitItem())
            // Unplugging below the threshold drops straight back to polling.
            updates.emit(battery(10))
            assertEquals(PushMode.POLLING, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
