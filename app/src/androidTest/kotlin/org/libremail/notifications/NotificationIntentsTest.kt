// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.notifications

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks in the notification deep-link contract: a message id round-trips build → parse, and intents
 * for different messages are distinct under [Intent.filterEquals] — the identity PendingIntent keys
 * on — so per-message notifications never collapse onto one shared PendingIntent.
 */
@RunWith(AndroidJUnit4::class)
class NotificationIntentsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun message_id_round_trips_through_the_intent() {
        val id = "imap:user@example.com:INBOX:42"
        assertEquals(id, NotificationIntents.messageId(NotificationIntents.openMessage(context, id)))
    }

    @Test
    fun uri_hostile_ids_round_trip() {
        val id = "imap:user@example.com:[Gmail]/All Mail:7?&%#"
        assertEquals(id, NotificationIntents.messageId(NotificationIntents.openMessage(context, id)))
    }

    @Test
    fun other_intents_carry_no_message_id() {
        assertNull(NotificationIntents.messageId(null))
        assertNull(NotificationIntents.messageId(Intent(Intent.ACTION_MAIN)))
        assertNull(NotificationIntents.messageId(Intent(Intent.ACTION_VIEW, Uri.parse("mailto:a@b.c"))))
    }

    @Test
    fun intents_for_different_messages_are_distinct_pending_intent_keys() {
        val first = NotificationIntents.openMessage(context, "imap:a@b:INBOX:1")
        val second = NotificationIntents.openMessage(context, "imap:a@b:INBOX:2")
        assertFalse(first.filterEquals(second))
        assertTrue(first.filterEquals(NotificationIntents.openMessage(context, "imap:a@b:INBOX:1")))
    }
}
