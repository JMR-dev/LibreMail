// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.contacts

/**
 * Where the optional contacts-autocomplete permission stands, as the Settings entry (#129) shows it.
 * - [GRANTED]: on — recipient autocomplete works.
 * - [DENIED]: off but re-requestable in-app (never asked, or denied once without "don't ask again").
 * - [BLOCKED]: off and no longer re-requestable — the only way back is the system settings screen.
 */
enum class ContactPermissionState { GRANTED, DENIED, BLOCKED }

/**
 * Pure mapping from the three Android permission signals to a [ContactPermissionState]. Kept free of
 * Android types so it is exhaustively unit-testable; the live inputs are read by
 * [ContactsPermissionManager] (grant), the Activity (`shouldShowRequestPermissionRationale`), and
 * [org.libremail.data.settings.SettingsRepository] (whether the system dialog has ever been shown).
 */
object ContactPermissionDecision {

    /**
     * Resolve the current state:
     * - [granted]: `READ_CONTACTS` is held → [ContactPermissionState.GRANTED].
     * - [showRationale]: the OS says a rationale should precede a re-request, i.e. the user denied
     *   once without "don't ask again" → still re-requestable, [ContactPermissionState.DENIED].
     * - [alreadyRequested]: the system dialog has been shown before. Combined with `!showRationale`
     *   (and not granted) this is the permanently-denied case → [ContactPermissionState.BLOCKED].
     *
     * The remaining case — not granted, no rationale, never requested — is a fresh install that has
     * simply never asked, so an in-app request will still surface the dialog: [ContactPermissionState.DENIED].
     */
    fun resolve(granted: Boolean, showRationale: Boolean, alreadyRequested: Boolean): ContactPermissionState = when {
        granted -> ContactPermissionState.GRANTED
        showRationale -> ContactPermissionState.DENIED
        alreadyRequested -> ContactPermissionState.BLOCKED
        else -> ContactPermissionState.DENIED
    }
}
