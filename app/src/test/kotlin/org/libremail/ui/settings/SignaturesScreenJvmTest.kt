// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.domain.model.Signature
import org.libremail.ui.theme.LibreMailTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric JVM Compose tests for [SignaturesScreen] (umbrella #373, batch #380). Drives the
 * signatures list on the JVM under [RobolectricTestRunner] via the v2 `createComposeRule()` — no
 * emulator — with a MockK [SignaturesViewModel] (its own logic is covered by `SignaturesViewModelTest`)
 * so `SignaturesScreen.kt` counts toward JaCoCo's JVM-testable surface: the empty state, name +
 * default-badge rendering, the row → edit tap, the make-default radio, the delete action, and the add FAB.
 * The instrumented `SignaturesScreenTest` stays as the on-device E2E. See
 * [org.libremail.ui.onboarding.AddAnotherAccountScreenJvmTest] for the pattern.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SignaturesScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private val lifecycleOwner: LifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun signature(id: String, name: String, isDefault: Boolean) =
        Signature(id = id, accountId = "imap:a", name = name, html = "<p>$name body</p>", isDefault = isDefault)

    private fun mockViewModel(signatures: List<Signature>): SignaturesViewModel {
        val viewModel = mockk<SignaturesViewModel>(relaxed = true)
        every { viewModel.signatures } returns MutableStateFlow(signatures)
        return viewModel
    }

    private fun setContent(viewModel: SignaturesViewModel, onEdit: (String) -> Unit = {}, onAdd: () -> Unit = {}) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                LibreMailTheme(darkTheme = false, dynamicColor = false) {
                    SignaturesScreen(onBack = {}, onEdit = onEdit, onAdd = onAdd, viewModel = viewModel)
                }
            }
        }
    }

    @Test
    fun noSignatures_showsEmptyState() {
        setContent(mockViewModel(emptyList()))

        composeTestRule.onNodeWithText(string(R.string.signatures_empty)).assertIsDisplayed()
    }

    @Test
    fun signatures_renderNames_withExactlyOneDefaultBadge() {
        setContent(
            mockViewModel(
                listOf(
                    signature("s1", "Work", isDefault = true),
                    signature("s2", "Personal", isDefault = false),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Work").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal").assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.signature_default_badge)).assertCountEquals(1)
    }

    @Test
    fun tappingASignatureRow_invokesOnEditWithItsId() {
        var editedId: String? = null
        setContent(
            mockViewModel(
                listOf(
                    signature("s1", "Work", isDefault = true),
                    signature("s2", "Personal", isDefault = false),
                ),
            ),
            onEdit = { editedId = it },
        )

        composeTestRule.onNodeWithText("Personal").performClick()
        assertEquals("s2", editedId)
    }

    @Test
    fun tappingRadioOnNonDefault_callsSetDefault() {
        val viewModel = mockViewModel(
            listOf(
                signature("s1", "Work", isDefault = true),
                signature("s2", "Personal", isDefault = false),
            ),
        )
        setContent(viewModel)

        // "Work" is the default (its radio is selected); the only unselected radio is "Personal"'s.
        composeTestRule.onNode(isSelectable() and isNotSelected()).performClick()

        verify { viewModel.setDefault("s2") }
    }

    @Test
    fun tappingDelete_callsDeleteForThatRow() {
        val viewModel = mockViewModel(
            listOf(
                signature("s1", "Work", isDefault = true),
                signature("s2", "Personal", isDefault = false),
            ),
        )
        setContent(viewModel)

        composeTestRule.onAllNodesWithContentDescription(string(R.string.signature_delete))[0].performClick()

        verify { viewModel.delete("s1") }
    }

    @Test
    fun tappingAddFab_invokesOnAdd() {
        var added = false
        setContent(mockViewModel(emptyList()), onAdd = { added = true })

        composeTestRule.onNodeWithContentDescription(string(R.string.signatures_add)).performClick()
        assertTrue(added)
    }
}
