// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.contacts

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Email
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A device contact match for recipient autocomplete. */
data class ContactSuggestion(val name: String, val email: String)

/** Looks up device contacts (ContactsContract) for recipient autocomplete. */
@Singleton
class ContactsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    /** Returns up to [LIMIT] contacts whose name or email matches [query]. Empty if no permission. */
    suspend fun search(query: String): List<ContactSuggestion> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        val projection = arrayOf(Email.ADDRESS, Email.DISPLAY_NAME_PRIMARY)
        val selection = "${Email.ADDRESS} LIKE ? OR ${Email.DISPLAY_NAME_PRIMARY} LIKE ?"
        val pattern = "%$query%"

        val results = mutableListOf<ContactSuggestion>()
        runCatching {
            context.contentResolver.query(
                Email.CONTENT_URI,
                projection,
                selection,
                arrayOf(pattern, pattern),
                "${Email.DISPLAY_NAME_PRIMARY} ASC",
            )?.use { cursor ->
                val addressIndex = cursor.getColumnIndexOrThrow(Email.ADDRESS)
                val nameIndex = cursor.getColumnIndexOrThrow(Email.DISPLAY_NAME_PRIMARY)
                val seen = HashSet<String>()
                while (cursor.moveToNext() && results.size < LIMIT) {
                    val email = cursor.getString(addressIndex)?.trim().orEmpty()
                    if (email.isEmpty() || !seen.add(email.lowercase())) continue
                    val name = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: email
                    results.add(ContactSuggestion(name, email))
                }
            }
        }
        results
    }

    private companion object {
        const val LIMIT = 8
    }
}
