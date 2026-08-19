package com.dgmltn.shiphappens.data.daily

import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * iOS gives no wall-clock guarantee: earliestBeginDate is the soonest the system will CONSIDER
 * running the task, and it may run hours later or skip a day entirely based on usage patterns.
 * The settings copy says "around" for exactly this reason.
 */
class BgTaskDailyRefreshScheduler : DailyRefreshScheduler {

    // submitTaskRequest's error out-param is a cinterop pointer.
    @OptIn(ExperimentalForeignApi::class)
    override fun schedule(at: LocalTime) {
        val next = NextRunTime.nextOccurrence(at, Clock.System.now(), TimeZone.currentSystemDefault())
        val request = BGAppRefreshTaskRequest(TASK_IDENTIFIER).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(next.epochSeconds.toDouble())
        }
        // Throws only for an unregistered identifier or a simulator without BGTaskScheduler;
        // neither is worth crashing the app over.
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
    }

    override fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_IDENTIFIER)
    }

    companion object {
        const val TASK_IDENTIFIER = "com.dgmltn.shiphappens.dailyrefresh"
    }
}
