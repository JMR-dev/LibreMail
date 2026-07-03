// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Full-content prefetch must default to Wi-Fi-only (#88) — both for fresh installs (the [AppSettings]
 * default) and for existing installs that never touched the setting (the DataStore-read fallback in
 * [toAppSettings]); an explicit user choice always wins.
 *
 * Also covers `licenseAccepted` (#172), the same kind of default/fallback mapping but for a plain
 * boolean: unset must read back as "not yet accepted" so a fresh install (and any pre-#172 install
 * that never wrote the key) is routed through `Routes.ONBOARDING_LICENSE` rather than skipping it.
 */
class AppSettingsTest {

    private val fetchPolicyKey = stringPreferencesKey("fetch_policy")
    private val licenseAcceptedKey = booleanPreferencesKey("license_accepted")

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

    @Test
    fun `in-memory default has not accepted the license`() {
        assertEquals(false, AppSettings().licenseAccepted)
    }

    @Test
    fun `a never-set license flag reads back as not accepted`() {
        assertEquals(false, emptyPreferences().toAppSettings().licenseAccepted)
    }

    @Test
    fun `an accepted license persists and round-trips`() {
        val prefs = preferencesOf(licenseAcceptedKey to true)
        assertEquals(true, prefs.toAppSettings().licenseAccepted)
    }
}
