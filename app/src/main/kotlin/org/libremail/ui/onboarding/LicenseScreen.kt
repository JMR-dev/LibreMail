// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.libremail.R

/**
 * First screen of onboarding (#172): the user must scroll through the full GPL-3.0 license text and
 * tap Agree before reaching [OnboardingWelcomeScreen] or anything else in the app; Decline exits
 * instead. This is the onboarding graph's start destination whenever
 * [org.libremail.data.settings.SettingsRepository.licenseAccepted] is false — see `onboardingGraph()`
 * in `ui/LibreMailApp.kt`, which also owns persisting the Agree decision and wiring Decline to
 * `Activity.finish()`.
 *
 * The bundled text at `res/raw/license.txt` is a byte-for-byte copy of the repo-root `LICENSE` file,
 * kept that way deliberately: `Text` below renders it verbatim with no reflow logic to strip or
 * reformat anything, so any header/footer added to the raw resource would show up to the user as
 * part of the "license" they're agreeing to. Cross-reference comments therefore live *outside* the
 * resource itself — see the "License" section of README.md — rather than inside it; if `LICENSE`
 * changes, copy the change into `license.txt` too so the two don't drift.
 *
 * @param onAgree invoked when the user taps Agree; only reachable once scrolled to the end.
 * @param onDecline invoked when the user taps Decline, or presses back (there is nothing before this
 *   screen to return to — see [BackHandler] below).
 */
@Composable
fun LicenseScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    val scrollState = rememberScrollState()
    val licenseText = rememberLicenseText()
    // Also correctly resolves the "whole license fits on one screen, nothing to scroll" case: when
    // no scrolling is needed, maxValue is 0 and 0 >= 0 is trivially true.
    val scrolledToEnd = scrollState.value >= scrollState.maxValue

    // This screen is the onboarding graph's start destination, so there is nothing before it to pop
    // back to: back is wired to the exact same exit path as Decline instead of left to fall through
    // to the platform default (which would also finish the single Activity, but only implicitly).
    BackHandler(onBack = onDecline)

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.license_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.license_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            // Only the license text scrolls; Agree/Decline stay pinned below it so they are visible
            // the whole time — Agree simply flips enabled once scrolledToEnd — rather than being the
            // last item in one big scrolling column the way AppPasswordSetupScreen/ManualSetupScreen/
            // ReportReviewScreen scroll their form fields. Same rememberScrollState() +
            // Modifier.verticalScroll gating those screens use either way (scrollState.value >=
            // scrollState.maxValue) — just scoped to the text pane instead of the whole screen, which
            // reads better for a "you must read this" gate than a submit button that only appears
            // once you've already scrolled past everything.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
            ) {
                Text(text = licenseText, style = MaterialTheme.typography.bodySmall)
                // Zero-visual-footprint marker at the true end of the scrollable content, so
                // LicenseScreenTest can performScrollTo() it directly instead of simulating repeated
                // swipes (which would be flaky given the content's unknown rendered height).
                Spacer(Modifier.size(1.dp).testTag(LICENSE_SCROLL_END_TAG))
            }
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.license_decline))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onAgree, enabled = scrolledToEnd, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.license_agree))
                }
            }
        }
    }
}

/** Loads `res/raw/license.txt` once per composition; a ~35KB local resource read is negligible. */
@Composable
private fun rememberLicenseText(): String {
    val context = LocalContext.current
    return remember {
        context.resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
    }
}

/**
 * Test tag on the sentinel marking the end of the scrollable license text. Internal (not private) so
 * `LicenseScreenTest` (androidTest) can target it directly — see `IntentHandledMarker` in
 * `MainActivity.kt` for the same internal-for-androidTest-visibility pattern.
 */
internal const val LICENSE_SCROLL_END_TAG = "license_scroll_end"
