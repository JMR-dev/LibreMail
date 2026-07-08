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
 * Locks in the notification deep-link contract: a message id round-trips build → parse, intents for
 * different messages are distinct under [Intent.filterEquals] — the identity PendingIntent keys on —
 * so per-message notifications never collapse onto one shared PendingIntent, and an `ACTION_OPEN_MESSAGE`
 * intent that does not carry this app's own sender token is ignored so a foreign caller targeting the
 * exported activity cannot drive the reader (#307).
 */
@RunWith(AndroidJUnit4::class)
class NotificationIntentsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun message_id_round_trips_through_the_intent() {
        val id = "imap:user@example.com:INBOX:42"
        assertEquals(id, NotificationIntents.messageId(context, NotificationIntents.openMessage(context, id)))
    }

    @Test
    fun uri_hostile_ids_round_trip() {
        val id = "imap:user@example.com:[Gmail]/All Mail:7?&%#"
        assertEquals(id, NotificationIntents.messageId(context, NotificationIntents.openMessage(context, id)))
    }

    @Test
    fun other_intents_carry_no_message_id() {
        assertNull(NotificationIntents.messageId(context, null))
        assertNull(NotificationIntents.messageId(context, Intent(Intent.ACTION_MAIN)))
        assertNull(NotificationIntents.messageId(context, Intent(Intent.ACTION_VIEW, Uri.parse("mailto:a@b.c"))))
    }

    @Test
    fun open_message_intent_without_our_sender_token_is_ignored() {
        // A hostile app can target the exported activity with our action (and a message id), but it
        // cannot mint a PendingIntent attributed to us — so an ACTION_OPEN_MESSAGE intent that lacks our
        // sender token must be ignored, while the genuine one (built by openMessage) still resolves.
        val genuine = NotificationIntents.openMessage(context, "imap:a@b:INBOX:1")
        val forged = Intent().setAction(genuine.action)
        assertNull(NotificationIntents.messageId(context, forged))
        assertEquals("imap:a@b:INBOX:1", NotificationIntents.messageId(context, genuine))
    }

    @Test
    fun intents_for_different_messages_are_distinct_pending_intent_keys() {
        val first = NotificationIntents.openMessage(context, "imap:a@b:INBOX:1")
        val second = NotificationIntents.openMessage(context, "imap:a@b:INBOX:2")
        assertFalse(first.filterEquals(second))
        assertTrue(first.filterEquals(NotificationIntents.openMessage(context, "imap:a@b:INBOX:1")))
    }
}
