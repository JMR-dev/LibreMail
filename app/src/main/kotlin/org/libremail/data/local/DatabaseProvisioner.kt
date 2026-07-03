// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** How the cache database ([LibreMailDatabase]) must be opened, decided by [DatabaseProvisioner]. */
sealed interface CacheOpenMode {
    /** Open with SQLCipher, keyed by [passphrase] — the opt-in encrypted cache. */
    data class Encrypted(val passphrase: String) : CacheOpenMode

    /** Open with the default framework helper — the cache is plaintext on disk. */
    data object Plaintext : CacheOpenMode
}

/**
 * Runs the one-time, blocking startup sequence that must complete BEFORE Room opens either database —
 * exactly once, memoized, and OFF the Hilt injection path (issue #93).
 *
 * `DatabaseModule.provideDatabase` used to do this work inline, with `runBlocking`, while Hilt
 * constructed the singleton [LibreMailDatabase]: a DataStore read, a Keystore op, a possible SQLCipher
 * re-key conversion, and (since #111) the cross-database [AccountDataMigrator]. All of it ran
 * synchronously on whichever thread first injected the database — which can be the main thread — so the
 * first DB access could jank or ANR (worst with the encrypted cache on). This class moves that work
 * behind [prepareCache]; the Hilt providers wire it into a [DeferredOpenHelperFactory] so it runs
 * lazily, on Room's background open, never at inject time.
 *
 * The sequence, its ordering, and its crash-safety are unchanged from the old `provideDatabase` — only
 * WHERE and WHEN it runs moved:
 *  1. If a screen-lock change flagged the encrypted cache for wiping, wipe it and reset its seals
 *     (before Room opens the file, so no open connection is deleted underneath it).
 *  2. Run [AccountDataMigrator] — the one-time move of accounts/credentials/settings/signatures into
 *     the non-auth [AccountDatabase] (issue #111). MUST precede opening the cache (whose
 *     [MIGRATION_15_16] drops the moved tables) AND opening [AccountDatabase] (which reads the copied
 *     rows). Both databases' open paths gate on [prepareCache], so the migrate-before-open guarantee
 *     holds regardless of which database Room opens first.
 *  3. Resolve the encryption gate: convert the on-disk cache to the form the `encryptCache` setting
 *     asks for, and report how the cache must be opened.
 *
 * [prepareCache] is memoized on success and guarded by a [Mutex], so the first database to open runs
 * the sequence and any concurrent or later opener awaits the same result. A failure is NOT memoized, so
 * it retries on the next open — preserving the migrator's "crash-loop rather than lose data" contract
 * (a throw here means the cache never opens, so [MIGRATION_15_16] never drops the not-yet-copied rows).
 */
@Singleton
class DatabaseProvisioner internal constructor(
    private val context: Context,
    private val keyStore: DatabaseKeyStore,
    private val settingsRepository: SettingsRepository,
    private val accountDataMigrator: AccountDataMigrator,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        keyStore: DatabaseKeyStore,
        settingsRepository: SettingsRepository,
        accountDataMigrator: AccountDataMigrator,
    ) : this(context, keyStore, settingsRepository, accountDataMigrator, Dispatchers.IO)

    private val mutex = Mutex()

    @Volatile
    private var prepared: CacheOpenMode? = null

    /**
     * Runs the startup sequence exactly once (on [ioDispatcher]) and returns how the cache must be
     * opened. Idempotent and safe to call concurrently from both databases' open paths; the blocking
     * work runs on [ioDispatcher], never on the caller's thread past the suspension point.
     */
    suspend fun prepareCache(): CacheOpenMode {
        prepared?.let { return it }
        return mutex.withLock {
            prepared ?: withContext(ioDispatcher) { runStartupSequence() }.also { prepared = it }
        }
    }

    private suspend fun runStartupSequence(): CacheOpenMode {
        val dbFile = context.getDatabasePath(DatabaseFiles.NAME)

        // A screen-lock change (biometric re-enrollment / lock removal) can invalidate the auth-bound
        // key so the encrypted cache is no longer decryptable. AppLockViewModel records that and
        // restarts the app; we wipe the cache HERE — before Room opens it — so the file is never
        // deleted from under an open connection. Crash-safe order: wipe + reset the seals, and only THEN
        // clear the flag, so a kill mid-wipe just repeats the idempotent wipe next start. Only
        // libremail.db is wiped: accounts/credentials live in AccountDatabase (a separate file), so the
        // user stays signed in across the wipe (issue #111).
        if (keyStore.isClearPending()) {
            DatabaseFiles.clear(context)
            keyStore.resetSealedPassphrase()
            keyStore.clearClearPending()
        }

        // One-time move of accounts/credentials/settings/signatures into the non-auth AccountDatabase
        // (issue #111). MUST run before the cache opens: opening it applies MIGRATION_15_16, which drops
        // the moved tables. Runs AFTER the wipe above so an unrecoverable-key cache is gone first
        // (nothing left to move) and we never block waiting on a passphrase we can't get.
        accountDataMigrator.migrateIfNeeded()

        // Opt-in at-rest encryption of the local cache (off by default). The conversion runs here —
        // before the database is opened — so it never races an open connection; toggling the setting
        // therefore takes effect on the next app start. The passphrase source is resolved from which
        // seal actually exists (DatabaseKeyStore.resolvePassphrase), NOT from the app-lock setting (a
        // separate DataStore that can disagree). When app-lock is ON the sealing key is auth-bound, so
        // resolvePassphrase waits on PassphraseSession until the user authenticates — which is why this
        // must never run on the main thread while the cache is locked (issue #93).
        val settings = settingsRepository.settings.first()
        val appLock = settings.appLock
        return when {
            settings.encryptCache -> {
                val passphrase = keyStore.resolvePassphrase(appLock)
                DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
                // Room is about to open the cache with SQLCipher (SupportOpenHelperFactory), so its
                // native library must already be loaded. ensureEncrypted() above loads it only as a
                // side effect of an actual plaintext -> encrypted conversion; on a steady-state start
                // (cache already encrypted, nothing to convert) that no-ops, so without this explicit
                // load the keyed open reaches SQLiteConnection.nativeOpen with no library loaded and
                // crashes with UnsatisfiedLinkError on every cold start once encryption is enabled.
                DatabaseEncryption.ensureNativeLibraryLoaded()
                CacheOpenMode.Encrypted(passphrase)
            }

            DatabaseEncryption.isEncrypted(dbFile) -> {
                // Encryption was turned back off — decrypt so the default (unkeyed) open succeeds.
                val passphrase = keyStore.resolvePassphrase(appLock)
                DatabaseEncryption.ensurePlaintext(dbFile, passphrase)
                CacheOpenMode.Plaintext
            }

            else -> CacheOpenMode.Plaintext
        }
    }
}
