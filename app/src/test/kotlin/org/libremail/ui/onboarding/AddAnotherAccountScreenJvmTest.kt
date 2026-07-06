// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
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
 * Robolectric feasibility PoC (issue #373): proves Jetpack Compose UI can be unit-tested on the JVM
 * without an emulator. `createComposeRule()` drives [AddAnotherAccountScreen] under
 * [RobolectricTestRunner]; `stringResource(...)` and Material3 theming resolve because
 * `testOptions.unitTests.isIncludeAndroidResources` is enabled in `app/build.gradle.kts`. Runs under
 * `:app:testDebugUnitTest`, NOT `androidTest` — the instrumented `AddAnotherAccountScreenTest` stays.
 *
 * `@Config(sdk = [36])` because compileSdk/targetSdk 37 (preview) has no Robolectric 4.16 sandbox
 * (also the default in `src/test/resources/robolectric.properties`); `@GraphicsMode(NATIVE)` is
 * required for Compose to render under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AddAnotherAccountScreenJvmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun string(resId: Int): String = context.getString(resId)

    private fun setContent(onAddAnother: () -> Unit = {}, onFinish: () -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                AddAnotherAccountScreen(onAddAnother = onAddAnother, onFinish = onFinish)
            }
        }
    }

    @Test
    fun rendersAccountAddedTitleFromResources() {
        setContent()

        // Proves the merged Android resource table loaded on the JVM (isIncludeAndroidResources).
        composeTestRule.onNodeWithText(string(R.string.onboarding_account_added_title)).assertIsDisplayed()
    }

    @Test
    fun tappingYes_invokesOnAddAnother() {
        var addAnother = false
        setContent(onAddAnother = { addAnother = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_yes)).performClick()

        assertTrue(addAnother)
    }

    @Test
    fun tappingNo_invokesOnFinish() {
        var finished = false
        setContent(onFinish = { finished = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_add_another_no)).performClick()

        assertTrue(finished)
    }
}
