// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.settings

import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetentionPolicyTest {

    @Test
    fun `per-account override wins over the global default per dimension`() {
        val policy = RetentionPolicy.resolve(
            accountCount = 500,
            accountMonths = null,
            defaultCount = 1000,
            defaultMonths = 6,
        )
        assertEquals(500, policy.count) // account override
        assertEquals(6, policy.months) // inherited default
    }

    @Test
    fun `null overrides inherit the global default`() {
        val policy = RetentionPolicy.resolve(null, null, defaultCount = 1000, defaultMonths = 12)
        assertEquals(1000, policy.count)
        assertEquals(12, policy.months)
    }

    @Test
    fun `an explicit account zero overrides a non-zero global default back to unlimited`() {
        val policy =
            RetentionPolicy.resolve(accountCount = 0, accountMonths = 0, defaultCount = 1000, defaultMonths = 6)
        assertTrue(policy.isUnlimited)
        assertNull(policy.countLimit)
    }

    @Test
    fun `keep-everything default is unlimited and has no cutoffs`() {
        val policy = RetentionPolicy.KEEP_EVERYTHING
        assertTrue(policy.isUnlimited)
        assertNull(policy.countLimit)
        assertNull(policy.ageCutoffMillis(System.currentTimeMillis()))
    }

    @Test
    fun `count limit is exposed only when positive`() {
        assertEquals(500, RetentionPolicy(count = 500, months = 0).countLimit)
        assertNull(RetentionPolicy(count = 0, months = 0).countLimit)
        assertFalse(RetentionPolicy(count = 500, months = 0).isUnlimited)
    }

    @Test
    fun `age cutoff is N calendar months before now`() {
        val now = Instant.parse("2026-06-30T00:00:00Z").toEpochMilli()
        val cutoff = RetentionPolicy(count = 0, months = 3).ageCutoffMillis(now)
        val expected = Instant.parse("2026-06-30T00:00:00Z")
            .atZone(ZoneOffset.UTC).minusMonths(3).toInstant().toEpochMilli()
        assertEquals(expected, cutoff)
        // Sanity: the cutoff really is in the past.
        assertTrue(cutoff!! < now)
    }

    @Test
    fun `negative inputs are clamped to unlimited`() {
        val policy = RetentionPolicy.resolve(accountCount = -5, accountMonths = -1, defaultCount = 0, defaultMonths = 0)
        assertEquals(0, policy.count)
        assertEquals(0, policy.months)
        assertTrue(policy.isUnlimited)
    }
}
