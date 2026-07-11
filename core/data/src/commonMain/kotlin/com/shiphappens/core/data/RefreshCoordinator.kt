package com.shiphappens.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Foreground-only refresh (spec: no background schedulers in v1). */
class RefreshCoordinator(
    private val repository: ParcelRepository,
    private val scope: CoroutineScope,
) {
    private val _summaries = MutableSharedFlow<RefreshSummary>(extraBufferCapacity = 4)
    val summaries: SharedFlow<RefreshSummary> = _summaries

    fun onAppForeground() {
        scope.launch { _summaries.emit(repository.refreshAll(force = false)) }
    }
}
