// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import android.security.keystore.KeyGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM encryption backed by a non-exportable key in the Android Keystore. Secrets (OAuth
 * tokens, IMAP passwords) are encrypted at rest so they never touch disk in plaintext.
 *
 * The non-auth-bound **master** key: usable in the background without a user-presence prompt, so
 * credential access keeps working while the app is locked. As the master key it is minted lazily on a
 * missing-alias decrypt (`generateKeyOnDecrypt = true`) — correct for a first run that has nothing
 * sealed yet. Contrast the auth-bound [DatabaseKeyCipher], whose absent key means invalidation and so
 * fails fast; the shared [AesGcmKeystoreCipher] documents why the two must differ.
 */
@Singleton
class KeystoreCrypto @Inject constructor() : AesGcmKeystoreCipher(alias = KEY_ALIAS, generateKeyOnDecrypt = true) {

    override fun keySpec(strongBox: Boolean): KeyGenParameterSpec = keySpecBuilder(strongBox).build()

    private companion object {
        const val KEY_ALIAS = "libremail.master.key"
    }
}
