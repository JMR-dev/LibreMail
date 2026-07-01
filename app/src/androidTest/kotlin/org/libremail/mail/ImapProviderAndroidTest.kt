// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import androidx.test.ext.junit.runners.AndroidJUnit4
import jakarta.mail.Session
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Properties

/**
 * Guards the main "Jakarta/Angus Mail on Android" risk: that provider registration
 * (META-INF/javamail.*) survives packaging, so IMAP/SMTP providers resolve at runtime.
 */
@RunWith(AndroidJUnit4::class)
class ImapProviderAndroidTest {

    @Test
    fun imap_and_imaps_store_providers_resolve() {
        val session = Session.getInstance(Properties())
        assertNotNull(session.getStore("imaps"))
        assertNotNull(session.getStore("imap"))
    }

    @Test
    fun smtp_transport_provider_resolves() {
        val session = Session.getInstance(Properties())
        assertNotNull(session.getTransport("smtp"))
    }
}
