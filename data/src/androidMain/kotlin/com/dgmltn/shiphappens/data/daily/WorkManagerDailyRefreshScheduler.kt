package com.dgmltn.shiphappens.data.daily

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * Books the daily pass as SELF-RESCHEDULING one-time work rather than PeriodicWorkRequest.
 *
 * A 24-hour period drifts an hour across each DST change and can never be re-anchored; a
 * one-time request whose successor is enqueued by the worker recomputes the next local 8:00
 * every day (see NextRunTime). WorkManager persists enqueued work across reboot, so no
 * BOOT_COMPLETED receiver is needed.
 */
class WorkManagerDailyRefreshScheduler(private val context: Context) : DailyRefreshScheduler {

    override fun schedule(at: LocalTime) {
        val delay = NextRunTime.delayUntilNext(at, Clock.System.now(), TimeZone.currentSystemDefault())
        val request = OneTimeWorkRequestBuilder<DailyRefreshWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DailyRefreshWorker.UNIQUE_WORK_NAME,
            // REPLACE, so changing the time re-books rather than queuing a second run.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(DailyRefreshWorker.UNIQUE_WORK_NAME)
    }
}
