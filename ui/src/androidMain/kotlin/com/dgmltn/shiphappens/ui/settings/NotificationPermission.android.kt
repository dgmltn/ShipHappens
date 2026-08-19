package com.dgmltn.shiphappens.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController {
    val context = LocalContext.current
    // Below API 33 notifications need no runtime permission at all, so the controller reports
    // granted and never launches anything.
    val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    var granted by remember {
        mutableStateOf(
            !needsRuntimePermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        pending?.invoke(result)
        pending = null
    }

    return remember(granted, needsRuntimePermission) {
        object : NotificationPermissionController {
            override val isGranted: Boolean get() = granted
            override fun request(onResult: (Boolean) -> Unit) {
                if (!needsRuntimePermission || granted) {
                    onResult(true)
                    return
                }
                pending = onResult
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
