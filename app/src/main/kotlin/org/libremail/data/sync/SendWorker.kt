// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.toDomain
import org.libremail.data.security.EncryptedCacheGuard
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.OutgoingMessage
import org.libremail.mail.GraphSendException
import org.libremail.mail.GraphSender
import org.libremail.mail.SmtpSender
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Drains the outbox: sends each queued message over Graph/SMTP, deleting it on success. */
@HiltWorker
class SendWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Lazy: OutboxDao/AccountDao/MailConnectionFactory all resolve the Room DB (the last via
    // CredentialStore), which blocks while the encrypted cache is locked. Resolve them only after the
    // cache-lock check, so a run while locked fails fast instead of parking a WorkManager thread.
    private val outboxDao: Lazy<OutboxDao>,
    private val accountDao: Lazy<AccountDao>,
    private val smtpSender: SmtpSender,
    private val graphSender: GraphSender,
    private val connectionFactory: Lazy<MailConnectionFactory>,
    private val cacheGuard: EncryptedCacheGuard,
) : CoroutineWorker(appContext, workerParams) {

    private companion object {
        const val TAG = "SendWorker"
    }

    override suspend fun doWork(): Result {
        if (cacheGuard.isCacheLocked()) return Result.retry()
        val outboxDao = this.outboxDao.get()
        val accountDao = this.accountDao.get()
        val connectionFactory = this.connectionFactory.get()
        val pending = outboxDao.getAll()
        if (pending.isEmpty()) return Result.success()

        var anyFailed = false
        for (entity in pending) {
            val attachmentDir = File(applicationContext.cacheDir, "outbox/${entity.id}")
            val account = accountDao.getById(entity.accountId)?.toDomain()
            if (account == null) {
                outboxDao.delete(entity.id) // account removed — drop the queued message
                attachmentDir.deleteRecursively()
                continue
            }
            runCatching {
                val message = OutgoingMessage(
                    accountId = entity.accountId,
                    to = entity.toAddresses,
                    cc = entity.ccAddresses,
                    bcc = entity.bccAddresses,
                    subject = entity.subject,
                    body = entity.body,
                    bodyHtml = entity.bodyHtml,
                )
                val files = orderedAttachments(attachmentDir)
                if (account.authType == AuthType.OAUTH_OUTLOOK) {
                    sendOutlook(connectionFactory, account, message, files)
                } else {
                    smtpSender.send(
                        connectionFactory.smtpParamsFor(account),
                        from = account.email,
                        message = message,
                        attachments = files,
                    )
                }
            }.fold(
                onSuccess = {
                    outboxDao.delete(entity.id)
                    attachmentDir.deleteRecursively()
                },
                onFailure = { e ->
                    if (e is GraphSendException && e.mayHaveSent) {
                        // Graph may already have delivered this; auto-retrying (or any other send)
                        // would duplicate it, so leave it queued with a clear status and let the
                        // user decide. Not counted as a failure, so WorkManager won't auto-retry.
                        outboxDao.setError(
                            entity.id,
                            "Send status unknown — check your Sent folder, then retry or cancel",
                        )
                    } else {
                        outboxDao.setError(entity.id, e.message)
                        anyFailed = true
                    }
                },
            )
        }
        // Retry (with WorkManager backoff) so failed sends are reattempted when conditions improve.
        return if (anyFailed) Result.retry() else Result.success()
    }

    /**
     * Outlook prefers Microsoft Graph. Fall back to SMTP only when Graph definitely did NOT send
     * (a rejection, a pre-send/transport error, or a token failure); never fall back when the Graph
     * request may already have been accepted, or the message would be sent twice.
     */
    private suspend fun sendOutlook(
        connectionFactory: MailConnectionFactory,
        account: Account,
        message: OutgoingMessage,
        files: List<File>,
    ) {
        try {
            val token = connectionFactory.graphTokenFor(account)
            graphSender.send(token, message, files)
        } catch (e: GraphSendException) {
            if (e.mayHaveSent) throw e
            smtpSender.send(
                connectionFactory.smtpParamsFor(account),
                from = account.email,
                message = message,
                attachments = files,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Graph was never reached (e.g. token refresh failed) — SMTP cannot duplicate it.
            Log.w(TAG, "Graph send failed for ${account.email}; falling back to SMTP", e)
            smtpSender.send(
                connectionFactory.smtpParamsFor(account),
                from = account.email,
                message = message,
                attachments = files,
            )
        }
    }

    /** Attachments are staged one-per-indexed-subdirectory so their original order is preserved. */
    private fun orderedAttachments(dir: File): List<File> = dir.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name.toIntOrNull() ?: Int.MAX_VALUE }
        ?.mapNotNull { it.listFiles()?.firstOrNull() }
        .orEmpty()
}
