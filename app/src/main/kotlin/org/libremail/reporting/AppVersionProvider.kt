// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves the app's version once, tolerating any lookup failure. */
@Singleton
class AppVersionProvider @Inject constructor(@ApplicationContext context: Context) {
    val versionName: String
    val versionCode: Long

    init {
        var name = UNKNOWN
        var code = 0L
        runCatching {
            @Suppress("DEPRECATION") // getPackageInfo(String, int) is fine for a plain version lookup.
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            name = info.versionName ?: UNKNOWN
            code = info.longVersionCode
        }
        versionName = name
        versionCode = code
    }

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
