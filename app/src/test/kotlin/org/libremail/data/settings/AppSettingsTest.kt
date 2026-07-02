// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Full-content prefetch must default to Wi-Fi-only (#88) — both for fresh installs (the [AppSettings]
 * default) and for existing installs that never touched the setting (the DataStore-read fallback in
 * [toAppSettings]); an explicit user choice always wins.
 */
class AppSettingsTest {

    private val fetchPolicyKey = stringPreferencesKey("fetch_policy")

    @Test
    fun `in-memory default fetch policy is WIFI_ONLY`() {
        assertEquals(FetchPolicy.WIFI_ONLY, AppSettings().fetchPolicy)
    }

    @Test
    fun `DataStore fallback for a never-set fetch policy is WIFI_ONLY`() {
        assertEquals(FetchPolicy.WIFI_ONLY, emptyPreferences().toAppSettings().fetchPolicy)
    }

    @Test
    fun `an explicitly stored fetch policy is respected over the default`() {
        val prefs = preferencesOf(fetchPolicyKey to FetchPolicy.ALWAYS.name)
        assertEquals(FetchPolicy.ALWAYS, prefs.toAppSettings().fetchPolicy)
    }

    @Test
    fun `an unrecognized stored fetch policy falls back to WIFI_ONLY`() {
        val prefs = preferencesOf(fetchPolicyKey to "NOT_A_POLICY")
        assertEquals(FetchPolicy.WIFI_ONLY, prefs.toAppSettings().fetchPolicy)
    }
}
