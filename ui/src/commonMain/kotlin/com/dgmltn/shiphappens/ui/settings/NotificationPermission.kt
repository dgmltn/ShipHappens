package com.dgmltn.shiphappens.ui.settings

import androidx.compose.runtime.Composable

/**
 * The system notification-permission prompt. Lives in ui, not data, because asking requires a
 * foreground Activity on Android — the 8am worker has none, so the request has to happen here,
 * at the moment the user flips the toggle.
 */
interface NotificationPermissionController {
    val isGranted: Boolean
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberNotificationPermissionController(): NotificationPermissionController
