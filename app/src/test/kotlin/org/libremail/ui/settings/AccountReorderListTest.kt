// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import org.junit.Test
import org.libremail.domain.model.Account
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure reorder-index maths behind the Settings drag-to-reorder list (issue #164). */
class AccountReorderListTest {

    private val accounts = listOf("a", "b", "c").map { Account.outlook("$it@example.org") }
    private val ids get() = accounts.map { it.id }

    @Test
    fun `dragging the first row two slots down moves it to the end`() {
        val moved = commitDrag(accounts, id = ids[0], dragOffsetY = 200f, rowHeightPx = 100)
        assertEquals(listOf(ids[1], ids[2], ids[0]), moved?.map { it.id })
    }

    @Test
    fun `dragging the last row two slots up moves it to the front`() {
        val moved = commitDrag(accounts, id = ids[2], dragOffsetY = -200f, rowHeightPx = 100)
        assertEquals(listOf(ids[2], ids[0], ids[1]), moved?.map { it.id })
    }

    @Test
    fun `an offset under half a row height keeps the position`() {
        assertNull(commitDrag(accounts, id = ids[0], dragOffsetY = 40f, rowHeightPx = 100))
    }

    @Test
    fun `a drop dragged past the ends is clamped to a valid index`() {
        val moved = commitDrag(accounts, id = ids[0], dragOffsetY = 900f, rowHeightPx = 100)
        assertEquals(listOf(ids[1], ids[2], ids[0]), moved?.map { it.id })
    }

    @Test
    fun `an unmeasured row height cannot reorder`() {
        assertNull(commitDrag(accounts, id = ids[0], dragOffsetY = 200f, rowHeightPx = 0))
    }

    @Test
    fun `an unknown id cannot reorder`() {
        assertNull(commitDrag(accounts, id = "missing", dragOffsetY = 200f, rowHeightPx = 100))
    }
}
