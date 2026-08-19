package com.dgmltn.shiphappens.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController {
    var granted by remember { mutableStateOf(false) }
    return remember {
        object : NotificationPermissionController {
            override val isGranted: Boolean get() = granted
            override fun request(onResult: (Boolean) -> Unit) {
                val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
                UNUserNotificationCenter.currentNotificationCenter()
                    .requestAuthorizationWithOptions(options) { allowed, _ ->
                        granted = allowed
                        onResult(allowed)
                    }
            }
        }
    }
}
