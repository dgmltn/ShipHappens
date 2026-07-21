package com.dgmltn.shiphappens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * How many composables currently in the composition want light (white) status bar icons.
 *
 * Reference-counted rather than a simple flip-and-restore, so overlapping screens during a Nav3
 * transition don't fight. Navigating Detail -> "more details" web keeps both carrier-header
 * screens composed while the push animates; both may want light icons. Restoring dark on the
 * exiting screen's disposal (which lands *after* the entering screen ran) would flip the bar back
 * to dark under the new screen. Decrementing a count instead leaves it > 0, so the appearance holds.
 */
internal val lightStatusBarIconRequests = mutableIntStateOf(0)

/**
 * Requests the status bar icon appearance that stays legible over [headerColor], for as long as
 * the calling composable is in the composition: light (white) icons over a dark header, dark icons
 * (the app default) over a light one — e.g. Amazon's amber header gets dark icons, UPS's brown gets
 * white ones.
 *
 * Reactive by design: carrier accent colors resolve asynchronously (the state starts on a dark
 * placeholder before the ViewModel emits the real color), so the choice is re-derived whenever
 * [headerColor] changes rather than being fixed when the screen first composes. Screens with a
 * carrier-colored top bar call this with that bar's color; [StatusBarIconsEffect] at the app root
 * applies the aggregate result.
 */
@Composable
fun StatusBarIconsForHeader(headerColor: Color) {
    // Dark header -> white icons. luminance() is the standard sRGB relative luminance (0..1);
    // 0.5 splits the carrier palette cleanly (Amazon #FEBD69 ~0.59 light, the rest well below).
    if (headerColor.luminance() < 0.5f) {
        DisposableEffect(Unit) {
            lightStatusBarIconRequests.intValue++
            onDispose { lightStatusBarIconRequests.intValue-- }
        }
    }
}

/**
 * Drives the system status bar icon appearance from the outstanding [StatusBarIconsForHeader]
 * requests. Install exactly once, at the app root: a single owner of the bar state means there is
 * no dispose-ordering race between screens as they transition.
 */
@Composable
expect fun StatusBarIconsEffect()
