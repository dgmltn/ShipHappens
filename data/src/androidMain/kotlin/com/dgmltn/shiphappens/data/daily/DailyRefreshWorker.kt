package com.dgmltn.shiphappens.data.daily

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The 8am pass. Resolves its collaborators from the app-wide Koin graph rather than taking a
 * WorkerFactory, because the app has no other custom worker and Koin is already started by
 * ShipHappensApplication before WorkManager can run anything.
 *
 * The headless scrape path is safe from here: HeadlessWebViewScraper hops to
 * Dispatchers.Main.immediate itself before constructing a WebView.
 */
class DailyRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val runner: DailyRefreshRunner by inject()
    private val settings: SettingsRepository by inject()
    private val scheduler: DailyRefreshScheduler by inject()

    override suspend fun doWork(): Result = try {
        runner.runOnce()
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    } finally {
        // Re-anchor tomorrow's run whatever happened — a failed pass must not end the series.
        // Reading the time fresh means a time change made while the app was closed takes effect.
        val current = settings.settings.first()
        if (current.dailyUpdateEnabled) scheduler.schedule(current.dailyUpdateTime) else scheduler.cancel()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily-refresh"
    }
}
