// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.Folder
import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.Store
import jakarta.mail.StoreClosedException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The connection-reuse cache (issue #125 spike): one authenticated [Store] per account behind a
 * mutex, established lazily and kept open. These tests pin the reuse guarantee and the lazy
 * catch-and-retry-once stale handling — a dropped connection is rebuilt and the op retried, a second
 * failure clears the slot, and a genuine protocol error is propagated without ever reconnecting.
 */
class ImapConnectionCacheTest {

    private val params = ImapConnectionParams(
        host = "127.0.0.1",
        port = 143,
        security = MailSecurity.NONE,
        username = "alice@example.org",
        secret = "secret",
        useXoauth2 = false,
    )

    private var connects = 0

    /** A cache whose connect step counts calls and returns [supply] (a fresh relaxed [Store] by default). */
    private fun cache(supply: () -> Store = { mockk(relaxed = true) }) = ImapConnectionCache {
        connects++
        supply()
    }

    @Test
    fun `establishes one connection and reuses it across calls`() = runTest {
        val cache = cache()

        assertEquals("a", cache.withStore(params) { "a" })
        assertEquals("b", cache.withStore(params) { "b" })

        assertEquals(1, connects, "the second op reuses the first connection")
    }

    @Test
    fun `rebuilds the socket once and retries when the connection drops`() = runTest {
        val opened = mutableListOf<Store>()
        val cache = cache {
            mockk<Store>(relaxed = true).also { opened += it }
        }
        var attempts = 0

        val result = cache.withStore(params) {
            attempts++
            if (attempts == 1) throw IOException("dropped") else "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(2, connects, "a dropped connection is rebuilt exactly once")
        verify { opened[0].close() } // the stale socket is torn down before reconnecting
    }

    @Test
    fun `a second failure after reconnect clears the slot so the next call reconnects`() = runTest {
        val cache = cache()

        assertFailsWith<IOException> { cache.withStore(params) { throw IOException("still down") } }
        assertEquals(2, connects, "initial connect plus one rebuild")

        cache.withStore(params) { "ok" }
        assertEquals(3, connects, "the cleared slot forces a fresh connect")
    }

    @Test
    fun `a genuine protocol error is propagated without reconnecting`() = runTest {
        val cache = cache()

        assertFailsWith<IllegalStateException> {
            cache.withStore(params) { throw IllegalStateException("bad login") }
        }

        assertEquals(1, connects, "a non-drop error must not trigger a reconnect")
    }

    @Test
    fun `folder-closed, store-closed, IO and IO-caused messaging errors all count as drops`() = runTest {
        retriesOn(FolderClosedException(mockk<Folder>(relaxed = true)))
        retriesOn(StoreClosedException(mockk<Store>(relaxed = true)))
        retriesOn(IOException("socket"))
        retriesOn(MessagingException("wrapped", IOException("socket")))
    }

    @Test
    fun `a messaging error without an IO cause is not a drop`() = runTest {
        val cache = cache()

        assertFailsWith<MessagingException> {
            cache.withStore(params) { throw MessagingException("server said no") }
        }

        assertEquals(1, connects)
    }

    @Test
    fun `closeAll tears down and forgets every cached connection`() = runTest {
        val store = mockk<Store>(relaxed = true)
        val cache = cache { store }

        cache.withStore(params) { "a" }
        cache.closeAll()

        verify { store.close() }
        cache.withStore(params) { "b" }
        assertEquals(2, connects, "after closeAll the next op reconnects")
    }

    /** Asserts [error] is treated as a dropped connection: rebuilt once, the retry succeeds. */
    private suspend fun retriesOn(error: Throwable) {
        connects = 0
        val cache = cache()
        var attempts = 0

        val result = cache.withStore(params) {
            attempts++
            if (attempts == 1) throw error else "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, connects, "${error.javaClass.simpleName} should have been retried on a fresh socket")
    }
}
