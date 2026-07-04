// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.SavedStateHandle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Signature
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignatureEditViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        signatureRepository: SignatureRepository,
        signatureId: String? = null,
    ): SignatureEditViewModel {
        val args = mutableMapOf<String, Any?>(Routes.SIGNATURE_EDIT_ARG_ACCOUNT to ACCOUNT)
        if (signatureId != null) args[Routes.SIGNATURE_EDIT_ARG_ID] = signatureId
        return SignatureEditViewModel(SavedStateHandle(args), signatureRepository)
    }

    @Test
    fun `a new signature is immediately loaded and blank`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        val vm = viewModel(repo)

        assertTrue(vm.isNew)
        assertTrue(vm.state.value.loaded)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.body)
        assertNull(vm.state.value.bodyHtml)
    }

    @Test
    fun `a blank signature id argument is treated as a new signature`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        val vm = viewModel(repo, signatureId = "   ")

        assertTrue(vm.isNew)
        assertTrue(vm.state.value.loaded)
        coVerify(exactly = 0) { repo.get(any()) }
    }

    @Test
    fun `an existing signature is loaded into the form`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        coEvery { repo.get("sig-1") } returns
            Signature(id = "sig-1", accountId = ACCOUNT, name = "Work", html = "<p>Regards</p>")
        val vm = viewModel(repo, signatureId = "sig-1")
        advanceUntilIdle()

        assertFalse(vm.isNew)
        assertTrue(vm.state.value.loaded)
        assertEquals("Work", vm.state.value.name)
        assertEquals("Regards", vm.state.value.body)
        assertEquals("<p>Regards</p>", vm.state.value.bodyHtml)
    }

    @Test
    fun `an existing signature with blank html keeps bodyHtml null`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        coEvery { repo.get("sig-1") } returns Signature(id = "sig-1", accountId = ACCOUNT, name = "Empty", html = "")
        val vm = viewModel(repo, signatureId = "sig-1")
        advanceUntilIdle()

        assertNull(vm.state.value.bodyHtml)
        assertTrue(vm.state.value.loaded)
    }

    @Test
    fun `a missing existing signature leaves the form unloaded`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        coEvery { repo.get("gone") } returns null
        val vm = viewModel(repo, signatureId = "gone")
        advanceUntilIdle()

        assertFalse(vm.isNew)
        assertFalse(vm.state.value.loaded)
    }

    @Test
    fun `onNameChange and onBodyChange update the form state`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        val vm = viewModel(repo)

        vm.onNameChange("Personal")
        vm.onBodyChange("hi there", "<b>hi there</b>")

        assertEquals("Personal", vm.state.value.name)
        assertEquals("hi there", vm.state.value.body)
        assertEquals("<b>hi there</b>", vm.state.value.bodyHtml)
    }

    @Test
    fun `saving a new signature with a blank name falls back to the default name`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        coEvery { repo.create(any(), any(), any()) } returns "new-id"
        val vm = viewModel(repo)
        var saved = false

        vm.onBodyChange("plain body", null)
        vm.save { saved = true }
        advanceUntilIdle()

        // Blank name -> "Signature"; html is derived from the plaintext when the form has no rich HTML.
        coVerify { repo.create(ACCOUNT, "Signature", match { it.contains("plain body") }) }
        assertTrue(saved)
    }

    @Test
    fun `saving a new signature persists its rich html verbatim when present`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        coEvery { repo.create(any(), any(), any()) } returns "new-id"
        val vm = viewModel(repo)

        vm.onNameChange("  Trimmed  ")
        vm.onBodyChange("body", "<i>body</i>")
        vm.save {}
        advanceUntilIdle()

        coVerify { repo.create(ACCOUNT, "Trimmed", "<i>body</i>") }
    }

    @Test
    fun `a rapid double-tap on Save creates the signature only once`() {
        // Runs on a StandardTestDispatcher so the save coroutine is queued (not run eagerly): the
        // second tap lands before it runs, exercising the synchronous `saving` guard (#304).
        val standardMain = StandardTestDispatcher()
        Dispatchers.setMain(standardMain)
        val repo = mockk<SignatureRepository>(relaxed = true)
        coEvery { repo.create(any(), any(), any()) } returns "new-id"
        var savedCount = 0
        runTest(standardMain) {
            val vm = viewModel(repo)
            vm.onBodyChange("body", null)

            vm.save { savedCount++ }
            vm.save { savedCount++ } // double-tap before the first save is dispatched
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.create(any(), any(), any()) }
            assertEquals(1, savedCount)
        }
    }

    @Test
    fun `saving an existing signature updates it and invokes the callback`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        coEvery { repo.get("sig-1") } returns
            Signature(id = "sig-1", accountId = ACCOUNT, name = "Work", html = "<p>Regards</p>")
        coEvery { repo.update(any(), any(), any()) } just Runs
        val vm = viewModel(repo, signatureId = "sig-1")
        advanceUntilIdle()
        var saved = false

        vm.onNameChange("Work v2")
        vm.save { saved = true }
        advanceUntilIdle()

        coVerify { repo.update("sig-1", "Work v2", "<p>Regards</p>") }
        coVerify(exactly = 0) { repo.create(any(), any(), any()) }
        assertTrue(saved)
    }

    @Test
    fun `SignatureEditUiState value semantics`() {
        val state = SignatureEditUiState(name = "n", body = "b", bodyHtml = "<b>b</b>", loaded = true)

        assertEquals(state, state.copy())
        assertEquals(state.hashCode(), state.copy().hashCode())
        assertTrue(state.toString().contains("n"))
        assertNotEquals(state, state.copy(name = "other"))
        assertNotEquals(state, state.copy(body = "other"))
        assertNotEquals(state, state.copy(bodyHtml = null))
        assertNotEquals(state, state.copy(loaded = false))
    }

    private companion object {
        const val ACCOUNT = "imap:a"
    }
}
