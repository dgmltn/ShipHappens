package com.dgmltn.shiphappens.ui

import com.dgmltn.shiphappens.data.daily.DailyRefreshRunner
import com.dgmltn.shiphappens.data.daily.DailyRefreshScheduler
import com.dgmltn.shiphappens.data.daily.StatusNotifier
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Callback-shaped bridge for the BGAppRefreshTask handler: Swift must call
 * task.setTaskCompleted(success:) when the work finishes, and it cannot await a Kotlin
 * suspend function.
 */
object IosDailyRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun run(onComplete: (Boolean) -> Unit) {
        scope.launch {
            val koin = KoinPlatform.getKoin()
            val result = runCatching { koin.get<DailyRefreshRunner>().runOnce() }
            // The runner posts its own finished summary; a crash before that would otherwise be
            // indistinguishable from a run that never fired. (Android's worker does the same.)
            result.exceptionOrNull()?.let { koin.get<StatusNotifier>().notifyRunFailed(it.toString()) }
            val ok = result.isSuccess
            // iOS discards a task request once it has executed, so each run books the next one.
            // (On Android the worker does the equivalent for itself.)
            val settings = koin.get<SettingsRepository>().settings.first()
            if (settings.dailyUpdateEnabled) {
                koin.get<DailyRefreshScheduler>().schedule(settings.dailyUpdateTime)
            }
            onComplete(ok)
        }
    }
}
