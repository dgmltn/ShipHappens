package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.notify.NotificationText
import platform.Foundation.NSUUID
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Posts immediately (nil trigger) — the daily pass has already run, so there is nothing to wait
 * for. threadIdentifier groups the morning's updates into one stack, matching Android's group.
 */
class IosStatusNotifier : StatusNotifier {

    override suspend fun notifyStatusChange(change: ParcelChange) {
        post(
            title = NotificationText.title(change),
            body = NotificationText.body(change),
            thread = THREAD_UPDATES,
            userInfo = mapOf<Any?, Any?>("parcelId" to change.parcelId),
        )
    }

    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) {
        post(
            title = NotificationText.signInTitle(sourceDisplayName),
            body = NotificationText.signInBody(sourceDisplayName),
            thread = THREAD_SIGN_IN,
            userInfo = mapOf<Any?, Any?>("sourceId" to sourceId),
        )
    }

    private fun post(title: String, body: String, thread: String, userInfo: Map<Any?, Any?>) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setThreadIdentifier(thread)
            setUserInfo(userInfo)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = NSUUID().UUIDString, content = content, trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    private companion object {
        const val THREAD_UPDATES = "package_updates"
        const val THREAD_SIGN_IN = "sign_in"
    }
}
