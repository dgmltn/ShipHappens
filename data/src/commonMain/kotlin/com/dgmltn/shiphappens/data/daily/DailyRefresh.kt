package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.RefreshSummary
import kotlinx.datetime.LocalTime

/**
 * Posts user-visible notifications. Platform-implemented (NotificationManagerCompat on Android,
 * UNUserNotificationCenter on iOS) and deliberately narrow: the runner decides WHAT to say
 * (see NotificationText), the notifier only decides HOW to show it.
 *
 * The run* methods are debug visibility for the background pass: progress while it works, an
 * unconditional summary when it finishes (posted even when nothing changed, so a quiet morning
 * is distinguishable from a pass that never ran), and a failure note when the pass itself threw.
 */
interface StatusNotifier {
    suspend fun notifyStatusChange(change: ParcelChange)
    suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String)
    suspend fun notifyRunProgress(done: Int, total: Int)
    suspend fun notifyRunFinished(summary: RefreshSummary)
    suspend fun notifyRunFailed(message: String)
}

/** Platforms without a notification implementation (JVM), and tests. */
object NoOpStatusNotifier : StatusNotifier {
    override suspend fun notifyStatusChange(change: ParcelChange) {}
    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) {}
    override suspend fun notifyRunProgress(done: Int, total: Int) {}
    override suspend fun notifyRunFinished(summary: RefreshSummary) {}
    override suspend fun notifyRunFailed(message: String) {}
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
