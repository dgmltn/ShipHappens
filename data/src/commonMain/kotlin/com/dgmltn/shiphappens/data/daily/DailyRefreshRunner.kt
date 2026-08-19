package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.RefreshSummary
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import kotlinx.coroutines.flow.first

/**
 * One daily pass: refresh every undelivered parcel, then say what moved.
 *
 * Platform-free by design — the Android worker and the iOS BGTask handler both do nothing but
 * call [runOnce], so the candidate rule, the change rule, and the nag dedup exist once.
 */
class DailyRefreshRunner(
    private val repository: ParcelRepository,
    private val settings: SettingsRepository,
    private val registry: SourceRegistry,
    private val notifier: StatusNotifier,
) {

    suspend fun runOnce(): RefreshSummary {
        val current = settings.settings.first()
        // Defensive: the scheduler should already be cancelled when the toggle is off, but a
        // stale WorkManager booking (or an iOS task submitted before the user opted out) must
        // not put the app on the network.
        if (!current.dailyUpdateEnabled) return RefreshSummary(attempted = 0, failed = 0)

        // force = true: the daily pass deliberately ignores the foreground staleness setting,
        // including MANUAL. refreshAll's own filter already excludes archived and DELIVERED.
        val summary = repository.refreshAll(force = true)

        summary.changes.filter { it.isNotable }.forEach { notifier.notifyStatusChange(it) }

        val nagged = current.signInNaggedSourceIds
        val toNag = summary.authFailedSourceIds - nagged
        toNag.forEach { id -> notifier.notifySignInNeeded(id, displayNameOf(id)) }
        // A source that succeeded is signed in again, so it re-arms for the next expiry.
        val updatedNags = (nagged + toNag) - summary.succeededSourceIds
        if (updatedNags != nagged) settings.setSignInNaggedSourceIds(updatedNags)

        return summary
    }

    private fun displayNameOf(sourceId: String): String =
        registry.all().firstOrNull { it.descriptor.id == sourceId }?.descriptor?.displayName ?: sourceId
}
