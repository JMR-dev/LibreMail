// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks in the #157 regression fix: [IntentHandledMarker] must tell a redelivered (already-parsed)
 * intent apart from a genuinely new one using only the [Intent] instance itself, never
 * `savedInstanceState` — which Android also sets to non-null after a process-death relaunch, where the
 * delivered intent is a brand new, not-yet-handled notification tap or mailto/share, not a replay.
 */
@RunWith(AndroidJUnit4::class)
class IntentHandledMarkerTest {

    @Test
    fun first_look_marks_the_intent_and_reports_it_as_unhandled() {
        val intent = Intent(Intent.ACTION_MAIN)
        assertTrue(IntentHandledMarker.markIfUnhandled(intent))
    }

    @Test
    fun redelivering_the_same_instance_is_recognized_as_already_handled() {
        // Simulates a config-change recreation: Android redelivers the very same Intent object to the
        // new Activity instance's onCreate, so a second look must be recognized as a replay.
        val intent = Intent(Intent.ACTION_MAIN)
        IntentHandledMarker.markIfUnhandled(intent)
        assertFalse(IntentHandledMarker.markIfUnhandled(intent))
    }

    @Test
    fun a_freshly_constructed_equivalent_intent_is_still_unhandled() {
        // Simulates a process-death relaunch (e.g. tapping a notification after the app was killed in
        // the background): a brand new Intent instance arrives — content may equal one already marked
        // in a prior (now-dead) process, but it was never marked in THIS one, so it must be parsed.
        val first = Intent(Intent.ACTION_VIEW)
        IntentHandledMarker.markIfUnhandled(first)

        val second = Intent(Intent.ACTION_VIEW)
        assertTrue(IntentHandledMarker.markIfUnhandled(second))
    }
}
