// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

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
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.entity.MessageEntity
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.AppSettings
import org.libremail.data.settings.SettingsRepository
import java.io.File

/**
 * On-device behavior of [DatabaseProvisioner.prepareCache] against REAL SQLCipher (the JVM
 * `DatabaseProvisionerTest` mocks [DatabaseEncryption], so it can't exercise a real keyed open). The
 * security/settings collaborators are faked; [DatabaseEncryption] is a spy so its real conversions run
 * while the native-lib load can still be verified.
 *
 * The headline is the steady-state regression guard for the crash fixed in 592a797: with the cache
 * already encrypted (nothing to convert) `ensureEncrypted` no-ops, so the provisioner itself must load
 * SQLCipher's native library before the keyed open — otherwise Room's `nativeOpen` throws
 * `UnsatisfiedLinkError` on every cold start. The `verify(exactly = 1) { ensureNativeLibraryLoaded() }`
 * fails if that explicit load is ever removed.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseProvisionerInstrumentedTest {

    private val appContext = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "provisioner_instrumented_test.db"
    private val dbFile: File get() = appContext.getDatabasePath(dbName)

    // 64 hex chars == a 32-byte SQLCipher passphrase.
    private val passphrase = "0123456789abcdef".repeat(4)

    private val keyStore = mockk<DatabaseKeyStore>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val migrator = mockk<AccountDataMigrator>()

    // A real ContextWrapper, NOT a mockk<Context>: mocking android.content.Context makes MockK walk the
    // whole framework class with kotlin-reflect (isKotlinInline), which trips an ART parameter-annotation
    // length mismatch and throws ArrayIndexOutOfBoundsException on API 31/32 (it passes on API 29). The
    // wrapper routes the provisioner's cache lookup to the test DB and delegates everything else.
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

    private fun seedPlaintextRow() {
        Room.databaseBuilder(appContext, LibreMailDatabase::class.java, dbName).build().apply {
            runBlocking { messageDao().insertNew(listOf(message("acct:1"))) }
            close()
        }
    }

    private fun openEncrypted(): LibreMailDatabase =
        Room.databaseBuilder(appContext, LibreMailDatabase::class.java, dbName)
            .openHelperFactory(SupportOpenHelperFactory(passphrase.toByteArray(Charsets.US_ASCII), null, false))
            .build()

    private fun openPlaintext(): LibreMailDatabase =
        Room.databaseBuilder(appContext, LibreMailDatabase::class.java, dbName).build()

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
    fun steadyStateEncryptedStartLoadsTheNativeLibAndOpensKeyedWithoutCrashing() = runBlocking<Unit> {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = true, appLock = false))
        // Build a genuinely-encrypted, steady-state cache. Encrypt BEFORE spying so the conversion is a
        // real one; by the time prepareCache runs, ensureEncrypted has nothing left to do.
        seedPlaintextRow()
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
        assertTrue("precondition: the cache is already encrypted", DatabaseEncryption.isEncrypted(dbFile))

        mockkObject(DatabaseEncryption) // spy: real implementations still run
        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Encrypted(passphrase), mode)
        // Regression guard (592a797): a steady-state encrypted start converts nothing, so the provisioner
        // MUST load the native library itself before the keyed open below. Fails if that load is removed.
        verify(exactly = 1) { DatabaseEncryption.ensureNativeLibraryLoaded() }
        unmockkObject(DatabaseEncryption)

        // The keyed open the provisioner reported must actually succeed on real SQLCipher (no crash).
        openEncrypted().apply {
            assertEquals(listOf("acct:1"), messageDao().observeSummaries().first().map { it.id })
            close()
        }
    }

    @Test
    fun encryptionTurnedOffDecryptsAnEncryptedCacheToPlaintext() = runBlocking<Unit> {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = false, appLock = false))
        seedPlaintextRow()
        DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
        assertTrue("precondition: the cache starts encrypted", DatabaseEncryption.isEncrypted(dbFile))

        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Plaintext, mode)
        assertFalse("the cache must be decrypted so the unkeyed open works", DatabaseEncryption.isEncrypted(dbFile))
        openPlaintext().apply {
            assertEquals(listOf("acct:1"), messageDao().observeSummaries().first().map { it.id })
            close()
        }
    }

    @Test
    fun plaintextStartLeavesThePlaintextCacheUntouched() = runBlocking<Unit> {
        every { settingsRepository.settings } returns flowOf(AppSettings(encryptCache = false, appLock = false))
        seedPlaintextRow()
        assertFalse("precondition: the cache is plaintext", DatabaseEncryption.isEncrypted(dbFile))

        val mode = provisioner().prepareCache()

        assertEquals(CacheOpenMode.Plaintext, mode)
        assertFalse("a plaintext-with-encryption-off start converts nothing", DatabaseEncryption.isEncrypted(dbFile))
        openPlaintext().apply {
            assertEquals(listOf("acct:1"), messageDao().observeSummaries().first().map { it.id })
            close()
        }
    }
}
