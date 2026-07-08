// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import kotlinx.coroutines.CancellationException
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isCacheEncryptionUnavailable] is the shared classifier the headless entry points (WorkManager workers
 * and `IdleService`) use to decide whether a database-open failure is the issue-#359 "SQLCipher native
 * library unavailable" condition — which they defer/skip softly — versus any other failure, which they
 * handle normally. It must recognise the provisioner's [CacheEncryptionUnavailableException] AND a bare
 * [LinkageError] (defensive), even when wrapped several layers deep (coroutine stack-trace recovery
 * re-wraps the throwable across the open boundary), and reject everything else — including
 * [CancellationException], which must always propagate.
 */
class CacheEncryptionUnavailableExceptionTest {

    @Test
    fun `recognises a direct CacheEncryptionUnavailableException`() {
        val failure = CacheEncryptionUnavailableException(UnsatisfiedLinkError("nativeOpen"))
        assertTrue(failure.isCacheEncryptionUnavailable())
    }

    @Test
    fun `recognises a bare LinkageError`() {
        assertTrue(UnsatisfiedLinkError("SQLiteConnection.nativeOpen").isCacheEncryptionUnavailable())
    }

    @Test
    fun `recognises a CacheEncryptionUnavailableException wrapped deep in the cause chain`() {
        val wrapped = RuntimeException(
            "room open failed",
            IllegalStateException("delegate", CacheEncryptionUnavailableException(UnsatisfiedLinkError())),
        )
        assertTrue(wrapped.isCacheEncryptionUnavailable())
    }

    @Test
    fun `recognises a LinkageError nested as a cause`() {
        assertTrue(RuntimeException("open", UnsatisfiedLinkError("nativeOpen")).isCacheEncryptionUnavailable())
    }

    @Test
    fun `rejects an unrelated exception`() {
        assertFalse(IllegalStateException("database is locked").isCacheEncryptionUnavailable())
    }

    @Test
    fun `rejects a CancellationException so cancellation still propagates`() {
        assertFalse(CancellationException("job cancelled").isCacheEncryptionUnavailable())
    }
}
