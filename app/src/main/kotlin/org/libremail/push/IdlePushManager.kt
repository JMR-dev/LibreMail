// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Starts and stops [IdleService] to match the user's push-mail (IMAP IDLE) preference. */
@Singleton
class IdlePushManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        // Starting a foreground service from a background process is disallowed on modern Android;
        // swallow that case — periodic WorkManager sync still covers mail, and IDLE starts the next
        // time the app is in the foreground.
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, IdleService::class.java))
        }
    }

    fun stop() {
        runCatching { context.stopService(Intent(context, IdleService::class.java)) }
    }
}
