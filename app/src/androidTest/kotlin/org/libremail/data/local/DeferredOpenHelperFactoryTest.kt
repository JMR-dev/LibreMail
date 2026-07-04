// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavior of [DeferredOpenHelperFactory] (issue #93): Room touches `create` and
 * `setWriteAheadLoggingEnabled` on the injection path (possibly the main thread), so the REAL open
 * helper — and the blocking startup gate that builds it — must be deferred until the database is first
 * actually opened. These tests pin that laziness, the WAL memoization, and the "never open just to
 * close" contract using a recording fake delegate (no real database needed).
 */
@RunWith(AndroidJUnit4::class)
class DeferredOpenHelperFactoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class FakeOpenHelper(private val db: SupportSQLiteDatabase) : SupportSQLiteOpenHelper {
        var walEnabled: Boolean? = null
        var closed = false
        override val databaseName: String = "fake"
        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            walEnabled = enabled
        }

        override val writableDatabase: SupportSQLiteDatabase get() = db
        override val readableDatabase: SupportSQLiteDatabase get() = db
        override fun close() {
            closed = true
        }
    }

    private fun configuration(name: String): SupportSQLiteOpenHelper.Configuration =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

    @Test
    fun theDelegateIsBuiltLazilyOnFirstOpenAndThenMemoized() {
        var builds = 0
        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val fake = FakeOpenHelper(db)
        val helper = DeferredOpenHelperFactory {
            builds++
            fake
        }.create(configuration("deferred-lazy"))

        // create() must not build the real delegate (it runs on the possibly-main injection thread).
        assertEquals("create() must not build the delegate", 0, builds)
        assertEquals("deferred-lazy", helper.databaseName)

        // WAL can be set before the first open; it must be remembered, not force an early build.
        helper.setWriteAheadLoggingEnabled(true)
        assertEquals("setWriteAheadLoggingEnabled must not build the delegate", 0, builds)

        // The first open builds the delegate and applies the remembered WAL setting.
        assertSame(db, helper.writableDatabase)
        assertEquals(1, builds)
        assertEquals("the remembered WAL flag is applied when the delegate is built", true, fake.walEnabled)

        // Subsequent opens reuse the same delegate.
        assertSame(db, helper.readableDatabase)
        assertEquals("the delegate is memoized after the first open", 1, builds)
    }

    @Test
    fun closeBeforeAnyOpenNeverBuildsTheDelegate() {
        var builds = 0
        val fake = FakeOpenHelper(mockk(relaxed = true))
        val helper = DeferredOpenHelperFactory {
            builds++
            fake
        }.create(configuration("deferred-close"))

        helper.close()

        assertEquals("close() must not build a delegate just to close it", 0, builds)
        assertFalse("a never-built delegate is never closed", fake.closed)
    }
}
