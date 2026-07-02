// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for the `firstResolvableOrElseLast` fallback-selection helper that backs
 * [BatteryOptimizationManager.settingsIntent] (#150). Deliberately exercised with plain `String`
 * candidates rather than real `Intent`s: `Intent`/`Uri`/`PackageManager` are unmocked Android SDK stubs
 * in a JVM unit test and throw on use, so the selection/ordering logic is kept generic and Android-free
 * specifically so it's testable here (see its KDoc). The real candidate intents and end-to-end
 * resolution are covered by the instrumented `BatteryOptimizationStepTest`.
 */
class BatteryOptimizationManagerTest {

    @Test
    fun `returns the first candidate that resolves`() {
        assertEquals("b", firstResolvableOrElseLast(listOf("a", "b", "c")) { it == "b" })
    }

    @Test
    fun `returns the only candidate when it is the first checked, even if it would not resolve`() {
        assertEquals("only", firstResolvableOrElseLast(listOf("only")) { it == "only" })
    }

    @Test
    fun `falls back to the last candidate when none resolve, rather than dead-ending`() {
        assertEquals("c", firstResolvableOrElseLast(listOf("a", "b", "c")) { false })
    }

    @Test
    fun `falls back to the last candidate even when it is the only one and it does not resolve`() {
        assertEquals("only", firstResolvableOrElseLast(listOf("only")) { false })
    }

    @Test
    fun `prefers an earlier resolving candidate over a later one, even if both resolve`() {
        assertEquals("first", firstResolvableOrElseLast(listOf("first", "second")) { true })
    }

    @Test
    fun `rejects an empty candidate list rather than silently returning nothing`() {
        assertFailsWith<IllegalArgumentException> {
            firstResolvableOrElseLast(emptyList<String>()) { true }
        }
    }
}
