// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.security

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassphraseSessionTest {

    @Test
    fun `starts locked`() {
        val session = PassphraseSession()
        assertFalse(session.isUnlocked())
        assertNull(session.current())
    }

    @Test
    fun `unlock stores and lock clears`() {
        val session = PassphraseSession()
        session.unlock("deadbeef")
        assertTrue(session.isUnlocked())
        assertEquals("deadbeef", session.current())
        session.lock()
        assertFalse(session.isUnlocked())
        assertNull(session.current())
    }

    @Test
    fun `await returns immediately when already unlocked`() = runTest {
        val session = PassphraseSession()
        session.unlock("cafe")
        assertEquals("cafe", session.await())
    }

    @Test
    fun `await suspends until unlocked`() = runTest {
        val session = PassphraseSession()
        val awaited = async { session.await() }
        yield()
        assertFalse(awaited.isCompleted) // still waiting
        session.unlock("f00d")
        assertEquals("f00d", awaited.await())
    }
}
