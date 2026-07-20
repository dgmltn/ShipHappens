package com.dgmltn.shiphappens.ui

import androidx.compose.runtime.Composable

/**
 * Requests light (white) status bar icons for as long as the calling composable is in the
 * composition, restoring the app-wide dark icons on dispose. Screens with a carrier-colored
 * header call this; screens on the light background leave the default alone.
 */
@Composable
expect fun LightStatusBarIcons()
