// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the [DatabaseKeyStore] dual-seal exchange (issue #100). The device-only Keystore that produces
 * the sealed blobs is mocked with a reversible cipher, and the persistence runs against an in-memory
 * [DataStore] injected through the [DatabaseKeyStore.dataStore] seam, so the security-critical
 * invariants — "never both seals at once" and "an auth-sealed passphrase is not recoverable without
 * authentication" — are exercised deterministically on the JVM instead of only on a device.
 *
 * [crypto] models the non-auth master seal as `m:<plain>`; [authCipher] models the auth-bound seal as
 * `a:<plain>`. Both are reversible so a resealed passphrase round-trips, which is exactly what keeps an
 * already-encrypted cache readable across an app-lock toggle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseKeyStoreTest {

    private val store = InMemoryPreferencesDataStore()
    private val crypto = mockk<KeystoreCrypto>(relaxed = true)
    private val authCipher = mockk<DatabaseKeyCipher>(relaxed = true)
    private val session = PassphraseSession()

    @Before
    fun setUp() {
        every { crypto.encrypt(any()) } answers { "m:" + firstArg<String>() }
        every { crypto.decrypt(any()) } answers { firstArg<String>().removePrefix("m:") }
        every { authCipher.encrypt(any()) } answers { "a:" + firstArg<String>() }
        every { authCipher.decrypt(any()) } answers { firstArg<String>().removePrefix("a:") }
    }

    private fun keyStore(): DatabaseKeyStore =
        DatabaseKeyStore(mockk(relaxed = true), crypto, authCipher, session).also { it.dataStore = store }

    @Test
    fun `passphrase mints a master-sealed key on first use and never alongside an auth seal`() = runTest {
        val keyStore = keyStore()
        assertEquals(SealState.NONE, keyStore.sealState())

        val passphrase = keyStore.passphrase()

        assertEquals(SealState.MASTER, keyStore.sealState())
        assertEquals(HEX_LEN, passphrase.length, "the SQLCipher passphrase is 32 bytes rendered as hex")
        // Exactly one seal exists: the master copy is present and no auth copy was written.
        assertNotNull(store.data.first()[SEALED_MASTER])
        assertNull(store.data.first()[SEALED_AUTH])
        // Idempotent: a second call returns the SAME passphrase rather than regenerating one (a second
        // key would strand the DB under a passphrase we could no longer reproduce).
        assertEquals(passphrase, keyStore.passphrase())
    }

    @Test
    fun `sealWithAuth replaces the master seal with an auth seal and unlocks the session`() = runTest {
        val keyStore = keyStore()
        val passphrase = keyStore.passphrase() // start master-sealed (app-lock off)

        keyStore.sealWithAuth()

        // Never both seals at once: enabling app-lock drops the master copy so the cache key is no
        // longer recoverable without authentication.
        assertEquals(SealState.AUTH, keyStore.sealState())
        assertNull(store.data.first()[SEALED_MASTER])
        assertEquals("a:$passphrase", store.data.first()[SEALED_AUTH])
        // The SAME passphrase is resealed (an already-encrypted cache stays readable) and unlocked into
        // the session so the DB opens this session.
        assertTrue(keyStore.hasAuthSealedPassphrase())
        assertEquals(passphrase, session.current())
    }

    @Test
    fun `sealWithAuth mints a fresh passphrase when neither a seal nor a session value exists`() = runTest {
        val keyStore = keyStore()
        assertEquals(SealState.NONE, keyStore.sealState())

        keyStore.sealWithAuth()

        assertEquals(SealState.AUTH, keyStore.sealState())
        assertNull(store.data.first()[SEALED_MASTER])
        val minted = session.current()
        assertNotNull(minted)
        assertEquals(HEX_LEN, minted.length)
        assertEquals("a:$minted", store.data.first()[SEALED_AUTH])
    }

    @Test
    fun `unlockWithAuth unwraps the auth-sealed passphrase into the session`() = runTest {
        val keyStore = keyStore()
        keyStore.sealWithAuth()
        val passphrase = requireNotNull(session.current())
        session.lock() // simulate a fresh session that must unwrap after the user authenticates

        keyStore.unlockWithAuth()

        assertEquals(passphrase, session.current())
    }

    @Test
    fun `unlockWithAuth is a no-op when nothing is auth-sealed`() = runTest {
        val keyStore = keyStore()

        keyStore.unlockWithAuth()

        assertNull(session.current())
        verify(exactly = 0) { authCipher.decrypt(any()) }
    }

    @Test
    fun `sealWithMaster replaces the auth seal with a master seal, deletes the auth key, and locks`() = runTest {
        val keyStore = keyStore()
        keyStore.sealWithAuth()
        val passphrase = requireNotNull(session.current())

        keyStore.sealWithMaster()

        // Never both seals at once: disabling app-lock drops the auth copy and reseals under the master.
        assertEquals(SealState.MASTER, keyStore.sealState())
        assertNull(store.data.first()[SEALED_AUTH])
        assertEquals("m:$passphrase", store.data.first()[SEALED_MASTER])
        // The now-orphaned auth-bound key is deleted so a later invalidation can't trigger a spurious
        // wipe, and the session is dropped so the cache opens without auth again.
        verify { authCipher.deleteKey() }
        assertNull(session.current())
        // Master-sealed value is recoverable WITHOUT authentication (that is the whole point of disable).
        assertEquals(passphrase, keyStore.passphrase())
    }

    @Test
    fun `sealWithMaster decrypts the auth seal when the session is already locked`() = runTest {
        val keyStore = keyStore()
        keyStore.sealWithAuth()
        val passphrase = requireNotNull(session.current())
        session.lock() // no session value: sealWithMaster must fall back to decrypting the auth seal

        keyStore.sealWithMaster()

        assertEquals(SealState.MASTER, keyStore.sealState())
        assertEquals("m:$passphrase", store.data.first()[SEALED_MASTER])
        assertNull(store.data.first()[SEALED_AUTH])
    }

    @Test
    fun `sealWithMaster does nothing when neither a session value nor an auth seal exists`() = runTest {
        val keyStore = keyStore()

        keyStore.sealWithMaster()

        assertEquals(SealState.NONE, keyStore.sealState())
        verify(exactly = 0) { authCipher.deleteKey() }
    }

    @Test
    fun `resetSealedPassphrase drops every seal, deletes the auth key, and locks the session`() = runTest {
        val keyStore = keyStore()
        keyStore.sealWithAuth() // auth-sealed + session unlocked

        keyStore.resetSealedPassphrase()

        assertEquals(SealState.NONE, keyStore.sealState())
        assertNull(store.data.first()[SEALED_AUTH])
        assertNull(store.data.first()[SEALED_MASTER])
        verify { authCipher.deleteKey() }
        assertNull(session.current())
    }

    @Test
    fun `passphrase refuses to mint a master key while an auth seal exists`() = runTest {
        val keyStore = keyStore()
        keyStore.sealWithAuth() // an auth seal now exists
        session.lock()

        // Minting a master passphrase now would strand the real (auth-sealed) key and leave the DB
        // encrypted under a passphrase we could never reproduce — so it fails loudly instead of quietly
        // creating a second, recoverable-without-auth copy.
        assertFailsWith<IllegalStateException> { keyStore.passphrase() }
        assertEquals(SealState.AUTH, keyStore.sealState())
        assertNull(store.data.first()[SEALED_MASTER])
    }

    @Test
    fun `resolvePassphrase returns the master-sealed value without authentication when app-lock is off`() = runTest {
        val keyStore = keyStore()
        val passphrase = keyStore.passphrase() // master-sealed

        assertEquals(passphrase, keyStore.resolvePassphrase(appLockEnabled = false))
    }

    @Test
    fun `resolvePassphrase returns the authenticated session value when an auth seal exists`() = runTest {
        val keyStore = keyStore()
        keyStore.sealWithAuth()
        val passphrase = requireNotNull(session.current())

        // AUTH seal: the value lives only in the session after the user authenticates — read it there,
        // never re-derive or regenerate it.
        assertEquals(passphrase, keyStore.resolvePassphrase(appLockEnabled = true))
    }

    @Test
    fun `clear-pending flag round-trips through set, query, and clear`() = runTest {
        val keyStore = keyStore()
        assertFalse(keyStore.isClearPending())

        keyStore.setClearPending()
        assertTrue(keyStore.isClearPending())
        assertEquals(true, store.data.first()[CLEAR_PENDING])

        keyStore.clearClearPending()
        assertFalse(keyStore.isClearPending())
        assertNull(store.data.first()[CLEAR_PENDING])
    }

    private companion object {
        // Same key names DatabaseKeyStore persists under, so the raw store can be inspected directly.
        val SEALED_MASTER = stringPreferencesKey("sealed_db_key")
        val SEALED_AUTH = stringPreferencesKey("sealed_db_key_auth")
        val CLEAR_PENDING = booleanPreferencesKey("clear_encrypted_cache_pending")
        const val HEX_LEN = 64 // 32 random bytes rendered as hex
    }
}

/**
 * A minimal in-memory [DataStore] of [Preferences] backed by a [MutableStateFlow], substituted for the
 * device-backed file store so the seal exchange is JVM-testable. `edit { }` routes through [updateData].
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val flow = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = flow.asStateFlow()

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(flow.value)
        flow.value = updated
        return updated
    }
}
