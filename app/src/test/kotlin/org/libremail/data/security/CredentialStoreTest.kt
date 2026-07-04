// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.entity.CredentialEntity
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [CredentialStore] wraps a single encrypted-secret row per account: it encrypts on the way in,
 * decrypts on the way out, and reports a missing row as `null`. The Keystore crypto is mocked so the
 * store's own read/modify/write and null-handling are what these pin — the AES-GCM round-trip itself
 * is [KeystoreCrypto]'s concern and is device-bound.
 */
class CredentialStoreTest {

    private val crypto = mockk<KeystoreCrypto>()
    private val dao = mockk<CredentialDao>()
    private val store = CredentialStore(crypto, dao)

    @Test
    fun `saveSecret encrypts the secret and upserts it under the account id`() = runTest {
        every { crypto.encrypt("token") } returns "cipher(token)"
        val saved = slot<CredentialEntity>()
        coEvery { dao.upsert(capture(saved)) } just Runs

        store.saveSecret("acct", "token")

        assertEquals("acct", saved.captured.accountId)
        assertEquals("cipher(token)", saved.captured.encryptedSecret)
    }

    @Test
    fun `loadSecret decrypts the stored ciphertext`() = runTest {
        coEvery { dao.getById("acct") } returns CredentialEntity("acct", "cipher(token)")
        every { crypto.decrypt("cipher(token)") } returns "token"

        assertEquals("token", store.loadSecret("acct"))
    }

    @Test
    fun `loadSecret returns null when the account has no stored secret`() = runTest {
        coEvery { dao.getById("acct") } returns null

        assertNull(store.loadSecret("acct"))
        // With no row there is nothing to decrypt.
        verify(exactly = 0) { crypto.decrypt(any()) }
    }

    @Test
    fun `delete removes the account's secret row`() = runTest {
        coEvery { dao.deleteById("acct") } just Runs

        store.delete("acct")

        coVerify { dao.deleteById("acct") }
    }
}
