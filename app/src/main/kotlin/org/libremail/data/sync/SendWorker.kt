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
import org.libremail.domain.model.OutgoingMessage
import org.libremail.mail.SmtpSender

/** Drains the outbox: sends each queued message over SMTP, deleting it on success. */
@HiltWorker
class SendWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val accountDao: AccountDao,
    private val smtpSender: SmtpSender,
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
                smtpSender.send(
                    connectionFactory.smtpParamsFor(account),
                    from = account.email,
                    message = OutgoingMessage(
                        accountId = entity.accountId,
                        to = entity.toAddresses,
                        cc = entity.ccAddresses,
                        subject = entity.subject,
                        body = entity.body,
                    ),
                    attachments = attachmentDir.listFiles()?.toList().orEmpty(),
                )
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
}
