// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.AccountDataMigrator
import org.libremail.data.local.DatabaseEncryption
import org.libremail.data.local.DatabaseFiles
import org.libremail.data.local.DatabaseProvisioner
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import java.io.File

/**
 * Pins the fail-closed contract's ONE resilience exception (issue #359): the plaintext account store is
 * never encrypted and never uses SQLCipher, so a cache-encryption native-load failure — which the
 * provisioner surfaces as `CacheEncryptionUnavailableException` — must NOT brick it. If it did, the app
 * couldn't read accounts to render the encryption error gate or assemble the PII-free problem report.
 *
 * Mirrors [DatabaseModuleInstrumentedTest]'s style: MockK collaborators, a real [ContextWrapper] (never
 * `mockk<Context>()`, which trips an ART parameter-annotation mismatch on API 31/32), and a real
 * [DatabaseProvisioner] whose encryption gate is forced to fail via a spied [DatabaseEncryption].
 */
@RunWith(AndroidJUnit4::class)
class AccountDatabaseModuleInstrumentedTest {

    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val cacheDbName = "accountmodule_cache_test.db"
    private val accountsDbName = "accountmodule_accounts_test.db"
    private val cacheFile: File get() = appContext.getDatabasePath(cacheDbName)
    private val accountsFile: File get() = appContext.getDatabasePath(accountsDbName)

    // 64 hex chars == a 32-byte SQLCipher passphrase.
    private val passphrase = "0123456789abcdef".repeat(4)

    private val keyStore = mockk<DatabaseKeyStore>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val migrator = mockk<AccountDataMigrator>()

    // Route the provisioner's cache lookup and Room's account-store lookup to this test's private files,
    // never the app's real databases.
    private val context: Context = object : ContextWrapper(appContext) {
        override fun getDatabasePath(name: String): File = when (name) {
            DatabaseFiles.NAME -> cacheFile
            DatabaseFiles.ACCOUNTS_NAME -> accountsFile
            else -> super.getDatabasePath(name)
        }
    }

    @Before
    fun setUp() {
        clean()
        coEvery { keyStore.isClearPending() } returns false
        coEvery { keyStore.resolvePassphrase(any()) } returns passphrase
        coEvery { migrator.migrateIfNeeded() } just Runs
    }

    @After
    fun tearDown() {
        unmockkAll()
        clean()
    }

    private fun clean() {
        listOf(cacheDbName, accountsDbName).forEach { name ->
            appContext.deleteDatabase(name)
            appContext.getDatabasePath(name).parentFile?.listFiles { f -> f.name.startsWith(name) }
                ?.forEach { it.delete() }
        }
    }

    private fun provisioner() = DatabaseProvisioner(context, keyStore, settingsRepository, migrator, Dispatchers.IO)

    @Test
    fun accountStoreStillOpensWhenTheCacheEncryptionLibraryFailsToLoad() = runBlocking<Unit> {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        mockkObject(DatabaseEncryption) // spy: real impls run except the forced failure below
        // Fault injection: the encrypted-cache gate can't load SQLCipher, so prepareCache() fails closed
        // with CacheEncryptionUnavailableException — the exact condition provideAccountDatabase tolerates.
        every { DatabaseEncryption.ensureNativeLibraryLoaded() } throws
            UnsatisfiedLinkError("dlopen failed: libsqlcipher.so is not loadable")

        val database = AccountDatabaseModule.provideAccountDatabase(context, provisioner())
        try {
            // The plaintext account store opens and a query succeeds despite the cache-encryption failure.
            assertEquals(emptyList<Any>(), database.accountDao().getAll())
        } finally {
            database.close()
        }
    }
}
