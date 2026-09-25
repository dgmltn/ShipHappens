package com.dgmltn.shiphappens.data.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.RefreshSummary
import com.dgmltn.shiphappens.data.notify.NotificationText

/**
 * Posts the daily run's findings.
 *
 * Notification ids come from the parcel id's hash so a second change to the same parcel
 * REPLACES the first rather than stacking two rows for one package. All package updates share
 * a group plus a summary notification, so several at 8am collapse into one expandable entry.
 *
 * Posting is silently dropped by the OS when POST_NOTIFICATIONS is denied — the settings
 * screen owns asking, and there is nothing useful to do about it from a background worker.
 */
class AndroidStatusNotifier(private val context: Context) : StatusNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override suspend fun notifyStatusChange(change: ParcelChange) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(NotificationText.title(change))
            .setContentText(NotificationText.body(change))
            .setContentIntent(deepLinkIntent("shiphappens://parcel/${change.parcelId}", change.parcelId.hashCode()))
            .setAutoCancel(true)
            .setGroup(GROUP_UPDATES)
            .build()
        post(change.parcelId.hashCode(), notification)
        postGroupSummary()
    }

    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) {
        ensureChannels()
        val body = NotificationText.signInBody(sourceDisplayName)
        val notification = NotificationCompat.Builder(context, CHANNEL_SIGN_IN)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(NotificationText.signInTitle(sourceDisplayName))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(deepLinkIntent("shiphappens://signin/$sourceId", sourceId.hashCode()))
            .setAutoCancel(true)
            .build()
        post(SIGN_IN_ID_BASE + sourceId.hashCode(), notification)
    }

    override suspend fun notifyRunProgress(done: Int, total: Int) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_DIAGNOSTICS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(NotificationText.runProgressTitle())
            .setContentText("$done of $total")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
        post(RUN_PROGRESS_ID, notification)
    }

    override suspend fun notifyRunFinished(summary: RefreshSummary) {
        ensureChannels()
        manager.cancel(RUN_PROGRESS_ID)
        val notification = NotificationCompat.Builder(context, CHANNEL_DIAGNOSTICS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(NotificationText.runSummaryTitle())
            .setContentText(NotificationText.runSummaryShort(summary))
            .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationText.runSummaryBody(summary)))
            .setShowWhen(true)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        post(RUN_SUMMARY_ID, notification)
    }

    override suspend fun notifyRunFailed(message: String) {
        ensureChannels()
        manager.cancel(RUN_PROGRESS_ID)
        val notification = NotificationCompat.Builder(context, CHANNEL_DIAGNOSTICS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(NotificationText.runFailedTitle())
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setShowWhen(true)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        post(RUN_SUMMARY_ID, notification)
    }

    private fun postGroupSummary() {
        val summary = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Package updates")
            .setGroup(GROUP_UPDATES)
            .setGroupSummary(true)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        post(SUMMARY_ID, summary)
    }

    private fun post(id: Int, notification: android.app.Notification) {
        // areNotificationsEnabled() spares us a SecurityException-shaped surprise on API 33+;
        // NotificationManagerCompat.notify itself requires the permission at call time.
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun deepLinkIntent(uri: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Opens the app to wherever it was, or its start screen on a cold launch. */
    private fun openAppIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context, OPEN_APP_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "Package updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Status and delivery-date changes found by the daily check"
            }
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_SIGN_IN, "Sign-in needed", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A carrier session expired and packages can't be updated"
            }
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_DIAGNOSTICS, "Background check", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and result of every background check, even a quiet one"
            }
        )
    }

    private companion object {
        const val CHANNEL_UPDATES = "package_updates"
        const val CHANNEL_SIGN_IN = "sign_in"
        const val CHANNEL_DIAGNOSTICS = "diagnostics"
        const val GROUP_UPDATES = "com.dgmltn.shiphappens.UPDATES"
        const val SUMMARY_ID = 1
        const val RUN_PROGRESS_ID = 2
        const val RUN_SUMMARY_ID = 3
        const val SIGN_IN_ID_BASE = 100_000
        const val OPEN_APP_REQUEST_CODE = 0
    }
}
