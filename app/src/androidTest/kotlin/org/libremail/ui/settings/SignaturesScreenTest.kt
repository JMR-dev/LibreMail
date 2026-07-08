// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
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
 * End-to-end UI test for [SignaturesScreen] + [SignaturesViewModel] over a real in-memory Room DB
 * and [SignatureRepository]: the empty state, name + default-badge rendering, making another
 * signature the default (round-tripping through the DB via the radio), and deleting a signature
 * (its row disappears as the repository re-emits the account's list).
 */
@RunWith(AndroidJUnit4::class)
class SignaturesScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val accountId = "imap:a"

    private lateinit var db: AccountDatabase
    private lateinit var repository: SignatureRepository

    // Holds the real SignaturesViewModel built by hand in setContent() below, so tearDown() can
    // clear() it (triggering ViewModel.onCleared()) before closing the DB.
    private val viewModelStore = ViewModelStore()

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
    fun tearDown() {
        // Clear the store (→ ViewModel.onCleared() → cancels viewModelScope) BEFORE closing the DB.
        // SignaturesViewModel.signatures is a Room InvalidationTracker Flow kept alive by
        // stateIn(WhileSubscribed(5_000)): without this, the collector can still be live up to 5s
        // after the UI detaches, so a re-query lands on the just-closed in-memory DB and throws
        // SQLITE_MISUSE ("connection is closed") — an intermittent teardown race, not a real bug.
        viewModelStore.clear()
        db.close()
    }

    private fun string(resId: Int) = composeTestRule.activity.getString(resId)

    /** Seeds signatures (the first becomes the account's default) then renders the screen. */
    private fun setContent(vararg names: String) {
        runBlocking { names.forEach { repository.create(accountId, it, "<p>$it body</p>") } }
        val viewModel = SignaturesViewModel(
            SavedStateHandle(mapOf(Routes.SIGNATURES_ARG_ACCOUNT to accountId)),
            repository,
        )
        viewModelStore.put("signatures", viewModel)
        composeTestRule.setContent {
            LibreMailTheme(darkTheme = false, dynamicColor = false) {
                SignaturesScreen(onBack = {}, onEdit = {}, onAdd = {}, viewModel = viewModel)
            }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun noSignatures_showsEmptyState() {
        setContent()

        composeTestRule.onNodeWithText(string(R.string.signatures_empty)).assertIsDisplayed()
    }

    @Test
    fun signatures_renderNames_withExactlyOneDefaultBadge() {
        setContent("Work", "Personal")
        waitForText("Work")

        composeTestRule.onNodeWithText("Work").assertIsDisplayed()
        composeTestRule.onNodeWithText("Personal").assertIsDisplayed()
        // The first signature created is the account's sole default, so exactly one badge shows.
        composeTestRule.onAllNodesWithText(string(R.string.signature_default_badge)).assertCountEquals(1)
    }

    @Test
    fun tappingRadioOnNonDefault_makesItTheDefault() {
        setContent("Work", "Personal")
        waitForText("Work")

        // "Work" (created first) is the default, so its radio is selected and "Personal"'s is not.
        composeTestRule.onAllNodes(isSelectable() and isSelected()).assertCountEquals(1)
        composeTestRule.onNode(isSelectable() and isNotSelected()).performClick()

        // The tap round-trips through the repository/DB: "Personal" is now the account's default.
        composeTestRule.waitUntil(5_000) {
            runBlocking { repository.getDefault(accountId)?.name } == "Personal"
        }
    }

    @Test
    fun tappingDelete_removesTheRow() {
        setContent("Work", "Personal")
        waitForText("Work")

        // Delete the first (default) signature; the repository re-emits, dropping its row.
        composeTestRule.onAllNodesWithContentDescription(string(R.string.signature_delete))[0].performClick()

        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithText("Work").fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText("Personal").assertIsDisplayed()
    }
}
