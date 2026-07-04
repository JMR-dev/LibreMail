// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.local.AccountDatabase
import org.libremail.data.local.entity.AccountEntity
import org.libremail.data.local.entity.ServerConfigEmbedded
import org.libremail.data.settings.SignatureRepository
import org.libremail.ui.navigation.Routes
import org.libremail.ui.theme.LibreMailTheme

/**
 * End-to-end UI test for [SignatureEditScreen] + [SignatureEditViewModel] over a real in-memory Room
 * DB and [SignatureRepository]: the new-vs-edit title, saving a freshly entered name (round-tripping
 * through the repository, which makes the account's first signature its default), loading an existing
 * signature's name into the field, and saving an edit back to the same row.
 */
@RunWith(AndroidJUnit4::class)
class SignatureEditScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val accountId = "imap:a"

    private lateinit var db: AccountDatabase
    private lateinit var repository: SignatureRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AccountDatabase::class.java).build()
        repository = SignatureRepository(db.signatureDao())
        // Signatures foreign-key to an account row, so the parent account must exist first.
        runBlocking {
            db.accountDao().upsert(
                AccountEntity(
                    id = accountId,
                    email = "a@example.org",
                    displayName = "A",
                    authType = "PASSWORD_IMAP",
                    imap = ServerConfigEmbedded("imap.example.org", 993, "SSL_TLS"),
                    smtp = ServerConfigEmbedded("smtp.example.org", 465, "SSL_TLS"),
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    private fun setContent(signatureId: String? = null, onBack: () -> Unit = {}) {
        val args = buildMap {
            put(Routes.SIGNATURE_EDIT_ARG_ACCOUNT, accountId)
            if (signatureId != null) put(Routes.SIGNATURE_EDIT_ARG_ID, signatureId)
        }
        val viewModel = SignatureEditViewModel(SavedStateHandle(args), repository)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                SignatureEditScreen(onBack = onBack, viewModel = viewModel)
            }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun newSignature_showsNewTitle() {
        setContent(signatureId = null)

        composeTestRule.onNodeWithText(string(R.string.signature_new_title)).assertIsDisplayed()
    }

    @Test
    fun newSignature_savingEnteredName_persistsViaRepository() {
        var backInvoked = false
        setContent(signatureId = null, onBack = { backInvoked = true })

        composeTestRule.onNodeWithText(string(R.string.signature_name)).performTextInput("Work")
        composeTestRule.onNodeWithText(string(R.string.signature_save)).performClick()

        // The save round-trips to the DB: the first signature created becomes the account's default.
        composeTestRule.waitUntil(5_000) {
            runBlocking { repository.getDefault(accountId)?.name } == "Work" && backInvoked
        }
    }

    @Test
    fun existingSignature_showsEditTitle_andPrefillsName() {
        val id = runBlocking { repository.create(accountId, "Personal", "<p>hi</p>") }
        setContent(signatureId = id)
        waitForText("Personal")

        composeTestRule.onNodeWithText(string(R.string.signature_edit_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal").assertIsDisplayed()
    }

    @Test
    fun existingSignature_savingRenamedName_updatesTheSameRow() {
        val id = runBlocking { repository.create(accountId, "Personal", "<p>hi</p>") }
        setContent(signatureId = id)
        waitForText("Personal")

        composeTestRule.onNodeWithText("Personal").performTextReplacement("Renamed")
        composeTestRule.onNodeWithText(string(R.string.signature_save)).performClick()

        composeTestRule.waitUntil(5_000) {
            runBlocking { repository.get(id)?.name } == "Renamed"
        }
    }
}
