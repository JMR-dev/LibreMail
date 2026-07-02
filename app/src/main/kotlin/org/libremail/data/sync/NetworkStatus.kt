// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * True when the device's active network is unmetered (e.g. Wi-Fi). Shared by [MailSyncer] and
 * [MailBackfiller] so both background jobs agree on what `Wi-Fi only` prefetch means; a divergent
 * copy would let one job download on cellular while the other doesn't.
 */
internal fun Context.isActiveNetworkUnmetered(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
