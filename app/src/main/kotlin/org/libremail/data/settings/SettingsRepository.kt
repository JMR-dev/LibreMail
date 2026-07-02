// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import android.app.backup.BackupManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-preferences DataStore. Exposed as `internal` (not `private`) and read via [toAppSettings] so
 * that [org.libremail.backup.LibreMailBackupAgent] can consult the backup opt-in flag through the
 * exact same singleton instance. The system instantiates the backup agent in the app process while
 * the app may already hold this DataStore open; constructing a second DataStore for the same file
 * would crash with "There are multiple DataStores active for the same file", so both sides must go
 * through this one delegate.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "libremail_settings")

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
    val appLock: Boolean = false,
    val includeInBackup: Boolean = false,
    val fetchPolicy: FetchPolicy = FetchPolicy.ALWAYS,
    /**
     * Global device-only retention defaults (issue #13), applied to accounts that don't override them.
     * `0` means "keep everything" (the default), matching the fetch-all history behaviour of #12.
     */
    val retentionCount: Int = 0,
    val retentionMonths: Int = 0,
)

private object Keys {
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val NEW_MAIL_NOTIFICATIONS = booleanPreferencesKey("new_mail_notifications")
    val PUSH_IDLE = booleanPreferencesKey("push_idle")
    val ALLOW_STARTTLS = booleanPreferencesKey("allow_starttls")
    val LOAD_REMOTE_IMAGES = booleanPreferencesKey("load_remote_images")
    val ENCRYPT_CACHE = booleanPreferencesKey("encrypt_cache")
    val APP_LOCK = booleanPreferencesKey("app_lock")
    val INCLUDE_IN_BACKUP = booleanPreferencesKey("include_in_backup")
    val FETCH_POLICY = stringPreferencesKey("fetch_policy")
    val RETENTION_COUNT = intPreferencesKey("retention_count")
    val RETENTION_MONTHS = intPreferencesKey("retention_months")
    val BATTERY_PROMPT_HANDLED = booleanPreferencesKey("battery_prompt_handled")
}

/**
 * Maps persisted preferences to [AppSettings]. Shared with the backup agent so it reads the opt-in
 * flag (and its default) through exactly the same logic the app uses.
 */
internal fun Preferences.toAppSettings(): AppSettings = AppSettings(
    dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
    newMailNotifications = this[Keys.NEW_MAIL_NOTIFICATIONS] ?: true,
    pushIdle = this[Keys.PUSH_IDLE] ?: true,
    allowStartTls = this[Keys.ALLOW_STARTTLS] ?: false,
    loadRemoteImages = this[Keys.LOAD_REMOTE_IMAGES] ?: false,
    encryptCache = this[Keys.ENCRYPT_CACHE] ?: false,
    appLock = this[Keys.APP_LOCK] ?: false,
    includeInBackup = this[Keys.INCLUDE_IN_BACKUP] ?: false,
    fetchPolicy = this[Keys.FETCH_POLICY]?.let { runCatching { FetchPolicy.valueOf(it) }.getOrNull() }
        ?: FetchPolicy.ALWAYS,
    retentionCount = this[Keys.RETENTION_COUNT] ?: 0,
    retentionMonths = this[Keys.RETENTION_MONTHS] ?: 0,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toAppSettings() }

    val dynamicColor: Flow<Boolean> = settings.map { it.dynamicColor }

    suspend fun isNewMailNotificationsEnabled(): Boolean = settings.first().newMailNotifications

    suspend fun fetchPolicy(): FetchPolicy = settings.first().fetchPolicy

    /**
     * One-time onboarding flag: whether the user has already seen/acted on the "unrestricted battery"
     * opt-in, so onboarding asks at most once (see #49). Not part of [AppSettings] — it is internal
     * onboarding state, not a user-facing preference. It rides along in Android Backup (the whole
     * settings DataStore is one file); a restore therefore may skip the prompt on a device that isn't
     * yet allowlisted — the Advanced Settings battery row is the recovery path there.
     */
    suspend fun isBatteryPromptHandled(): Boolean =
        context.settingsDataStore.data.map { it[Keys.BATTERY_PROMPT_HANDLED] ?: false }.first()

    suspend fun setBatteryPromptHandled(value: Boolean) = put(Keys.BATTERY_PROMPT_HANDLED, value)

    suspend fun setDynamicColor(value: Boolean) = put(Keys.DYNAMIC_COLOR, value)
    suspend fun setNewMailNotifications(value: Boolean) = put(Keys.NEW_MAIL_NOTIFICATIONS, value)
    suspend fun setPushIdle(value: Boolean) = put(Keys.PUSH_IDLE, value)
    suspend fun setAllowStartTls(value: Boolean) = put(Keys.ALLOW_STARTTLS, value)
    suspend fun setLoadRemoteImages(value: Boolean) = put(Keys.LOAD_REMOTE_IMAGES, value)
    suspend fun setEncryptCache(value: Boolean) = put(Keys.ENCRYPT_CACHE, value)
    suspend fun setAppLock(value: Boolean) = put(Keys.APP_LOCK, value)

    /**
     * Opts this app in/out of system Android Backup. Off by default. After persisting, nudges the
     * framework so the change takes effect on the next backup pass — enabling schedules a backup of
     * the safe settings, disabling schedules one that ships nothing (clearing any prior cloud copy).
     */
    suspend fun setIncludeInBackup(value: Boolean) {
        put(Keys.INCLUDE_IN_BACKUP, value)
        runCatching { BackupManager(context).dataChanged() }
    }

    suspend fun setFetchPolicy(value: FetchPolicy) {
        context.settingsDataStore.edit { it[Keys.FETCH_POLICY] = value.name }
    }

    /** Global default retention by message count (newest N per folder); 0 = keep everything. */
    suspend fun setRetentionCount(value: Int) {
        context.settingsDataStore.edit { it[Keys.RETENTION_COUNT] = value.coerceAtLeast(0) }
    }

    /** Global default retention by age in months; 0 = keep everything. */
    suspend fun setRetentionMonths(value: Int) {
        context.settingsDataStore.edit { it[Keys.RETENTION_MONTHS] = value.coerceAtLeast(0) }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }
}
