// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import jakarta.mail.Folder
import jakarta.mail.FolderClosedException
import jakarta.mail.MessagingException
import jakarta.mail.Store
import jakarta.mail.StoreClosedException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.libremail.domain.model.ImapConnectionParams
import org.libremail.domain.model.MailSecurity
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The connection-reuse cache (issue #357 Part 2, wiring the #125 spike): one authenticated [Store] per
 * account behind a mutex, established lazily and kept open. These tests pin the reuse guarantee, the
 * lazy catch-and-retry-once stale handling — a dropped connection is rebuilt and the op retried, a
 * second failure clears the slot, and a genuine protocol error is propagated without ever reconnecting
 * — plus idle eviction (a connection unused past the timeout is closed) and teardown.
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

    /** Injected monotonic clock (nanos) so idle eviction is deterministic; advanced by the test. */
    private var nowNanos = 0L

    @Before
    fun setUp() {
        // The cache breadcrumbs through AppLog, which forwards to android.util.Log — a throwing no-op
        // stub under plain JVM tests. Mock it class-wide (fully qualified, so this file never imports
        // android.util.Log) so no test crashes on the unmocked method.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.d(any(), any(), any()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    /** A cache whose connect step counts calls and returns [supply] (a fresh relaxed [Store] by default). */
    private fun cache(idleTimeoutMillis: Long = IDLE_TIMEOUT_MS, supply: () -> Store = { mockk(relaxed = true) }) =
        ImapConnectionCache(
            connect = {
                connects++
                supply()
            },
            idleTimeoutMillis = idleTimeoutMillis,
            nowNanos = { nowNanos },
        )

    @Test
    fun `establishes one connection and reuses it across calls`() = runTest {
        val cache = cache()

        assertEquals("a", cache.withStore(params, op = "test") { "a" })
        assertEquals("b", cache.withStore(params, op = "test") { "b" })

        assertEquals(1, connects, "the second op reuses the first connection")
    }

    @Test
    fun `rebuilds the socket once and retries when the connection drops`() = runTest {
        val opened = mutableListOf<Store>()
        val cache = cache {
            mockk<Store>(relaxed = true).also { opened += it }
        }
        var attempts = 0

        val result = cache.withStore(params, op = "test") {
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

        assertFailsWith<IOException> { cache.withStore(params, op = "test") { throw IOException("still down") } }
        assertEquals(2, connects, "initial connect plus one rebuild")

        cache.withStore(params, op = "test") { "ok" }
        assertEquals(3, connects, "the cleared slot forces a fresh connect")
    }

    @Test
    fun `a genuine protocol error is propagated without reconnecting`() = runTest {
        val cache = cache()

        assertFailsWith<IllegalStateException> {
            cache.withStore(params, op = "test") { throw IllegalStateException("bad login") }
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
            cache.withStore(params, op = "test") { throw MessagingException("server said no") }
        }

        assertEquals(1, connects)
    }

    @Test
    fun `evictIdle closes a connection idle past the timeout but keeps a fresh one`() = runTest {
        val store = mockk<Store>(relaxed = true)
        val cache = cache(idleTimeoutMillis = IDLE_TIMEOUT_MS) { store }

        nowNanos = 0L
        cache.withStore(params, op = "test") { "a" } // establish; lastUsed = 0

        // Still within the timeout: not yet idle -> kept.
        nowNanos = (IDLE_TIMEOUT_MS - 1) * NANOS_PER_MS
        cache.evictIdle()
        verify(exactly = 0) { store.close() }

        // Idle past the timeout -> evicted, and the next op reconnects.
        nowNanos = IDLE_TIMEOUT_MS * NANOS_PER_MS
        cache.evictIdle()
        verify(exactly = 1) { store.close() }

        cache.withStore(params, op = "test") { "b" }
        assertEquals(2, connects, "after idle eviction the next op reconnects")
    }

    @Test
    fun `closeAll tears down and forgets every cached connection`() = runTest {
        val store = mockk<Store>(relaxed = true)
        val cache = cache { store }

        cache.withStore(params, op = "test") { "a" }
        cache.closeAll()

        verify { store.close() }
        cache.withStore(params, op = "test") { "b" }
        assertEquals(2, connects, "after closeAll the next op reconnects")
    }

    /** Asserts [error] is treated as a dropped connection: rebuilt once, the retry succeeds. */
    private suspend fun retriesOn(error: Throwable) {
        connects = 0
        val cache = cache()
        var attempts = 0

        val result = cache.withStore(params, op = "test") {
            attempts++
            if (attempts == 1) throw error else "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, connects, "${error.javaClass.simpleName} should have been retried on a fresh socket")
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 60_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
