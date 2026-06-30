// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCryptoTest {

    private val crypto = KeystoreCrypto()

    @Test
    fun encrypt_then_decrypt_returns_original() {
        val secret = "imap:app-password 🔒 {\"json\":true}"
        val encrypted = crypto.encrypt(secret)
        assertNotEquals(secret, encrypted)
        assertEquals(secret, crypto.decrypt(encrypted))
    }

    @Test
    fun encrypt_uses_a_fresh_iv_each_time() {
        // Same plaintext must not produce identical ciphertext (random GCM IV).
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"))
    }
}
