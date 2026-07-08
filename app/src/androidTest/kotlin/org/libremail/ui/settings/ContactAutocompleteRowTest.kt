// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.contacts.ContactPermissionState
import org.libremail.ui.theme.LibreMailTheme

/**
 * UI tests for the Settings contacts-autocomplete row (#129). The row is presentational, so each of
 * its three states — on / off / blocked-in-settings — is driven directly and asserted deterministically,
 * independent of the process's real `READ_CONTACTS` grant.
 */
@RunWith(AndroidJUnit4::class)
class ContactAutocompleteRowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(state: ContactPermissionState, onClick: () -> Unit = {}) {
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                ContactAutocompleteRow(state = state, onClick = onClick)
            }
        }
    }

    @Test
    fun granted_showsOnSubtitle_andIsClickable() {
        var clicked = false
        setContent(ContactPermissionState.GRANTED) { clicked = true }

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_on)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_on)).performClick()
        assertTrue("Tapping the row must invoke onClick", clicked)
    }

    @Test
    fun denied_showsOffSubtitle() {
        setContent(ContactPermissionState.DENIED)
        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_off)).assertIsDisplayed()
    }

    @Test
    fun blocked_showsBlockedSubtitle() {
        setContent(ContactPermissionState.BLOCKED)
        composeTestRule.onNodeWithText(string(R.string.settings_contacts_autocomplete_blocked)).assertIsDisplayed()
    }
}
