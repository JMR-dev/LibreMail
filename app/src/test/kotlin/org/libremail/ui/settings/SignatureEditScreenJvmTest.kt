// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose tests for [SignatureEditScreen] (umbrella #373, batch #380). Drives the
 * signature editor on the JVM under [RobolectricTestRunner] via the v2 `createComposeRule()` — no
 * emulator — with a MockK [SignatureEditViewModel] (its own logic is covered by
 * `SignatureEditViewModelTest`) so `SignatureEditScreen.kt` counts toward JaCoCo's JVM-testable surface:
 * the new-vs-edit title branch, the name field, the loaded/not-loaded body-field branch, the name-change
 * callback, and the save button (including its saving-disabled state). The instrumented
 * `SignatureEditScreenTest` stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SignatureEditScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private val lifecycleOwner: LifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun mockViewModel(isNew: Boolean, state: SignatureEditUiState): SignatureEditViewModel {
        val viewModel = mockk<SignatureEditViewModel>(relaxed = true)
        every { viewModel.isNew } returns isNew
        every { viewModel.state } returns MutableStateFlow(state)
        return viewModel
    }

    private fun setContent(viewModel: SignatureEditViewModel, onBack: () -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    SignatureEditScreen(onBack = onBack, viewModel = viewModel)
                }
            }
        }
    }

    @Test
    fun newSignature_showsNewTitle_andNameField() {
        setContent(mockViewModel(isNew = true, state = SignatureEditUiState(loaded = true)))

        composeTestRule.onNodeWithText(string(R.string.signature_new_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.signature_name)).assertIsDisplayed()
    }

    @Test
    fun existingSignature_showsEditTitle_prefillsName_andShowsBodyField() {
        setContent(
            mockViewModel(
                isNew = false,
                state = SignatureEditUiState(
                    name = "Personal",
                    body = "Regards",
                    bodyHtml = "<p>Regards</p>",
                    loaded = true,
                ),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.signature_edit_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal").assertIsDisplayed()
        // The rich-text body field (loaded == true) renders with the "signature content" label.
        composeTestRule.onNodeWithText(string(R.string.signature_content)).assertIsDisplayed()
    }

    @Test
    fun notLoaded_hidesBodyField() {
        setContent(mockViewModel(isNew = false, state = SignatureEditUiState(name = "Personal", loaded = false)))

        // The body field is gated on state.loaded, so its label is absent until the signature loads.
        composeTestRule.onAllNodesWithText(string(R.string.signature_content)).assertCountEquals(0)
    }

    @Test
    fun typingIntoName_callsOnNameChange() {
        val viewModel = mockViewModel(isNew = true, state = SignatureEditUiState(loaded = true))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.signature_name)).performTextInput("Work")

        verify { viewModel.onNameChange(any()) }
    }

    @Test
    fun tappingSave_callsViewModel() {
        val viewModel = mockViewModel(isNew = true, state = SignatureEditUiState(loaded = true, saving = false))
        setContent(viewModel)

        composeTestRule.onNodeWithText(string(R.string.signature_save)).performClick()

        verify { viewModel.save(any()) }
    }

    @Test
    fun whileSaving_saveButtonIsDisabled() {
        setContent(mockViewModel(isNew = true, state = SignatureEditUiState(loaded = true, saving = true)))

        composeTestRule.onNodeWithText(string(R.string.signature_save)).assert(isNotEnabled())
    }
}
