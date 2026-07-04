// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit-level coverage for [copyReportPayloadToClipboard] (#237: `LocalClipboardManager`/
 * `ClipboardManager` — deprecated in favor of the suspend `LocalClipboard`/[Clipboard] API — were
 * migrated off of here). The "Copy report" action's clipboard interaction is exercised directly
 * against a mocked [Clipboard], with [ClipData]'s static factory mocked the same way
 * [org.libremail.contacts.ContactsPermissionManagerTest] mocks `Uri` — so this runs as a plain JVM
 * unit test, no Compose UI test or emulator needed.
 */
class ReportReviewClipboardTest {

    private val clipboard = mockk<Clipboard>()
    private val clipData = mockk<ClipData>()
    private val textSlot = slot<CharSequence>()

    @Before
    fun setUp() {
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), capture(textSlot)) } returns clipData
        coEvery { clipboard.setClipEntry(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkStatic(ClipData::class)
    }

    @Test
    fun `copying the payload builds a plain-text clip from the exact text and sets it`() = runTest {
        copyReportPayloadToClipboard(clipboard, "the report body")

        assertEquals("the report body", textSlot.captured.toString())
        coVerify(exactly = 1) { clipboard.setClipEntry(match { it.clipData === clipData }) }
    }
}
