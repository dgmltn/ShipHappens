package com.dgmltn.shiphappens.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dgmltn.shiphappens.ui.App
import com.dgmltn.shiphappens.ui.navigation.DeepLink
import com.dgmltn.shiphappens.ui.navigation.parseDeepLink

class MainActivity : ComponentActivity() {

    /**
     * Set from the launch intent and from onNewIntent (the activity is singleTop, so a tapped
     * notification re-enters the running instance rather than recreating it). App() clears it
     * once it has pushed the route, so a configuration change doesn't re-navigate.
     */
    private val pendingDeepLink = mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingDeepLink.value = parseDeepLink(intent?.dataString)
        // The app is light-only, so pin the system bars to dark icons rather than letting
        // enableEdgeToEdge() pick white ones from the system dark theme. Screens with a
        // carrier-colored header flip the status bar back to light icons themselves.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            App(
                deepLink = pendingDeepLink.value,
                onDeepLinkHandled = { pendingDeepLink.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = parseDeepLink(intent.dataString)
    }
}
