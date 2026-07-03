// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContactsPermissionManagerTest {

    private val context = mockk<Context>(relaxed = true) {
        every { packageName } returns "org.libremail.app"
    }
    private val manager = ContactsPermissionManager(context)

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `hasPermission is true only when READ_CONTACTS is granted`() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) } returns
            PackageManager.PERMISSION_GRANTED

        assertTrue(manager.hasPermission())
    }

    @Test
    fun `hasPermission is false when READ_CONTACTS is denied`() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) } returns
            PackageManager.PERMISSION_DENIED

        assertFalse(manager.hasPermission())
    }

    @Test
    fun `settingsIntent deep-links to this app's system details screen`() {
        mockkConstructor(Intent::class)
        mockkStatic(Uri::class)
        every { Uri.fromParts("package", "org.libremail.app", null) } returns mockk(relaxed = true)

        assertNotNull(manager.settingsIntent())

        // The intent is scoped to this package via a package: URI so the user lands on LibreMail's page.
        verify { Uri.fromParts("package", "org.libremail.app", null) }
    }
}
