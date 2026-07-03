// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

class AppVersionProviderTest {

    private fun context(pm: PackageManager): Context = mockk {
        every { packageName } returns "org.libremail.app"
        every { packageManager } returns pm
    }

    @Test
    fun `resolves the version name and code from the package info`() {
        val info = mockk<PackageInfo>(relaxed = true)
        info.versionName = "1.2.3"
        every { info.longVersionCode } returns 42L
        val pm = mockk<PackageManager> { every { getPackageInfo("org.libremail.app", 0) } returns info }

        val provider = AppVersionProvider(context(pm))

        assertEquals("1.2.3", provider.versionName)
        assertEquals(42L, provider.versionCode)
    }

    @Test
    fun `falls back to unknown when the package reports no version name`() {
        val info = mockk<PackageInfo>(relaxed = true)
        info.versionName = null
        every { info.longVersionCode } returns 7L
        val pm = mockk<PackageManager> { every { getPackageInfo(any<String>(), any<Int>()) } returns info }

        val provider = AppVersionProvider(context(pm))

        assertEquals("unknown", provider.versionName)
        assertEquals(7L, provider.versionCode)
    }

    @Test
    fun `tolerates a lookup failure and degrades to unknown`() {
        val pm = mockk<PackageManager> {
            every { getPackageInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        }

        val provider = AppVersionProvider(context(pm))

        assertEquals("unknown", provider.versionName)
        assertEquals(0L, provider.versionCode)
    }
}
