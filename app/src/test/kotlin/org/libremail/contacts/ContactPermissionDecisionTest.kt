// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.contacts

import org.junit.Test
import kotlin.test.assertEquals

class ContactPermissionDecisionTest {

    @Test
    fun `granted is always ON, regardless of the other signals`() {
        for (rationale in listOf(false, true)) {
            for (requested in listOf(false, true)) {
                val state = ContactPermissionDecision.resolve(
                    granted = true,
                    showRationale = rationale,
                    alreadyRequested = requested,
                )
                assertEquals(
                    ContactPermissionState.GRANTED,
                    state,
                    "granted=true must always be GRANTED (rationale=$rationale, requested=$requested)",
                )
            }
        }
    }

    @Test
    fun `denied once with a rationale owed is re-requestable (DENIED)`() {
        assertEquals(
            ContactPermissionState.DENIED,
            ContactPermissionDecision.resolve(granted = false, showRationale = true, alreadyRequested = true),
        )
    }

    @Test
    fun `never asked yet is re-requestable (DENIED), not blocked`() {
        // No rationale AND never requested = a fresh install that simply hasn't asked; a request works.
        assertEquals(
            ContactPermissionState.DENIED,
            ContactPermissionDecision.resolve(granted = false, showRationale = false, alreadyRequested = false),
        )
    }

    @Test
    fun `permanently denied is BLOCKED`() {
        // Requested before, no rationale now, still not granted = "don't ask again" — Settings only.
        assertEquals(
            ContactPermissionState.BLOCKED,
            ContactPermissionDecision.resolve(granted = false, showRationale = false, alreadyRequested = true),
        )
    }
}
