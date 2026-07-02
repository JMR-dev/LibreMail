// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads this app's `READ_CONTACTS` grant and deep-links to the system screen where it can be changed.
 * `READ_CONTACTS` powers recipient autocomplete only (see [ContactsRepository]); the whole feature is
 * optional and degrades gracefully when the permission is absent.
 *
 * Deliberately Context-only so it can back both the onboarding opt-in step and the Settings entry.
 * The `shouldShowRequestPermissionRationale` signal needs an Activity, so it is read in the Compose
 * layer and combined with [ContactPermissionDecision]; this manager stays free of Activity state.
 */
@Singleton
class ContactsPermissionManager @Inject constructor(@ApplicationContext private val context: Context) {
    /** True when `READ_CONTACTS` is currently granted to this app. */
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    /**
     * Intent to this app's system details screen, where **Permissions → Contacts** can be toggled.
     * Used to recover the permanently-denied ("Don't allow" / don't-ask-again) case, which can no
     * longer be re-requested in-app. Always resolvable since API 9.
     */
    fun settingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
}
