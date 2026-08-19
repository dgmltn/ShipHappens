package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelChange
import kotlinx.datetime.LocalTime

/**
 * Posts user-visible notifications. Platform-implemented (NotificationManagerCompat on Android,
 * UNUserNotificationCenter on iOS) and deliberately narrow: the runner decides WHAT to say
 * (see NotificationText), the notifier only decides HOW to show it.
 */
interface StatusNotifier {
    suspend fun notifyStatusChange(change: ParcelChange)
    suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String)
}

/** Platforms without a notification implementation (JVM), and tests. */
object NoOpStatusNotifier : StatusNotifier {
    override suspend fun notifyStatusChange(change: ParcelChange) {}
    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) {}
}

/**
 * Books the once-a-day background run. [schedule] is idempotent — calling it again replaces
 * any existing booking, which is how a changed time takes effect.
 */
interface DailyRefreshScheduler {
    fun schedule(at: LocalTime)
    fun cancel()
}

object NoOpDailyRefreshScheduler : DailyRefreshScheduler {
    override fun schedule(at: LocalTime) {}
    override fun cancel() {}
}
