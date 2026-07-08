// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.contacts.ContactsPermissionManager
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
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // onAccountAdded now breadcrumbs the first account via AppLog (#308); android.util.Log is a
        // no-op stub that throws "not mocked" under plain JVM tests, so stub it so the call can't crash.
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun batteryManager(supported: Boolean = true, unrestricted: Boolean = false) =
        mockk<BatteryOptimizationManager> {
            every { isSupported } returns supported
            every { isIgnoringBatteryOptimizations() } returns unrestricted
        }

    private fun contactsManager(granted: Boolean = false) = mockk<ContactsPermissionManager> {
        every { hasPermission() } returns granted
    }

    private fun settingsRepository(batteryHandled: Boolean = false, contactsHandled: Boolean = false) =
        mockk<SettingsRepository> {
            coEvery { isBatteryPromptHandled() } returns batteryHandled
            coEvery { isContactsPromptHandled() } returns contactsHandled
        }

    private fun viewModel(
        battery: BatteryOptimizationManager = batteryManager(),
        contacts: ContactsPermissionManager = contactsManager(),
        settings: SettingsRepository = settingsRepository(),
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) = OnboardingViewModel(battery, contacts, settings, savedStateHandle)

    @Test
    fun `battery prompt is needed when not unrestricted and not handled`() = runTest(testDispatcher) {
        val vm = viewModel(battery = batteryManager(unrestricted = false), settings = settingsRepository())

        assertEquals(true, vm.batteryPromptNeeded.value)
        assertFalse(vm.batteryUnrestricted.value)
    }

    @Test
    fun `battery prompt is skipped when the app is already unrestricted`() = runTest(testDispatcher) {
        val vm = viewModel(battery = batteryManager(unrestricted = true))

        assertEquals(false, vm.batteryPromptNeeded.value)
        assertTrue(vm.batteryUnrestricted.value)
    }

    @Test
    fun `battery prompt is skipped once it has been handled`() = runTest(testDispatcher) {
        val vm = viewModel(
            battery = batteryManager(unrestricted = false),
            settings = settingsRepository(batteryHandled = true),
        )

        assertEquals(false, vm.batteryPromptNeeded.value)
    }

    @Test
    fun `contacts prompt is needed when not granted and not handled`() = runTest(testDispatcher) {
        val vm = viewModel(
            contacts = contactsManager(granted = false),
            settings = settingsRepository(contactsHandled = false),
        )

        assertEquals(true, vm.contactsPromptNeeded.value)
        assertFalse(vm.contactsGranted.value)
    }

    @Test
    fun `contacts prompt is skipped when the permission is already granted`() = runTest(testDispatcher) {
        val vm = viewModel(contacts = contactsManager(granted = true))

        assertEquals(false, vm.contactsPromptNeeded.value)
        assertTrue(vm.contactsGranted.value)
    }

    @Test
    fun `contacts prompt is skipped once it has been handled`() = runTest(testDispatcher) {
        val vm = viewModel(
            contacts = contactsManager(granted = false),
            settings = settingsRepository(contactsHandled = true),
        )

        assertEquals(false, vm.contactsPromptNeeded.value)
    }

    @Test
    fun `only the first added account id is remembered`() = runTest(testDispatcher) {
        val vm = viewModel()

        assertNull(vm.firstAddedAccountId)
        vm.onAccountAdded("imap:first@example.com")
        vm.onAccountAdded("imap:second@example.com")

        assertEquals("imap:first@example.com", vm.firstAddedAccountId)
    }

    @Test
    fun `the first added account id survives a process-death recreation via SavedStateHandle`() =
        runTest(testDispatcher) {
            // #308: onboarding can be process-killed mid-flow (e.g. the excursion to system battery
            // settings). The first-account id lives in SavedStateHandle, so a recreated ViewModel over
            // the restored state must still finish onto that account's inbox rather than the unified one.
            val handle = SavedStateHandle()
            viewModel(savedStateHandle = handle).onAccountAdded("imap:first@example.com")

            // Simulate restoration after a kill: a brand-new handle seeded from the saved state, then a
            // fresh ViewModel over it (a plain field would have been lost — this is exactly #30's guard).
            val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            assertEquals("imap:first@example.com", viewModel(savedStateHandle = restored).firstAddedAccountId)
        }

    @Test
    fun `marking the battery prompt handled persists the flag`() = runTest(testDispatcher) {
        val repo = settingsRepository()
        coEvery { repo.setBatteryPromptHandled(any()) } just Runs
        val vm = viewModel(settings = repo)

        vm.markBatteryPromptHandled()

        coVerify { repo.setBatteryPromptHandled(true) }
    }

    @Test
    fun `marking the license accepted persists the flag`() = runTest(testDispatcher) {
        val repo = settingsRepository()
        coEvery { repo.setLicenseAccepted(any()) } just Runs
        val vm = viewModel(settings = repo)

        vm.markLicenseAccepted()

        coVerify { repo.setLicenseAccepted(true) }
    }

    @Test
    fun `marking the contacts prompt handled persists the flag`() = runTest(testDispatcher) {
        val repo = settingsRepository()
        coEvery { repo.setContactsPromptHandled(any()) } just Runs
        val vm = viewModel(settings = repo)

        vm.markContactsPromptHandled()

        coVerify { repo.setContactsPromptHandled(true) }
    }

    @Test
    fun `marking the contacts permission requested persists the flag`() = runTest(testDispatcher) {
        val repo = settingsRepository()
        coEvery { repo.setContactsPermissionRequested(any()) } just Runs
        val vm = viewModel(settings = repo)

        vm.markContactsPermissionRequested()

        coVerify { repo.setContactsPermissionRequested(true) }
    }

    @Test
    fun `a granted permission result flips contactsGranted on`() = runTest(testDispatcher) {
        val vm = viewModel(contacts = contactsManager(granted = false))
        assertFalse(vm.contactsGranted.value)

        vm.onContactsPermissionResult(true)

        assertTrue(vm.contactsGranted.value)
    }

    @Test
    fun `refresh re-reads the live battery status`() = runTest(testDispatcher) {
        val manager = mockk<BatteryOptimizationManager> {
            every { isSupported } returns true
            // First read (init) is not-unrestricted; the second (refresh) reflects the user's change.
            every { isIgnoringBatteryOptimizations() } returnsMany listOf(false, true)
        }
        val vm = viewModel(battery = manager)
        assertFalse(vm.batteryUnrestricted.value)

        vm.refreshBatteryStatus()

        assertTrue(vm.batteryUnrestricted.value)
    }

    @Test
    fun `refresh re-reads the live contacts grant`() = runTest(testDispatcher) {
        val manager = mockk<ContactsPermissionManager> {
            // First reads (init) report not-granted; a later read reflects the user granting it.
            every { hasPermission() } returnsMany listOf(false, false, true)
        }
        val vm = viewModel(contacts = manager)
        assertFalse(vm.contactsGranted.value)

        vm.refreshContactsStatus()

        assertTrue(vm.contactsGranted.value)
    }
}
