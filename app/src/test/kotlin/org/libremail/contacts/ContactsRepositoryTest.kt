// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.contacts

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Email
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ContactsRepository] resolves recipient-autocomplete suggestions from `ContactsContract`. The
 * `ContentResolver`/`Cursor` are mocked (the real provider needs a device), which is enough to lock in
 * the query-shape, the too-short-query short-circuit, de-duplication, the name fallback, the result
 * cap, and the fail-soft behaviour when the provider throws.
 */
class ContactsRepositoryTest {

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context> { every { contentResolver } returns resolver }
    private val repository = ContactsRepository(context)

    private fun cursorOf(addresses: List<String?>, names: List<String?>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndexOrThrow(Email.ADDRESS) } returns 0
        every { cursor.getColumnIndexOrThrow(Email.DISPLAY_NAME_PRIMARY) } returns 1
        // Advance exactly once per seeded row, then stop — never an unbounded "always true" cursor.
        every { cursor.moveToNext() } returnsMany (List(addresses.size) { true } + false)
        every { cursor.getString(0) } returnsMany addresses
        every { cursor.getString(1) } returnsMany names
        return cursor
    }

    private fun stubQuery(cursor: Cursor?) {
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
    }

    @Test
    fun `a query under two characters short-circuits without touching the provider`() = runTest {
        assertEquals(emptyList(), repository.search("a"))
        verify(exactly = 0) { resolver.query(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `maps matching rows, de-duplicates by email, and falls back to the email as the name`() = runTest {
        // Row 2 duplicates row 1 (case-insensitively); row 3 has a blank address; row 4 has no name.
        // getString(name) is only read for the rows that pass the address checks (rows 1 and 4).
        stubQuery(
            cursorOf(
                addresses = listOf("alice@example.org", "ALICE@example.org", "   ", "bob@example.org"),
                names = listOf("Alice", null),
            ),
        )

        val results = repository.search("al")

        assertEquals(
            listOf(
                ContactSuggestion("Alice", "alice@example.org"),
                ContactSuggestion("bob@example.org", "bob@example.org"),
            ),
            results,
        )
    }

    @Test
    fun `caps the number of suggestions returned`() = runTest {
        val many = (1..20).map { "user$it@example.org" }
        stubQuery(cursorOf(addresses = many, names = List(20) { "User $it" }))

        assertEquals(8, repository.search("user").size)
    }

    @Test
    fun `a null cursor yields no suggestions`() = runTest {
        stubQuery(null)

        assertEquals(emptyList(), repository.search("query"))
    }

    @Test
    fun `a provider failure fails soft to an empty list`() = runTest {
        every { resolver.query(any(), any(), any(), any(), any()) } throws SecurityException("no permission")

        assertEquals(emptyList(), repository.search("query"))
    }

    @Test
    fun `ContactSuggestion value semantics`() {
        val suggestion = ContactSuggestion("Alice", "alice@example.org")

        val (name, email) = suggestion
        assertEquals("Alice", name)
        assertEquals("alice@example.org", email)
        assertEquals(suggestion, suggestion.copy())
        assertEquals(suggestion.hashCode(), suggestion.copy().hashCode())
        assertTrue(suggestion.toString().contains("Alice"))
        assertNotEquals(suggestion, suggestion.copy(name = "Bob"))
        assertNotEquals(suggestion, suggestion.copy(email = "bob@example.org"))
    }
}
