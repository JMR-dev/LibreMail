// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.Session
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity

/** Thin IMAP client over Jakarta/Angus Mail. Supports password and XOAUTH2 auth. */
@Singleton
class ImapClient @Inject constructor() {

    /** Connects and returns the account's folder names. Throws on failure. */
    suspend fun listFolders(params: ImapConnectionParams): List<String> = withContext(Dispatchers.IO) {
        val protocol = if (params.security == MailSecurity.SSL_TLS) "imaps" else "imap"
        val props = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", params.host)
            put("mail.$protocol.port", params.port.toString())
            put("mail.$protocol.connectiontimeout", TIMEOUT_MS)
            put("mail.$protocol.timeout", TIMEOUT_MS)
            put("mail.$protocol.writetimeout", TIMEOUT_MS)
            if (params.security == MailSecurity.STARTTLS) {
                put("mail.$protocol.starttls.enable", "true")
                put("mail.$protocol.starttls.required", "true")
            }
            if (params.useXoauth2) {
                put("mail.$protocol.auth.mechanisms", "XOAUTH2")
            }
        }

        val store = Session.getInstance(props).getStore(protocol)
        store.connect(params.host, params.port, params.username, params.secret)
        try {
            store.defaultFolder.list("*").map { it.fullName }
        } finally {
            runCatching { store.close() }
        }
    }

    private companion object {
        const val TIMEOUT_MS = "15000"
    }
}
