// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

/**
 * Raised by [DatabaseProvisioner] when the opt-in encrypted cache cannot be opened because SQLCipher's
 * native library failed to load or link on this device (issue #359 — e.g. an `.so` the platform
 * rejects, surfacing as an `UnsatisfiedLinkError`/`LinkageError` at `SQLiteConnection.nativeOpen` or
 * from [DatabaseEncryption.ensureNativeLibraryLoaded]).
 *
 * The app must **fail closed**: it must NOT fall back to an unencrypted cache (that would silently
 * defeat the user's opt-in encryption), NOT wipe the on-disk ciphertext, and NOT touch the
 * `encryptCache` setting. Instead this distinct, expected signal is surfaced so the startup UI
 * (`CacheEncryptionGate`) can show the encryption error gate — not the mailbox, and not a crash.
 *
 * Deliberately a dedicated type (not a bare [LinkageError]) so only this precise condition is treated
 * as "encryption unavailable"; any other failure still propagates. The provisioner never memoizes it,
 * so a later launch — where the library may load, e.g. after an app update — re-attempts and recovers
 * automatically.
 */
class CacheEncryptionUnavailableException(cause: Throwable) :
    Exception(
        "Encrypted cache unavailable: the SQLCipher native library failed to load on this device",
        cause,
    )
