// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.libremail.R

/**
 * Lightweight, dependency-free "Battery → Unrestricted" walkthrough shown on
 * [BatteryOptimizationScreen] before the user leaves for system settings (#174). It is the first
 * animation in the app, so the approach was chosen to add **no** new dependency (no Lottie, no
 * `AnimatedVectorDrawable`): a stylized Compose illustration driven by [rememberInfiniteTransition].
 *
 * The visual is deliberately **generic** — a faux settings card with a "Battery" row (tap it) and an
 * "Unrestricted" option (choose it), not a screen recording of any one OEM's real UI, which varies by
 * manufacturer (#150) and would look wrong or go stale on most devices. A looping highlight moves from
 * the Battery row to the Unrestricted option while a "tap" dot pulses, illustrating the two-step path.
 *
 * Accessibility (all required by #174):
 * - **Reduced motion:** when the system "Remove animations" setting is on ([rememberReducedMotion]),
 *   the same card renders **at rest** (no infinite transition) — a static illustration of the end
 *   state instead of movement.
 * - **TalkBack:** the whole illustration exposes a single [contentDescription] (its decorative inner
 *   labels are cleared), mirroring the on-screen `onboarding_battery_guidance` text so screen-reader
 *   users get the same steps. The animation is additive — the guidance text always stays on screen.
 *
 * @param reducedMotion when true, render the static (motionless) variant. Defaults to the live system
 *   setting; overridable so tests can drive either path deterministically.
 */
@Composable
fun BatteryGuideAnimation(modifier: Modifier = Modifier, reducedMotion: Boolean = rememberReducedMotion()) {
    val description = stringResource(R.string.onboarding_battery_animation_description)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 360.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (reducedMotion) {
            // Static fallback: the end state at rest — "Battery ›" then "Unrestricted ✓", no motion.
            GuideCard(focusUnrestricted = true, tapAlpha = 0f)
        } else {
            val transition = rememberInfiniteTransition(label = "batteryGuide")
            // 0f..1f highlights the Battery row; 1f..2f highlights the Unrestricted option, then loops.
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 3600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "phase",
            )
            // A gentle pulse for the "tap here" dot so the guide never looks frozen.
            val tapAlpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "tap",
            )
            GuideCard(focusUnrestricted = phase >= 1f, tapAlpha = tapAlpha)
        }
    }
}

/**
 * Reads the system "animation duration scale" once and reports whether animations are effectively
 * off (scale 0 — the "Remove animations" accessibility setting, or a battery-saver / test harness
 * that disables them). Callers use it to skip motion in favour of a static illustration.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { isReducedMotion(context) }
}

/** Non-composable core of [rememberReducedMotion], split out so it is unit-testable without Compose. */
internal fun isReducedMotion(context: Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        ANIMATIONS_ENABLED_SCALE,
    )
    return scale == NO_ANIMATION_SCALE
}

/**
 * The faux settings card: a decorative header pill above the "Battery" row and the "Unrestricted"
 * option. [focusUnrestricted] moves the highlight/selection from the first row to the second (the
 * choice being demonstrated); [tapAlpha] drives the pulsing "tap here" dot on the focused row.
 */
@Composable
private fun GuideCard(focusUnrestricted: Boolean, tapAlpha: Float) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Decorative "screen title" pill — hints "a system settings screen" without naming an OEM.
            Box(
                Modifier
                    .width(96.dp)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
            )
            GuideRow(
                label = stringResource(R.string.onboarding_battery_anim_battery),
                highlighted = !focusUnrestricted,
                tapAlpha = if (focusUnrestricted) 0f else tapAlpha,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GuideRow(
                label = stringResource(R.string.onboarding_battery_anim_unrestricted),
                highlighted = focusUnrestricted,
                tapAlpha = if (focusUnrestricted) tapAlpha else 0f,
            ) {
                if (focusUnrestricted) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Box(
                        Modifier
                            .size(20.dp)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * One row of the faux settings list: a generic leading glyph (a dependency-free stand-in for an OEM
 * setting icon), the [label], a pulsing "tap here" dot (via [tapAlpha]) and a caller-supplied
 * [trailing] affordance (a chevron for "opens a sub-screen", a check/radio for "selectable option").
 */
@Composable
private fun GuideRow(label: String, highlighted: Boolean, tapAlpha: Float, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (highlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(12.dp)
                .alpha(tapAlpha)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        )
        trailing()
    }
}

// Animation-scale sentinels for isReducedMotion (kept as named constants so detekt's MagicNumber rule
// — which is not relaxed for this non-@Composable helper — stays satisfied).
private const val ANIMATIONS_ENABLED_SCALE = 1f
private const val NO_ANIMATION_SCALE = 0f
