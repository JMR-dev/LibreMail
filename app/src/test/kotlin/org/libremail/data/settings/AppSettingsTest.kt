// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
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
 *
 * Also covers `lastFontCss`/`lastFontSizePt` (#78): the last-used compose font/size, read back by
 * `ComposeViewModel` to seed brand-new compositions. Both are plain nullable round-trips — unset
 * must read back as null (never a font is a valid, common state), not some sentinel.
 */
class AppSettingsTest {

    private val fetchPolicyKey = stringPreferencesKey("fetch_policy")
    private val licenseAcceptedKey = booleanPreferencesKey("license_accepted")
    private val lastFontCssKey = stringPreferencesKey("last_font_css")
    private val lastFontSizePtKey = intPreferencesKey("last_font_size_pt")

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

    @Test
    fun `in-memory default has no remembered font`() {
        assertEquals(null, AppSettings().lastFontCss)
        assertEquals(null, AppSettings().lastFontSizePt)
    }

    @Test
    fun `a never-set font preference reads back as null`() {
        val settings = emptyPreferences().toAppSettings()
        assertEquals(null, settings.lastFontCss)
        assertEquals(null, settings.lastFontSizePt)
    }

    @Test
    fun `a persisted font family and size round-trip independently`() {
        val prefs = preferencesOf(lastFontCssKey to "Georgia, serif", lastFontSizePtKey to 14)
        val settings = prefs.toAppSettings()
        assertEquals("Georgia, serif", settings.lastFontCss)
        assertEquals(14, settings.lastFontSizePt)
    }

    @Test
    fun `only the size can be set while the family stays null`() {
        val prefs = preferencesOf(lastFontSizePtKey to 18)
        val settings = prefs.toAppSettings()
        assertEquals(null, settings.lastFontCss)
        assertEquals(18, settings.lastFontSizePt)
    }
}
