// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The user-chosen default account (#163), covered at the `toAppSettings()` mapping layer:
 * [SettingsRepository] itself needs a real `Context` for its DataStore, so (as with [AppSettingsTest]'s
 * coverage of [FetchPolicy]) the JVM-testable seam is the plain `Preferences` -> [AppSettings] mapping
 * that [SettingsRepository.setDefaultAccountId] and [SettingsRepository.clearDefaultAccountId] are
 * ultimately read back through.
 */
class SettingsRepositoryTest {

    private val defaultAccountIdKey = stringPreferencesKey("default_account_id")

    @Test
    fun `in-memory default has no default account`() {
        assertNull(AppSettings().defaultAccountId)
    }

    @Test
    fun `a persisted default account id round-trips`() {
        val prefs = preferencesOf(defaultAccountIdKey to "imap:me@example.org")

        assertEquals("imap:me@example.org", prefs.toAppSettings().defaultAccountId)
    }

    @Test
    fun `an absent key reads back as no default (the cleared state)`() {
        // setDefaultAccountId(null) and clearDefaultAccountId both remove the key rather than store a
        // sentinel value, so "cleared" and "never set" are indistinguishable and both map to null here.
        assertNull(emptyPreferences().toAppSettings().defaultAccountId)
    }
}
