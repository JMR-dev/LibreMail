// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

/**
 * Pure decision for whether onboarding should surface the battery opt-in step. Deliberately free of
 * Android types so it is exhaustively unit-testable; the live inputs are read by
 * [BatteryOptimizationManager] and [org.libremail.data.settings.SettingsRepository].
 */
object BatteryPromptDecision {

    /**
     * Prompt only when all three hold:
     * - [supported]: the platform exposes battery-optimization control (the Doze allowlist is API 23+,
     *   so this is always true at our minSdk — kept explicit so the rule stays correct if the floor
     *   ever drops).
     * - not [alreadyUnrestricted]: an app already exempt from battery optimization gains nothing.
     * - not [alreadyHandled]: the user has already seen and acted on (or dismissed) the prompt, so we
     *   don't nag on a later onboarding run.
     */
    fun shouldPrompt(supported: Boolean, alreadyUnrestricted: Boolean, alreadyHandled: Boolean): Boolean =
        supported && !alreadyUnrestricted && !alreadyHandled
}
