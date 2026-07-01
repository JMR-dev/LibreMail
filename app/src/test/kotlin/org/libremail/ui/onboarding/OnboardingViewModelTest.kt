// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.settings.SettingsRepository
import org.libremail.push.BatteryOptimizationManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun batteryManager(supported: Boolean = true, unrestricted: Boolean = false) =
        mockk<BatteryOptimizationManager> {
            every { isSupported } returns supported
            every { isIgnoringBatteryOptimizations() } returns unrestricted
        }

    private fun settingsRepository(handled: Boolean = false) = mockk<SettingsRepository> {
        coEvery { isBatteryPromptHandled() } returns handled
    }

    @Test
    fun `battery prompt is needed when not unrestricted and not handled`() = runTest(testDispatcher) {
        val vm = OnboardingViewModel(batteryManager(unrestricted = false), settingsRepository(handled = false))

        assertEquals(true, vm.batteryPromptNeeded.value)
        assertFalse(vm.batteryUnrestricted.value)
    }

    @Test
    fun `battery prompt is skipped when the app is already unrestricted`() = runTest(testDispatcher) {
        val vm = OnboardingViewModel(batteryManager(unrestricted = true), settingsRepository(handled = false))

        assertEquals(false, vm.batteryPromptNeeded.value)
        assertTrue(vm.batteryUnrestricted.value)
    }

    @Test
    fun `battery prompt is skipped once it has been handled`() = runTest(testDispatcher) {
        val vm = OnboardingViewModel(batteryManager(unrestricted = false), settingsRepository(handled = true))

        assertEquals(false, vm.batteryPromptNeeded.value)
    }

    @Test
    fun `only the first added account id is remembered`() = runTest(testDispatcher) {
        val vm = OnboardingViewModel(batteryManager(), settingsRepository())

        assertNull(vm.firstAddedAccountId)
        vm.onAccountAdded("imap:first@example.com")
        vm.onAccountAdded("imap:second@example.com")

        assertEquals("imap:first@example.com", vm.firstAddedAccountId)
    }

    @Test
    fun `marking the prompt handled persists the flag`() = runTest(testDispatcher) {
        val repo = settingsRepository()
        coEvery { repo.setBatteryPromptHandled(any()) } just Runs
        val vm = OnboardingViewModel(batteryManager(), repo)

        vm.markBatteryPromptHandled()

        coVerify { repo.setBatteryPromptHandled(true) }
    }

    @Test
    fun `refresh re-reads the live battery status`() = runTest(testDispatcher) {
        val manager = mockk<BatteryOptimizationManager> {
            every { isSupported } returns true
            // First read (init) is not-unrestricted; the second (refresh) reflects the user's change.
            every { isIgnoringBatteryOptimizations() } returnsMany listOf(false, true)
        }
        val vm = OnboardingViewModel(manager, settingsRepository())
        assertFalse(vm.batteryUnrestricted.value)

        vm.refreshBatteryStatus()

        assertTrue(vm.batteryUnrestricted.value)
    }
}
