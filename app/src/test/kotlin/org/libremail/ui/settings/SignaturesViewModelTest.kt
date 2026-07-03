// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.lifecycle.SavedStateHandle
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Signature
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SignaturesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun signature(id: String, isDefault: Boolean = false) =
        Signature(id = id, accountId = ACCOUNT, name = "Sig $id", html = "<p>hi</p>", isDefault = isDefault)

    private fun viewModel(signatureRepository: SignatureRepository) = SignaturesViewModel(
        SavedStateHandle(mapOf(Routes.SIGNATURES_ARG_ACCOUNT to ACCOUNT)),
        signatureRepository,
    )

    @Test
    fun `resolves the account id from the nav argument`() {
        val repo = mockk<SignatureRepository>(relaxed = true)
        every { repo.observeForAccount(ACCOUNT) } returns flowOf(emptyList())

        assertEquals(ACCOUNT, viewModel(repo).accountId)
    }

    @Test
    fun `signatures mirrors the repository stream for this account`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        every { repo.observeForAccount(ACCOUNT) } returns
            MutableStateFlow(listOf(signature("a", isDefault = true), signature("b")))
        val vm = viewModel(repo)

        backgroundScope.launch { vm.signatures.collect {} }
        runCurrent()

        assertEquals(listOf("a", "b"), vm.signatures.value.map { it.id })
    }

    @Test
    fun `setDefault delegates to the repository scoped to this account`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        every { repo.observeForAccount(ACCOUNT) } returns flowOf(emptyList())
        val vm = viewModel(repo)

        vm.setDefault("sig-1")

        coVerify { repo.setDefault(ACCOUNT, "sig-1") }
    }

    @Test
    fun `delete delegates to the repository`() = runTest(dispatcher) {
        val repo = mockk<SignatureRepository>(relaxed = true)
        every { repo.observeForAccount(ACCOUNT) } returns flowOf(emptyList())
        val vm = viewModel(repo)

        vm.delete("sig-2")

        coVerify { repo.delete("sig-2") }
    }

    private companion object {
        const val ACCOUNT = "imap:a"
    }
}
