// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.entity.FolderEntity

/**
 * Real-SQLite behavior of [FolderDao] not already covered by [LibreMailDatabaseTest] (which pins
 * `observeForAccount` ordering + `replaceForAccount`): the one-shot [FolderDao.getForAccountOnce]
 * read, REPLACE-on-conflict for a re-listed folder, and the account scoping of a delete.
 */
@RunWith(AndroidJUnit4::class)
class FolderDaoTest {

    private lateinit var db: LibreMailDatabase
    private lateinit var dao: FolderDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibreMailDatabase::class.java).build()
        dao = db.folderDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun getForAccountOnceReturnsFoldersOrderedBySortOrder() = runBlocking {
        dao.insertAll(
            listOf(
                FolderEntity("acct", "Archive", "Archive", "ARCHIVE", selectable = true, sortOrder = 2),
                FolderEntity("acct", "INBOX", "INBOX", "INBOX", selectable = true, sortOrder = 0),
                FolderEntity("acct", "Sent", "Sent", "SENT", selectable = true, sortOrder = 1),
            ),
        )

        assertEquals(listOf("INBOX", "Sent", "Archive"), dao.getForAccountOnce("acct").map { it.fullName })
    }

    @Test
    fun insertAllReplacesAFolderWithTheSamePrimaryKey() = runBlocking {
        dao.insertAll(
            listOf(FolderEntity("acct", "INBOX", "Old label", "INBOX", selectable = true, sortOrder = 0)),
        )

        // A re-list of INBOX (same accountId + fullName) replaces the cached row.
        dao.insertAll(
            listOf(FolderEntity("acct", "INBOX", "New label", "INBOX", selectable = true, sortOrder = 0)),
        )

        val folders = dao.getForAccountOnce("acct")
        assertEquals(1, folders.size)
        assertEquals("New label", folders.single().displayName)
    }

    @Test
    fun deleteForAccountRemovesOnlyThatAccountsFolders() = runBlocking {
        dao.insertAll(listOf(FolderEntity("acct", "INBOX", "INBOX", "INBOX", selectable = true, sortOrder = 0)))
        dao.insertAll(listOf(FolderEntity("acct2", "INBOX", "INBOX", "INBOX", selectable = true, sortOrder = 0)))

        dao.deleteForAccount("acct")

        assertTrue(dao.getForAccountOnce("acct").isEmpty())
        assertEquals(listOf("INBOX"), dao.getForAccountOnce("acct2").map { it.fullName })
    }
}
