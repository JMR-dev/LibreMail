// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.ui.theme.LibreMailTheme

/**
 * UI tests for the onboarding contacts-access step (#127, #128). They drive the presentational
 * [ContactsAccessContent] with explicit signals so the three paths — skip, grant (the "done" state),
 * and request (with the re-ask rationale) — run deterministically without a live system permission
 * dialog (whose grant state would otherwise leak across the shared instrumentation process).
 */
@RunWith(AndroidJUnit4::class)
class ContactsAccessStepTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(
        granted: Boolean,
        showRationale: Boolean = false,
        onAllow: () -> Unit = {},
        onSkip: () -> Unit = {},
        onContinue: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ContactsAccessContent(
                    granted = granted,
                    showRationale = showRationale,
                    onAllow = onAllow,
                    onSkip = onSkip,
                    onContinue = onContinue,
                )
            }
        }
    }

    @Test
    fun notGranted_notNow_skipsTheStep() {
        var skipped = false
        var allowed = false
        setContent(granted = false, onAllow = { allowed = true }, onSkip = { skipped = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_not_now)).performClick()

        assertTrue("Not now must invoke the skip callback", skipped)
        assertFalse("Skipping must not request the permission", allowed)
    }

    @Test
    fun notGranted_allow_triggersTheRequest() {
        var allowed = false
        setContent(granted = false, onAllow = { allowed = true })

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_allow)).performClick()

        assertTrue("Allow must trigger the permission request", allowed)
    }

    @Test
    fun granted_showsDoneState_andContinues() {
        var continued = false
        setContent(granted = true, onContinue = { continued = true })

        // The "done" copy is shown and the request/skip buttons are gone.
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_done_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_allow)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_not_now)).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_continue)).performClick()
        assertTrue("Continue must invoke the continue callback", continued)
    }

    @Test
    fun reRequest_showsRationale() {
        setContent(granted = false, showRationale = true)

        // A re-request explains itself (shouldShowRequestPermissionRationale handling, #128).
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_rationale)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onboarding_contacts_allow)).assertIsDisplayed()
    }
}
