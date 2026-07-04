// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import org.junit.Assert.assertEquals
import org.junit.Test
import org.libremail.R
import org.libremail.data.sync.PushMode

/**
 * JVM coverage of [PushStatusNotification.statusTextRes] — the pure "which status message for which
 * push state" decision. [PushStatusNotification.build]'s `NotificationCompat` is a no-op stub in the
 * unit-test `android.jar`, so the built `Notification` is asserted on-device in
 * `PushStatusNotificationInstrumentedTest`; this verifies the text-selection branch — including the
 * #302 dataSync FGS runtime-cap fallback — with no emulator.
 */
class PushStatusNotificationTest {

    @Test
    fun `idle shows the instant-delivery text`() {
        assertEquals(
            R.string.notif_push_status_text,
            PushStatusNotification.statusTextRes(PushMode.IDLE, timedOut = false),
        )
    }

    @Test
    fun `polling shows the low-battery fallback text`() {
        assertEquals(
            R.string.notif_push_status_text_low_battery,
            PushStatusNotification.statusTextRes(PushMode.POLLING, timedOut = false),
        )
    }

    @Test
    fun `a dataSync FGS timeout shows the paused fallback text`() {
        assertEquals(
            R.string.notif_push_status_text_timed_out,
            PushStatusNotification.statusTextRes(PushMode.POLLING, timedOut = true),
        )
    }

    @Test
    fun `the timeout text wins even while push is nominally idle`() {
        // The platform delivers the runtime-cap timeout while the service is still in IDLE mode, so
        // timedOut must take precedence over the mode (#302).
        assertEquals(
            R.string.notif_push_status_text_timed_out,
            PushStatusNotification.statusTextRes(PushMode.IDLE, timedOut = true),
        )
    }
}
