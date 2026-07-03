// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun report(id: String, createdAt: Long = 1L, kind: ReportKind = ReportKind.MANUAL) = DebugReport(
        id = id,
        createdAtMillis = createdAt,
        kind = kind,
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel",
        stackTrace = null,
        settings = emptyMap(),
        logs = emptyList(),
    )

    @Test
    fun `save then find and list`() {
        val store = ReportStore(tempFolder.root)

        store.save(report("a"))

        assertEquals("a", store.find("a")?.id)
        assertEquals(listOf("a"), store.reports.value.map { it.id })
    }

    @Test
    fun `lists newest first`() {
        val store = ReportStore(tempFolder.root)

        store.save(report("old", createdAt = 1L))
        store.save(report("new", createdAt = 2L))

        assertEquals(listOf("new", "old"), store.reports.value.map { it.id })
    }

    @Test
    fun `delete removes the report`() {
        val store = ReportStore(tempFolder.root)
        store.save(report("a"))

        store.delete("a")

        assertNull(store.find("a"))
        assertTrue(store.reports.value.isEmpty())
    }

    @Test
    fun `survives a fresh instance over the same directory (next launch)`() {
        ReportStore(tempFolder.root).save(report("persisted"))

        val reopened = ReportStore(tempFolder.root)

        assertEquals("persisted", reopened.find("persisted")?.id)
    }

    @Test
    fun `ignores unparseable files`() {
        File(tempFolder.root, "garbage.json").writeText("not json at all")
        val store = ReportStore(tempFolder.root)

        store.save(report("valid"))

        assertEquals(listOf("valid"), store.reports.value.map { it.id })
    }

    @Test
    fun `purgeOlderThan deletes reports strictly older than the cutoff`() {
        val store = ReportStore(tempFolder.root)
        store.save(report("old", createdAt = 1_000L))
        store.save(report("boundary", createdAt = 3_000L))
        store.save(report("recent", createdAt = 5_000L))

        val purged = store.purgeOlderThan(cutoffMillis = 3_000L)

        assertEquals(1, purged)
        assertNull(store.find("old"))
        // A report exactly at the cutoff is kept (strictly-older purge); list stays newest-first.
        assertEquals(listOf("recent", "boundary"), store.reports.value.map { it.id })
    }
}
