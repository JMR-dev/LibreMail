// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.app.Notification
import android.app.Service
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.R
import org.libremail.data.sync.PushMode

/**
 * On-device coverage of the #354 dataSync-FGS degrade path that [IdleService.onStartCommand] routes
 * through [IdleForegroundStarter]. When a foreground start is rejected — the runtime-cap
 * `ForegroundServiceStartNotAllowedException`, surfaced as its [IllegalStateException] supertype — the
 * seam must catch it, skip IDLE watching, and degrade to periodic sync plus the degraded
 * ("instant delivery paused") notification, never propagating. This drives the same decision seam the
 * service uses and builds the real degraded notification with a real application `Context` (a
 * `ContextWrapper`, never a mocked `Context`), mirroring `PushStatusNotificationInstrumentedTest`; it
 * stands up no foreground service, Hilt graph, or network, so it is deterministic — and unlike a JVM
 * unit test it exercises the real `Notification` build (the unit-test `android.jar`'s
 * `NotificationCompat` is a no-op stub).
 */
@RunWith(AndroidJUnit4::class)
class IdleServiceForegroundStartInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun rejectedForegroundStart_degradesToPeriodicSyncWithPausedNotification_andSkipsWatching() {
        val rejection = IllegalStateException(
            "Time limit already exhausted for foreground service type dataSync",
        )
        var watchingStarted = false
        var periodicSyncScheduled = false
        var degradedNotification: Notification? = null

        val result = IdleForegroundStarter.startForegroundOrDegrade(
            capActive = false,
            enterForeground = { throw rejection },
            onStarted = { watchingStarted = true },
            onDegraded = { cause ->
                assertSame("the runtime-cap rejection must reach the degrade path", rejection, cause)
                // Mirror IdleService.degradeToPeriodicSync on a real Context: (re)assert periodic sync and
                // build the degraded status notification the service would post.
                periodicSyncScheduled = true
                PushStatusNotification.ensureChannel(context)
                degradedNotification = PushStatusNotification.build(context, PushMode.POLLING, timedOut = true)
            },
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse("a rejected dataSync FGS start must not begin IDLE watching", watchingStarted)
        assertTrue("the degrade path must (re)assert the 15-minute periodic sync fallback", periodicSyncScheduled)
        val notification = requireNotNull(degradedNotification) { "the degrade path must build a status notification" }
        assertEquals(
            "the degraded notification must show the instant-delivery-paused text",
            context.getString(R.string.notif_push_status_text_timed_out),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
    }

    @Test
    fun activeCapWindow_skipsForegroundStartAttempt_andStillDegrades() {
        var enterForegroundAttempted = false
        var watchingStarted = false
        var degraded = false

        val result = IdleForegroundStarter.startForegroundOrDegrade(
            capActive = true,
            enterForeground = { enterForegroundAttempted = true },
            onStarted = { watchingStarted = true },
            onDegraded = { degraded = true },
        )

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse("must not attempt a dataSync FGS start while still inside the cap window", enterForegroundAttempted)
        assertFalse(watchingStarted)
        assertTrue("must fall back to periodic sync while capped", degraded)
    }
}
