// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity

/** A message header fetched from the server (no body — that arrives with the reader). */
data class FetchedMessage(
    val uid: String,
    val sender: String,
    val senderEmail: String,
    val subject: String,
    val timestampMillis: Long,
    val isRead: Boolean,
    val isFlagged: Boolean,
)

/** Thin IMAP client over Jakarta/Angus Mail. Supports password and XOAUTH2 auth. */
@Singleton
class ImapClient @Inject constructor() {

    /** Connects and returns the account's folder names. Throws on failure. */
    suspend fun listFolders(params: ImapConnectionParams): List<String> = withContext(Dispatchers.IO) {
        withStore(params) { store ->
            store.defaultFolder.list("*").map { it.fullName }
        }
    }

    /** Fetches the most recent [limit] INBOX headers, newest first. */
    suspend fun fetchRecentInbox(params: ImapConnectionParams, limit: Int): List<FetchedMessage> =
        withContext(Dispatchers.IO) {
            withStore(params) { store ->
                val inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_ONLY)
                try {
                    val total = inbox.messageCount
                    if (total == 0) return@withStore emptyList()

                    val messages = inbox.getMessages(maxOf(1, total - limit + 1), total)
                    inbox.fetch(
                        messages,
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(FetchProfile.Item.FLAGS)
                            add(UIDFolder.FetchProfileItem.UID)
                        },
                    )
                    val uidFolder = inbox as UIDFolder
                    messages.reversed().map { message ->
                        val from = message.from?.firstOrNull() as? InternetAddress
                        FetchedMessage(
                            uid = uidFolder.getUID(message).toString(),
                            sender = from?.personal ?: from?.address ?: "(unknown sender)",
                            senderEmail = from?.address.orEmpty(),
                            subject = message.subject ?: "(no subject)",
                            timestampMillis = (message.sentDate ?: message.receivedDate)?.time
                                ?: System.currentTimeMillis(),
                            isRead = message.isSet(Flags.Flag.SEEN),
                            isFlagged = message.isSet(Flags.Flag.FLAGGED),
                        )
                    }
                } finally {
                    runCatching { inbox.close(false) }
                }
            }
        }

    private inline fun <T> withStore(params: ImapConnectionParams, block: (Store) -> T): T {
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
        return try {
            block(store)
        } finally {
            runCatching { store.close() }
        }
    }

    private companion object {
        const val TIMEOUT_MS = "15000"
    }
}
