// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.lifecycle.SavedStateHandle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.contacts.ContactSuggestion
import org.libremail.contacts.ContactsRepository
import org.libremail.data.settings.AccountSettingsRepository
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import org.libremail.data.settings.SignatureRepository
import org.libremail.domain.model.Account
import org.libremail.domain.model.AccountSettings
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.MailSecurity
import org.libremail.domain.model.OutgoingAttachment
import org.libremail.domain.model.ServerConfig
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository
import org.libremail.ui.navigation.Routes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Complements [ComposeViewModelTest] with the surface it leaves uncovered: recipient autocomplete,
 * inline-image tracking/pruning, the trySend guard branches, a send failure, and flushDraft.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelExtraTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val alice = Account(
        id = "imap:a",
        email = "alice@example.org",
        displayName = "Alice",
        authType = AuthType.PASSWORD_IMAP,
        imap = ServerConfig("imap.example.org", 993, MailSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.example.org", 465, MailSecurity.SSL_TLS),
    )

    private fun viewModel(
        accounts: List<Account> = listOf(alice),
        savedState: SavedStateHandle = SavedStateHandle(),
        mailRepository: MailRepository = mockk(relaxed = true),
        contactsRepository: ContactsRepository = mockk(relaxed = true),
    ): ComposeViewModel {
        val accountRepository = mockk<AccountRepository>()
        every { accountRepository.observeAccounts() } returns MutableStateFlow(accounts)
        val accountSettingsRepository = mockk<AccountSettingsRepository>()
        coEvery { accountSettingsRepository.get(any()) } answers { AccountSettings(firstArg()) }
        val signatureRepository = mockk<SignatureRepository>(relaxed = true)
        coEvery { signatureRepository.getDefault(any()) } returns null
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.settings } returns flowOf(AppSettings())
        coEvery { settingsRepository.setLastFont(any(), any()) } just Runs
        return ComposeViewModel(
            savedStateHandle = savedState,
            mailRepository = mailRepository,
            accountRepository = accountRepository,
            contactsRepository = contactsRepository,
            accountSettingsRepository = accountSettingsRepository,
            signatureRepository = signatureRepository,
            settingsRepository = settingsRepository,
        )
    }

    // --- recipient autocomplete ------------------------------------------------------------------

    @Test
    fun `typing a recipient with contacts allowed surfaces suggestions`() = runTest(dispatcher) {
        val contacts = mockk<ContactsRepository>()
        coEvery { contacts.search("bo") } returns listOf(ContactSuggestion("Bob", "bob@example.org"))
        val vm = viewModel(contactsRepository = contacts)
        advanceUntilIdle()

        vm.onContactsPermission(granted = true)
        vm.onToChange("bo")
        advanceUntilIdle()

        assertEquals(listOf(ContactSuggestion("Bob", "bob@example.org")), vm.state.value.suggestions)
    }

    @Test
    fun `a too-short token clears any standing suggestions and never searches`() = runTest(dispatcher) {
        val contacts = mockk<ContactsRepository>(relaxed = true)
        val vm = viewModel(contactsRepository = contacts)
        advanceUntilIdle()
        vm.onContactsPermission(granted = true)

        vm.onToChange("b")
        advanceUntilIdle()

        assertTrue(vm.state.value.suggestions.isEmpty())
        coVerify(exactly = 0) { contacts.search(any()) }
    }

    @Test
    fun `no suggestions are searched while contacts permission is withheld`() = runTest(dispatcher) {
        val contacts = mockk<ContactsRepository>(relaxed = true)
        val vm = viewModel(contactsRepository = contacts)
        advanceUntilIdle()

        vm.onToChange("bob")
        advanceUntilIdle()

        coVerify(exactly = 0) { contacts.search(any()) }
    }

    @Test
    fun `picking a suggestion replaces the trailing token and appends after earlier recipients`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.pickSuggestion(ContactSuggestion("Bob", "bob@example.org"))
            assertEquals("bob@example.org", vm.state.value.to)

            vm.onToChange("bob@example.org, ca")
            vm.pickSuggestion(ContactSuggestion("Carol", "carol@example.org"))
            assertEquals("bob@example.org, carol@example.org", vm.state.value.to)
            assertTrue(vm.state.value.suggestions.isEmpty())
        }

    // --- inline images ---------------------------------------------------------------------------

    @Test
    fun `a picked inline image is tracked and handed to the editor, then kept while pending`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onImagePicked("content://img/1", "pic.png")
        val inline = vm.state.value.attachments.single { it.isInline }
        assertEquals(inline.contentId, vm.state.value.pendingInlineImage?.contentId)

        // A body edit that doesn't yet reference the cid keeps the image while it's still pending.
        vm.onBodyChange("typing", null)
        assertTrue(vm.state.value.attachments.any { it.contentId == inline.contentId })
    }

    @Test
    fun `an inline image is pruned once inserted and no longer referenced by the body`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onImagePicked("content://img/1", "pic.png")
        val cid = vm.state.value.attachments.single { it.isInline }.contentId

        // The editor confirms insertion; a later body without the cid drops the image.
        vm.onInlineImageInserted()
        assertNull(vm.state.value.pendingInlineImage)
        vm.onBodyChange("no image here", null)

        assertTrue(vm.state.value.attachments.none { it.contentId == cid })
    }

    @Test
    fun `an inline image is kept while the body still references its cid`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onImagePicked("content://img/1", "pic.png")
        val cid = vm.state.value.attachments.single { it.isInline }.contentId
        vm.onInlineImageInserted()

        vm.onBodyChange("look", "<p><img src=\"cid:$cid\"></p>")

        assertTrue(vm.state.value.attachments.any { it.contentId == cid })
    }

    // --- attachment/error helpers ----------------------------------------------------------------

    @Test
    fun `removeAttachment drops the matching uri and consumeError clears the error`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.addAttachments(
            listOf(
                OutgoingAttachment("content://a", "a.pdf"),
                OutgoingAttachment("content://b", "b.pdf"),
            ),
        )

        vm.removeAttachment("content://a")
        assertEquals(listOf("content://b"), vm.state.value.attachments.map { it.uri })

        vm.onCcChange("cc@example.org")
        vm.onBccChange("bcc@example.org")
        assertEquals("cc@example.org", vm.state.value.cc)
        assertEquals("bcc@example.org", vm.state.value.bcc)

        vm.send() // no recipient -> sets an error
        assertNotEquals(null, vm.state.value.error)
        vm.consumeError()
        assertNull(vm.state.value.error)
    }

    // --- send guards & failure -------------------------------------------------------------------

    @Test
    fun `send refuses without an account`() = runTest(dispatcher) {
        // Draft route so init doesn't wait forever for a (never-arriving) first account.
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.getDraft("d1") } returns null
        val vm = viewModel(
            accounts = emptyList(),
            savedState = SavedStateHandle(mapOf(Routes.COMPOSE_ARG_DRAFT to "d1")),
            mailRepository = mailRepository,
        )
        advanceUntilIdle()

        vm.onToChange("bob@example.org")
        vm.send()

        assertEquals("Add an account first", vm.state.value.error)
        coVerify(exactly = 0) { mailRepository.sendMessage(any()) }
    }

    @Test
    fun `send refuses without a recipient`() = runTest(dispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        val vm = viewModel(mailRepository = mailRepository)
        advanceUntilIdle()

        vm.send()

        assertEquals("Add a recipient", vm.state.value.error)
        coVerify(exactly = 0) { mailRepository.sendMessage(any()) }
    }

    @Test
    fun `a send failure surfaces the error and clears the sending flag`() = runTest(dispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.sendMessage(any()) } returns Result.failure(RuntimeException("SMTP said no"))
        val vm = viewModel(mailRepository = mailRepository)
        advanceUntilIdle()

        vm.onToChange("bob@example.org")
        vm.onBodyChange("Hello", null)
        vm.send()
        advanceUntilIdle()

        assertEquals("SMTP said no", vm.state.value.error)
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `flushDraft persists the in-progress draft immediately`() = runTest(dispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        val vm = viewModel(mailRepository = mailRepository)
        advanceUntilIdle()

        vm.onToChange("bob@example.org")
        vm.flushDraft()
        advanceUntilIdle()

        coVerify(atLeast = 1) { mailRepository.saveDraft(any()) }
    }

    @Test
    fun `emptying a resumed draft deletes it on exit`() = runTest(dispatcher) {
        val mailRepository = mockk<MailRepository>(relaxed = true)
        coEvery { mailRepository.getDraft("d1") } returns org.libremail.domain.model.Draft(
            id = "d1",
            accountId = "imap:a",
            to = "x@example.org",
            cc = "",
            subject = "Hi",
            body = "Body",
            updatedAt = 0L,
        )
        val vm = viewModel(
            savedState = SavedStateHandle(mapOf(Routes.COMPOSE_ARG_DRAFT to "d1")),
            mailRepository = mailRepository,
        )
        advanceUntilIdle()

        // Blank every field, then leave: the persisted row is removed rather than left as an empty orphan.
        vm.onToChange("")
        vm.onSubjectChange("")
        vm.onBodyChange("", null)
        vm.onExit()
        advanceUntilIdle()

        coVerify { mailRepository.deleteDraft("d1") }
    }

    @Test
    fun `ComposeUiState and PendingInlineImage carry value semantics`() {
        val state = ComposeUiState(
            to = "a@b.org",
            subject = "s",
            body = "b",
            bodyHtml = "<p>b</p>",
            fromAccountId = "imap:a",
            sending = true,
            error = "e",
            showAttachmentPrompt = true,
        )
        assertEquals(state, state.copy())
        assertEquals(state.hashCode(), state.copy().hashCode())
        assertTrue(state.toString().contains("a@b.org"))
        assertNotEquals(state, state.copy(sending = false))
        assertNotEquals(state, state.copy(error = null))

        val pending = PendingInlineImage("cid-1", "pic.png")
        val (contentId, name) = pending
        assertEquals("cid-1", contentId)
        assertEquals("pic.png", name)
        assertEquals(pending, pending.copy())
        assertEquals(pending.hashCode(), pending.copy().hashCode())
        assertTrue(pending.toString().contains("cid-1"))
        assertNotEquals(pending, pending.copy(name = "other.png"))
        assertNotEquals(pending, pending.copy(contentId = "cid-2"))
    }
}
