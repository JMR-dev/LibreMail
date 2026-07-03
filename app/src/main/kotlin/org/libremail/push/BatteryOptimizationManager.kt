// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and deep-links this app's system battery-optimization state, so the user can move LibreMail
 * to "Unrestricted" and keep [IdleService]'s push connection (and periodic sync) from being throttled
 * or torn down by Doze.
 *
 * We deliberately do NOT use the restricted `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission or its
 * one-tap dialog: Google Play limits that permission to an approved set of use cases (rejection risk,
 * see #17). Sending the user to a system screen instead needs no extra permission and is safe on both
 * Play and F-Droid (#16).
 *
 * ### Which screen, and why (#150)
 * The onboarding goal is to land as close as possible to the per-app "Unrestricted / Optimized /
 * Restricted" screen. Of the `Settings` actions that are both public (part of the SDK, not `@hide`)
 * and don't require the restricted permission above, none opens that exact screen for a specific
 * package — confirmed against the AOSP `Settings` source, not just the reference docs:
 * - [Settings.ACTION_APPLICATION_DETAILS_SETTINGS], scoped to our package, is the closest safe option:
 *   on stock Android/Pixel/AOSP it lands one tap ("Battery") away from the target screen. This is
 *   [settingsIntent]'s primary candidate, unchanged from before this investigation.
 * - [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] is public and needs no permission, but is
 *   *not* package-scoped: it opens the system-wide "Battery Optimization" app list (defaulting to a
 *   filter that hides already-optimized apps, so the user must switch it to "All apps" to even find
 *   LibreMail), and tapping an app there shows a legacy two-state Optimize/"Don't optimize" dialog that
 *   predates the "Restricted" option. That is a worse landing than app-details for one known app, so
 *   it is used only as a fallback for the rare case app-details itself doesn't resolve.
 * - The one action that *would* jump straight to the target, `ACTION_VIEW_ADVANCED_POWER_USAGE_DETAIL`,
 *   is `@hide` in the platform source — not part of the public SDK. Using its literal string would
 *   mean depending on a private, unversioned implementation detail, exactly the fragility this class
 *   already avoids for the restricted-permission route, so it is out for the same reason.
 *
 * OEM skins (Samsung, Xiaomi, etc.) may rename, relocate, or move this control into a manufacturer
 * settings app entirely. There is no public intent for those short of hardcoding fragile,
 * version-specific OEM component names, so devices without a "Battery" entry on the app-details page
 * simply keep the app-details landing rather than this class reaching for one.
 *
 * Note [isIgnoringBatteryOptimizations] reflects the Doze allowlist: it is `true` only for the
 * "Unrestricted" setting and `false` for *both* "Optimized" and "Restricted", so it cannot single out
 * the (most harmful) "Restricted" state on its own.
 */
@Singleton
class BatteryOptimizationManager @Inject constructor(@ApplicationContext private val context: Context) {
    /**
     * Whether the platform exposes battery-optimization control. The Doze allowlist has existed since
     * API 23, so at our minSdk (29) this is always true; the version check keeps [BatteryPromptDecision]
     * honest if the floor ever drops below API 23.
     */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** True when this app is currently exempt from battery optimization ("Unrestricted"). */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Best-effort deep link toward the per-app battery-optimization screen (see the class KDoc for the
     * rationale and OEM caveats). Returns the first of [candidateIntents] that resolves an activity on
     * this device, falling back to the last candidate — today, app-details, resolvable since API 9 —
     * so the caller always gets an intent that lands somewhere useful instead of a dead end.
     */
    fun settingsIntent(): Intent =
        firstResolvableOrElseLast(candidateIntents()) { it.resolveActivity(context.packageManager) != null }

    /**
     * Candidate settings screens, most specific/direct first. `internal` (rather than private) so the
     * candidate list itself — actions, order, and the package scoping — is directly checkable from a
     * test. See the class KDoc for why each candidate is here, in this order.
     */
    internal fun candidateIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
}

/**
 * Returns the first element of [candidates] for which [resolves] holds, or the last element if none do
 * — so a caller building a best-effort fallback chain always gets something usable rather than `null`.
 * Top-level and `internal` (rather than folded into [BatteryOptimizationManager.settingsIntent]) so
 * this selection/fallback logic is exhaustively unit-testable without touching real Android intent
 * resolution, the same reasoning as [BatteryPromptDecision].
 *
 * @param candidates must be non-empty, ordered most-preferred first.
 */
internal fun <T> firstResolvableOrElseLast(candidates: List<T>, resolves: (T) -> Boolean): T {
    require(candidates.isNotEmpty()) { "candidates must not be empty" }
    return candidates.firstOrNull(resolves) ?: candidates.last()
}
