// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device cover for the issue-#359 keyed-open probe ([DatabaseEncryption.probeKeyedOpen]). The probe
 * is the fix for gap 2: it reaches the REAL `SQLiteConnection.nativeOpen` — the exact #359 crash site —
 * from inside `DatabaseProvisioner`'s fail-closed handler, so an open-time `UnsatisfiedLinkError` on an
 * incompatible device is caught and converted to `CacheEncryptionUnavailableException` before Room's
 * later deferred open can crash on it uncaught.
 *
 * This runs the real SQLCipher native path (mocked out of the JVM unit tests), asserting two things a
 * device is needed for: on a compatible device the probe opens+closes the keyed database WITHOUT
 * throwing, and — on every device — it leaves no file behind (it opens a throwaway sibling, never the
 * real cache, and cleans up its sidecars). All data is synthetic; nothing here is PII.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionProbeInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cacheName = "probe_test_cache.db"
    private val cacheFile: File get() = context.getDatabasePath(cacheName)

    // 64 hex chars == a 32-byte SQLCipher passphrase, matching DatabaseKeyStore's format.
    private val passphrase = "0123456789abcdef".repeat(4)

    @Before
    @After
    fun clean() {
        cacheFile.parentFile
            ?.listFiles { f -> f.name.startsWith(cacheName) }
            ?.forEach { it.delete() }
    }

    @Test
    fun probeKeyedOpen_reachesNativeOpen_thenLeavesNoFileBehind() {
        // The provisioner loads the native library immediately before probing (probeKeyedOpen's
        // precondition, kept out of the probe so the load happens exactly once per open); mirror that here.
        DatabaseEncryption.ensureNativeLibraryLoaded()
        // On a compatible device this returns normally (keyed nativeOpen succeeds); on an incompatible one
        // it throws UnsatisfiedLinkError here — the exact #359 signature the provisioner now fails closed
        // on. Either way, no probe file may survive.
        DatabaseEncryption.probeKeyedOpen(cacheFile, passphrase)

        val dir = cacheFile.parentFile!!
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            assertFalse(
                "probe left a '$cacheName.openprobe$suffix' file behind",
                File(dir, "$cacheName.openprobe$suffix").exists(),
            )
        }
        // The probe uses a throwaway sibling, so it must never create the real cache file.
        assertFalse("probe must not create the real cache file", cacheFile.exists())
    }
}
