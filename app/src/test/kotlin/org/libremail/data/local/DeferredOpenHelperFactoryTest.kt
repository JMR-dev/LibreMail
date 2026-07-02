// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * [DeferredOpenHelperFactory] is the seam that keeps the blocking startup gate off Room's build/inject
 * path (issue #93): the operations Room performs while BUILDING the database — `create()` and
 * `setWriteAheadLoggingEnabled()` — must not touch the real delegate, and therefore must not run the
 * gate. The delegate materialises only when the database is first OPENED (`writableDatabase` /
 * `readableDatabase`), which Room does on its background query executor.
 */
class DeferredOpenHelperFactoryTest {

    private fun configuration(name: String? = "test.db"): SupportSQLiteOpenHelper.Configuration {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        return SupportSQLiteOpenHelper.Configuration.builder(mockk<Context>(relaxed = true))
            .name(name)
            .callback(callback)
            .build()
    }

    @Test
    fun `create and the build-time configuration calls never run the deferred gate`() {
        val builds = AtomicInteger(0)
        val factory = DeferredOpenHelperFactory {
            builds.incrementAndGet()
            mockk<SupportSQLiteOpenHelper>(relaxed = true)
        }

        val helper = factory.create(configuration(name = "libremail.db"))
        // Everything Room touches while building the database must stay cheap.
        assertEquals("libremail.db", helper.databaseName)
        helper.setWriteAheadLoggingEnabled(true)
        helper.setWriteAheadLoggingEnabled(false)

        assertEquals(0, builds.get(), "building the database must not run the deferred startup gate")
    }

    @Test
    fun `the delegate is built only on first open and then reused`() {
        val builds = AtomicInteger(0)
        val delegate = mockk<SupportSQLiteOpenHelper>(relaxed = true)
        val factory = DeferredOpenHelperFactory {
            builds.incrementAndGet()
            delegate
        }
        val helper = factory.create(configuration())
        assertEquals(0, builds.get())

        // The first open materialises the delegate (and runs the gate exactly once)...
        helper.writableDatabase
        assertEquals(1, builds.get())
        // ...and every later access reuses it, never re-running the gate.
        helper.writableDatabase
        helper.readableDatabase
        assertEquals(1, builds.get())
    }

    @Test
    fun `a WAL setting made before the first open is applied when the delegate is built`() {
        val delegate = mockk<SupportSQLiteOpenHelper>(relaxed = true)
        val factory = DeferredOpenHelperFactory { delegate }
        val helper = factory.create(configuration())

        helper.setWriteAheadLoggingEnabled(true) // recorded, not forwarded — there is no delegate yet
        verify(exactly = 0) { delegate.setWriteAheadLoggingEnabled(any()) }

        helper.writableDatabase // builds the delegate
        verify(exactly = 1) { delegate.setWriteAheadLoggingEnabled(true) }
    }

    @Test
    fun `close before any open is a no-op that never builds the delegate`() {
        val builds = AtomicInteger(0)
        val factory = DeferredOpenHelperFactory {
            builds.incrementAndGet()
            mockk<SupportSQLiteOpenHelper>(relaxed = true)
        }

        factory.create(configuration()).close()

        assertEquals(0, builds.get(), "closing a never-opened helper must not build the delegate")
    }

    @Test
    fun `writableDatabase delegates to the built helper`() {
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val delegate = mockk<SupportSQLiteOpenHelper>(relaxed = true)
        every { delegate.writableDatabase } returns db
        val factory = DeferredOpenHelperFactory { delegate }

        assertSame(db, factory.create(configuration()).writableDatabase)
    }
}
