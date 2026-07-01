// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryPromptDecisionTest {

    @Test
    fun `prompts when supported, not already unrestricted, and not yet handled`() {
        assertTrue(
            BatteryPromptDecision.shouldPrompt(
                supported = true,
                alreadyUnrestricted = false,
                alreadyHandled = false,
            ),
        )
    }

    @Test
    fun `never prompts when the app is already unrestricted`() {
        assertFalse(
            BatteryPromptDecision.shouldPrompt(supported = true, alreadyUnrestricted = true, alreadyHandled = false),
        )
    }

    @Test
    fun `never prompts once the user has handled it`() {
        assertFalse(
            BatteryPromptDecision.shouldPrompt(supported = true, alreadyUnrestricted = false, alreadyHandled = true),
        )
    }

    @Test
    fun `never prompts on an unsupported platform, regardless of the other inputs`() {
        for (unrestricted in listOf(false, true)) {
            for (handled in listOf(false, true)) {
                assertFalse(
                    BatteryPromptDecision.shouldPrompt(
                        supported = false,
                        alreadyUnrestricted = unrestricted,
                        alreadyHandled = handled,
                    ),
                    "supported=false must never prompt (unrestricted=$unrestricted, handled=$handled)",
                )
            }
        }
    }
}
