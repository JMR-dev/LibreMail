// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReportStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Runs the initial scan inline (Unconfined dispatches eagerly on the calling thread) so these
    // tests observe a fully-populated store immediately, as before the scan moved off-thread (#296).
    // The dedicated `seeds empty` test below verifies the real off-thread, empty-seed behaviour.
    private fun newStore() = ReportStore(tempFolder.root, CoroutineScope(Dispatchers.Unconfined))

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
        val store = newStore()

        store.save(report("a"))

        assertEquals("a", store.find("a")?.id)
        assertEquals(listOf("a"), store.reports.value.map { it.id })
    }

    @Test
    fun `lists newest first`() {
        val store = newStore()

        store.save(report("old", createdAt = 1L))
        store.save(report("new", createdAt = 2L))

        assertEquals(listOf("new", "old"), store.reports.value.map { it.id })
    }

    @Test
    fun `delete removes the report`() {
        val store = newStore()
        store.save(report("a"))

        store.delete("a")

        assertNull(store.find("a"))
        assertTrue(store.reports.value.isEmpty())
    }

    @Test
    fun `survives a fresh instance over the same directory (next launch)`() {
        newStore().save(report("persisted"))

        val reopened = newStore()

        assertEquals("persisted", reopened.find("persisted")?.id)
    }

    @Test
    fun `markSurfaced flags the report and persists across a fresh instance`() {
        val store = newStore()
        store.save(report("a"))

        store.markSurfaced("a")

        assertTrue(store.find("a")!!.surfaced)
        // Survives a fresh instance over the same directory (the next launch reads it as surfaced).
        assertTrue(newStore().find("a")!!.surfaced)
    }

    @Test
    fun `markSurfaced is a no-op for a missing report`() {
        val store = newStore()

        store.markSurfaced("missing")

        assertTrue(store.reports.value.isEmpty())
    }

    @Test
    fun `ignores unparseable files`() {
        File(tempFolder.root, "garbage.json").writeText("not json at all")
        val store = newStore()

        store.save(report("valid"))

        assertEquals(listOf("valid"), store.reports.value.map { it.id })
    }

    @Test
    fun `purgeOlderThan deletes reports strictly older than the cutoff`() {
        val store = newStore()
        store.save(report("old", createdAt = 1_000L))
        store.save(report("boundary", createdAt = 3_000L))
        store.save(report("recent", createdAt = 5_000L))

        val purged = store.purgeOlderThan(cutoffMillis = 3_000L)

        assertEquals(1, purged)
        assertNull(store.find("old"))
        // A report exactly at the cutoff is kept (strictly-older purge); list stays newest-first.
        assertEquals(listOf("recent", "boundary"), store.reports.value.map { it.id })
    }

    @Test
    fun `seeds the flow empty and runs the initial scan off-thread`() {
        // A report already on disk from a previous launch, written directly (not via the flow).
        File(tempFolder.root, "persisted.json").writeText(report("persisted").toStorageJson())

        // A StandardTestDispatcher queues the launched scan instead of running it inline, so the
        // window between construction and the scan landing is observable.
        val scheduler = TestCoroutineScheduler()
        val store = ReportStore(tempFolder.root, CoroutineScope(StandardTestDispatcher(scheduler)))

        // The constructor did NOT scan on the calling thread: the flow is seeded empty (#296).
        assertTrue(store.reports.value.isEmpty())

        // Running the queued work performs the scan off-thread and populates the flow.
        scheduler.advanceUntilIdle()
        assertEquals(listOf("persisted"), store.reports.value.map { it.id })
    }
}
