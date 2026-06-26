// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sample

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class SampleDataTest {

    @Test
    fun `byId returns a known sample message`() {
        val message = SampleData.byId("sample-1")
        assertNotNull(message)
        assertEquals("sample-1", message.id)
    }

    @Test
    fun `byId returns null for an unknown id`() {
        assertNull(SampleData.byId("does-not-exist"))
    }
}
