package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.RefreshSummary
import com.dgmltn.shiphappens.data.notify.NotificationText
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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

    override suspend fun notifyRunProgress(done: Int, total: Int) {
        // A fixed identifier makes each progress tick REPLACE the previous one — iOS has no
        // notification progress bar, so the body carries the count.
        post(
            title = NotificationText.runProgressTitle(),
            body = "$done of $total",
            thread = THREAD_DIAGNOSTICS,
            userInfo = emptyMap(),
            identifier = RUN_PROGRESS_ID,
        )
    }

    override suspend fun notifyRunFinished(summary: RefreshSummary) {
        clearProgress()
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        post(
            title = NotificationText.runSummaryTitle(),
            body = NotificationText.runSummaryBody(summary, today),
            thread = THREAD_DIAGNOSTICS,
            userInfo = emptyMap(),
            identifier = RUN_SUMMARY_ID,
        )
    }

    override suspend fun notifyRunFailed(message: String) {
        clearProgress()
        post(
            title = NotificationText.runFailedTitle(),
            body = message,
            thread = THREAD_DIAGNOSTICS,
            userInfo = emptyMap(),
            identifier = RUN_SUMMARY_ID,
        )
    }

    private fun clearProgress() {
        UNUserNotificationCenter.currentNotificationCenter()
            .removeDeliveredNotificationsWithIdentifiers(listOf(RUN_PROGRESS_ID))
    }

    private fun post(
        title: String,
        body: String,
        thread: String,
        userInfo: Map<Any?, Any?>,
        identifier: String = NSUUID().UUIDString,
    ) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setThreadIdentifier(thread)
            setUserInfo(userInfo)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = identifier, content = content, trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    private companion object {
        const val THREAD_UPDATES = "package_updates"
        const val THREAD_SIGN_IN = "sign_in"
        const val THREAD_DIAGNOSTICS = "diagnostics"
        const val RUN_PROGRESS_ID = "run-progress"
        const val RUN_SUMMARY_ID = "run-summary"
    }
}
