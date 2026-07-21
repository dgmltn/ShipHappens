package com.dgmltn.shiphappens.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun StatusBarIconsEffect() {
    val light = lightStatusBarIconRequests.intValue > 0
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // isAppearanceLightStatusBars == true means "light background, use dark icons", so light
        // (white) icons need it set to false.
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !light
    }
}
