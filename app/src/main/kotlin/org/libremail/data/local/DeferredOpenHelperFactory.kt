// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * A [SupportSQLiteOpenHelper.Factory] that defers building the REAL open helper — and any blocking work
 * that choosing and creating it entails — from Room's build/inject path to the FIRST actual database
 * open (issue #93).
 *
 * Room calls [create] and [SupportSQLiteOpenHelper.setWriteAheadLoggingEnabled] while it builds the
 * database, on whichever thread injected it (possibly the main thread); neither may block. This factory
 * hands back a thin handle whose delegate is materialised only when the database is first opened
 * (`writableDatabase` / `readableDatabase`), which Room performs on its background query executor. The
 * [buildDelegate] lambda is where the caller runs the startup gate (see [DatabaseProvisioner]) and
 * picks the concrete factory — so all of that runs off the injection path and off the main thread.
 */
internal class DeferredOpenHelperFactory(
    private val buildDelegate: (SupportSQLiteOpenHelper.Configuration) -> SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        DeferredOpenHelper(configuration, buildDelegate)
}

/**
 * The lazy handle returned by [DeferredOpenHelperFactory]. Everything Room touches before the first
 * open is cheap; [buildDelegate] (which does the blocking work) runs only when [writableDatabase] or
 * [readableDatabase] is first read.
 */
private class DeferredOpenHelper(
    private val configuration: SupportSQLiteOpenHelper.Configuration,
    private val buildDelegate: (SupportSQLiteOpenHelper.Configuration) -> SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper {

    private val lock = Any()

    /** Guarded by [lock]. Null until the database is first opened — `create()` must stay non-blocking. */
    private var delegate: SupportSQLiteOpenHelper? = null

    /**
     * Guarded by [lock]. Room may set WAL before the first open; we remember the value and apply it when
     * the delegate is built, rather than building the delegate early (which would run the gate at inject
     * time). Null means "Room never asked", so the delegate keeps the real factory's own default.
     */
    private var writeAheadLoggingEnabled: Boolean? = null

    override val databaseName: String?
        get() = configuration.name

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        synchronized(lock) {
            writeAheadLoggingEnabled = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
        }
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = delegate().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase
        get() = delegate().readableDatabase

    override fun close() {
        // Never opened means nothing to close; do NOT build the delegate just to close it.
        synchronized(lock) { delegate?.close() }
    }

    private fun delegate(): SupportSQLiteOpenHelper = synchronized(lock) {
        delegate ?: buildDelegate(configuration).also { built ->
            writeAheadLoggingEnabled?.let(built::setWriteAheadLoggingEnabled)
            delegate = built
        }
    }
}
