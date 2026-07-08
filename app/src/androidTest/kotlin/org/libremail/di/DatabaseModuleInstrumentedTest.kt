// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.AccountDataMigrator
import org.libremail.data.local.DatabaseEncryption
import org.libremail.data.local.DatabaseFiles
import org.libremail.data.local.DatabaseProvisioner
import org.libremail.data.local.LibreMailDatabase
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import java.io.File

/**
 * Pins the "native lib loaded before keyed open" invariant (592a797) at the OPEN site — issue #220's
 * follow-up to #208/#210. `DatabaseProvisionerTest` (JVM, mocked) and `DatabaseProvisionerInstrumentedTest`
 * (instrumented, real SQLCipher) both pin that [DatabaseProvisioner.prepareCache] calls
 * `DatabaseEncryption.ensureNativeLibraryLoaded()` for the encrypted branch, but neither exercises
 * [DatabaseModule.provideDatabase] itself: the instrumented one opens through a hand-rolled
 * `SupportOpenHelperFactory`, bypassing the branch that actually picks between it and
 * `FrameworkSQLiteOpenHelperFactory` based on what [DatabaseProvisioner] reports. A regression that
 * breaks THAT wiring — swaps the branches, or stops gating the open on
 * [DatabaseProvisioner.prepareCache] at all — would slip through both existing guards.
 *
 * These tests call [DatabaseModule.provideDatabase] directly (a plain function on the `object`, no Hilt
 * graph needed) and drive the first real open through its own
 * [org.libremail.data.local.DeferredOpenHelperFactory] lambda, mirroring the lane-3 instrumented style:
 * MockK-mocked collaborators, a real [ContextWrapper] (never `mockk<Context>()`, which trips an ART
 * parameter-annotation mismatch on API 31/32), and real SQLCipher for the encrypted path.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseModuleInstrumentedTest {

    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "database_module_instrumented_test.db"
    private val dbFile: File get() = appContext.getDatabasePath(dbName)

    // 64 hex chars == a 32-byte SQLCipher passphrase, matching DatabaseKeyStore's format.
    private val passphrase = "0123456789abcdef".repeat(4)

    private val keyStore = mockk<DatabaseKeyStore>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val migrator = mockk<AccountDataMigrator>()

    // provideDatabase hardcodes DatabaseFiles.NAME as the Room db name, exactly like DatabaseProvisioner
    // does internally when it resolves the cache file — so redirecting getDatabasePath for that one name
    // routes BOTH the provisioner's file checks and Room's own open through this test's private file,
    // never the app's real cache.
    private val context: Context = object : ContextWrapper(appContext) {
        override fun getDatabasePath(name: String): File =
            if (name == DatabaseFiles.NAME) dbFile else super.getDatabasePath(name)
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
        appContext.deleteDatabase(dbName)
        dbFile.parentFile?.listFiles { f -> f.name.startsWith(dbName) }?.forEach { it.delete() }
    }

    private fun provisioner() = DatabaseProvisioner(context, keyStore, settingsRepository, migrator, Dispatchers.IO)

    /**
     * Builds a genuinely plaintext cache with one row, then converts it to real SQLCipher ciphertext —
     * the steady-state shape an already-encrypted install has at the next cold start, where
     * `ensureEncrypted` has nothing left to convert (592a797's exact failure mode: the load can't ride
     * on a conversion that no-ops).
     */
    private fun seedEncryptedCacheWithOneRow() {
        Room.databaseBuilder(appContext, LibreMailDatabase::class.java, dbName).build().apply {
            runBlocking { messageDao().insertNew(listOf(message("acct:1"))) }
            close()
        }
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
    }

    private fun message(id: String) = MessageEntity(
        id = id,
        accountId = "acct",
        sender = "Ada",
        senderEmail = "ada@example.org",
        subject = "Hi",
        snippet = "",
        body = "",
        timestampMillis = 1_000L,
        isRead = false,
        isStarred = false,
    )

    @Test
    fun encryptedBranchLoadsNativeLibraryBeforeTheKeyedOpenSucceeds() = runBlocking<Unit> {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        seedEncryptedCacheWithOneRow()
        assertTrue("precondition: the cache is already encrypted", DatabaseEncryption.isEncrypted(dbFile))

        mockkObject(DatabaseEncryption) // spy: real implementations still run
        val database = DatabaseModule.provideDatabase(context, provisioner())
        try {
            // The first real open, driven by provideDatabase's own DeferredOpenHelperFactory lambda and
            // CacheOpenMode branch — NOT a hand-rolled SupportOpenHelperFactory bypass. If that branch
            // ever opened this genuinely-encrypted file with the plaintext framework helper instead of
            // SQLCipher's, this would throw (a plaintext driver can't parse SQLCipher ciphertext) rather
            // than return the seeded row.
            assertEquals("acct:1", database.messageDao().getById("acct:1")?.id)
        } finally {
            database.close()
        }

        // Regression guard (592a797), pinned at the open site: a steady-state encrypted start converts
        // nothing in ensureEncrypted, so provideDatabase's encrypted branch must itself have loaded
        // SQLCipher's native library before the keyed open above could succeed.
        verify(exactly = 1) { DatabaseEncryption.ensureNativeLibraryLoaded() }
    }

    @Test
    fun plaintextBranchNeverTouchesTheNativeLibrary() = runBlocking<Unit> {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = false, appLock = false))

        mockkObject(DatabaseEncryption)
        val database = DatabaseModule.provideDatabase(context, provisioner())
        try {
            database.messageDao().insertNew(listOf(message("acct:1")))
            assertEquals("acct:1", database.messageDao().getById("acct:1")?.id)
        } finally {
            database.close()
        }

        // The counterpart to the guard above: the plaintext branch must never construct or load
        // SQLCipher, so a regression that swapped the branch mapping would show up here too.
        verify(exactly = 0) { DatabaseEncryption.ensureNativeLibraryLoaded() }
    }

    @Test
    fun encryptedOpenNeverSucceedsIfTheNativeLibraryLoadFails() {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        seedEncryptedCacheWithOneRow()

        mockkObject(DatabaseEncryption)
        // Fault injection: if provideDatabase's encrypted branch ever stopped gating the keyed open on
        // this load call, the open below would still succeed despite the injected failure. Failing here
        // instead pins that the open is causally downstream of the load, not just usually preceded by it
        // — the "wired without a preceding load" regression issue #220 calls out.
        every { DatabaseEncryption.ensureNativeLibraryLoaded() } throws IllegalStateException("boom")

        val database = DatabaseModule.provideDatabase(context, provisioner())
        try {
            assertThrows(Throwable::class.java) {
                runBlocking { database.messageDao().getById("acct:1") }
            }
        } finally {
            runCatching { database.close() }
        }
    }
}
