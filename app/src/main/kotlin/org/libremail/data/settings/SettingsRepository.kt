// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "libremail_settings")

/**
 * How aggressively the app downloads message content during sync.
 * - [ALWAYS]: fetch full bodies and all attachments on any connection (default).
 * - [WIFI_ONLY]: fetch full content only on an unmetered network; otherwise behave like [ON_DEMAND].
 * - [ON_DEMAND]: sync headers only; fetch a message's body/attachments lazily when it's opened.
 */
enum class FetchPolicy { ALWAYS, WIFI_ONLY, ON_DEMAND }

/** User preferences. Only [dynamicColor] and [newMailNotifications] are wired to behaviour so far. */
data class AppSettings(
    val dynamicColor: Boolean = true,
    val newMailNotifications: Boolean = true,
    val pushIdle: Boolean = true,
    val allowStartTls: Boolean = false,
    val loadRemoteImages: Boolean = false,
    val encryptCache: Boolean = false,
    val fetchPolicy: FetchPolicy = FetchPolicy.ALWAYS,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            dynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            newMailNotifications = prefs[NEW_MAIL_NOTIFICATIONS] ?: true,
            pushIdle = prefs[PUSH_IDLE] ?: true,
            allowStartTls = prefs[ALLOW_STARTTLS] ?: false,
            loadRemoteImages = prefs[LOAD_REMOTE_IMAGES] ?: false,
            encryptCache = prefs[ENCRYPT_CACHE] ?: false,
            fetchPolicy = prefs[FETCH_POLICY]?.let { runCatching { FetchPolicy.valueOf(it) }.getOrNull() }
                ?: FetchPolicy.ALWAYS,
        )
    }

    val dynamicColor: Flow<Boolean> = settings.map { it.dynamicColor }

    suspend fun isNewMailNotificationsEnabled(): Boolean = settings.first().newMailNotifications

    suspend fun fetchPolicy(): FetchPolicy = settings.first().fetchPolicy

    suspend fun setDynamicColor(value: Boolean) = put(DYNAMIC_COLOR, value)
    suspend fun setNewMailNotifications(value: Boolean) = put(NEW_MAIL_NOTIFICATIONS, value)
    suspend fun setPushIdle(value: Boolean) = put(PUSH_IDLE, value)
    suspend fun setAllowStartTls(value: Boolean) = put(ALLOW_STARTTLS, value)
    suspend fun setLoadRemoteImages(value: Boolean) = put(LOAD_REMOTE_IMAGES, value)
    suspend fun setEncryptCache(value: Boolean) = put(ENCRYPT_CACHE, value)
    suspend fun setFetchPolicy(value: FetchPolicy) {
        context.settingsDataStore.edit { it[FETCH_POLICY] = value.name }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private companion object {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val NEW_MAIL_NOTIFICATIONS = booleanPreferencesKey("new_mail_notifications")
        val PUSH_IDLE = booleanPreferencesKey("push_idle")
        val ALLOW_STARTTLS = booleanPreferencesKey("allow_starttls")
        val LOAD_REMOTE_IMAGES = booleanPreferencesKey("load_remote_images")
        val ENCRYPT_CACHE = booleanPreferencesKey("encrypt_cache")
        val FETCH_POLICY = stringPreferencesKey("fetch_policy")
    }
}
