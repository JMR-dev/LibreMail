// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.local.toDomain
import org.libremail.domain.model.Account
import org.libremail.domain.model.AuthType
import org.libremail.domain.model.OutgoingMessage
import org.libremail.mail.GraphSender
import org.libremail.mail.SmtpSender

/** Drains the outbox: sends each queued message over SMTP, deleting it on success. */
@HiltWorker
class SendWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val accountDao: AccountDao,
    private val smtpSender: SmtpSender,
    private val graphSender: GraphSender,
    private val connectionFactory: MailConnectionFactory,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
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
                    subject = entity.subject,
                    body = entity.body,
                )
                val files = attachmentDir.listFiles()?.toList().orEmpty()
                if (account.authType == AuthType.OAUTH_OUTLOOK) {
                    sendOutlook(account, message, files)
                } else {
                    smtpSender.send(connectionFactory.smtpParamsFor(account), from = account.email, message = message, attachments = files)
                }
            }.fold(
                onSuccess = {
                    outboxDao.delete(entity.id)
                    attachmentDir.deleteRecursively()
                },
                onFailure = { e ->
                    outboxDao.setError(entity.id, e.message)
                    anyFailed = true
                },
            )
        }
        // Retry (with WorkManager backoff) so failed sends are reattempted when conditions improve.
        return if (anyFailed) Result.retry() else Result.success()
    }

    /** Outlook prefers Microsoft Graph; fall back to SMTP (XOAUTH2) if the Graph send fails. */
    private suspend fun sendOutlook(account: Account, message: OutgoingMessage, files: List<File>) {
        runCatching {
            graphSender.send(connectionFactory.graphTokenFor(account), message, files)
        }.getOrElse {
            smtpSender.send(connectionFactory.smtpParamsFor(account), from = account.email, message = message, attachments = files)
        }
    }
}
