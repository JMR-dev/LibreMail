// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.sync.PushMode

/**
 * On-device coverage of [PushStatusNotification] — the foreground-notification logic [IdleService]
 * delegates to (issue #257). `NotificationChannel`/`NotificationCompat` are no-op stubs in the
 * unit-test `android.jar`, so this is the only place the real channel importance and the push-mode →
 * text branch can be asserted. It uses the real application `Context` (a `ContextWrapper`, never a
 * mocked `Context`), mirroring `BatteryOptimizationManagerIntentTest`, and touches no service
 * lifecycle, Hilt graph, or network — so it is deterministic and side-effect-free beyond registering a
 * low-importance notification channel.
 */
@RunWith(AndroidJUnit4::class)
class PushStatusNotificationInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun ensureChannel_registersALowImportancePushChannel() {
        PushStatusNotification.ensureChannel(context)

        val channel = requireNotNull(
            NotificationManagerCompat.from(context).getNotificationChannel(PushStatusNotification.CHANNEL_ID),
        ) { "push status channel must be registered" }
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun build_inIdleMode_saysConnectedForInstantDeliveryAndIsAnOngoingServiceNotification() {
        val notification = PushStatusNotification.build(context, PushMode.IDLE)

        assertEquals(
            context.getString(R.string.notif_push_status_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(
            context.getString(R.string.notif_push_status_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertTrue(
            "status notification must be ongoing",
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
    }

    @Test
    fun build_inPollingMode_saysLowBatteryFallback() {
        val notification = PushStatusNotification.build(context, PushMode.POLLING)

        assertEquals(
            context.getString(R.string.notif_push_status_text_low_battery),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }
}
