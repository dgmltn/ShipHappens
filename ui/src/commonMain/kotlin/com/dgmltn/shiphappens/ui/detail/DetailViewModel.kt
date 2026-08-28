package com.dgmltn.shiphappens.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dgmltn.shiphappens.data.AppClock
import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.design.accentHex
import com.dgmltn.shiphappens.source.webview.WebCapableSource
import com.dgmltn.shiphappens.domain.TRACKING_STEP_LABELS
import com.dgmltn.shiphappens.domain.design12h
import com.dgmltn.shiphappens.domain.designFormat
import com.dgmltn.shiphappens.domain.formatEtaWindow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

enum class StepState { DONE, CURRENT, TODO }
data class TimelineStepUi(val label: String, val time: String?, val state: StepState)

data class DetailUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val carrierName: String = "",
    val accentHex: String = "#17150F",
    val headline: String = "",
    val windowLabel: String = "",
    val windowText: String = "",
    val locationText: String? = null,
    /** Carrier's delay explanation; null hides the delay note. Independent of [headline]. */
    val delayNote: String? = null,
    val trackingNumber: String = "",
    val timeline: List<TimelineStepUi> = emptyList(),
    /** Carrier display name when an in-app web page exists for this parcel; null hides the button. */
    val webCarrierName: String? = null,
    /** True while a refresh for this parcel is in flight. */
    val refreshing: Boolean = false,
)

class DetailViewModel(
    parcelId: String,
    repository: ParcelRepository,
    private val clock: AppClock,
    registry: SourceRegistry,
) : ViewModel() {

    private val webCarrierNames: Map<String, String> =
        registry.all().filterIsInstance<WebCapableSource>()
            .associate { it.webSpec.carrier.code to it.webSpec.carrier.displayName }

    val state: StateFlow<DetailUiState> =
        combine(repository.observeParcel(parcelId), repository.refreshingIds) { parcel, refreshingIds ->
            parcel?.toDetail(refreshing = parcel.id in refreshingIds) ?: DetailUiState()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    private fun Parcel.toDetail(refreshing: Boolean): DetailUiState {
        val delivered = status == TrackingStatus.DELIVERED
        val days = etaDate?.let { clock.today().daysUntil(it) }
        // A delay never replaces the headline: the ETA is the thing the user came for, and a
        // delayed package still has one (usually a freshly revised one). The delay shows as its
        // own note below, so "Arrives tomorrow" + "Delayed — due to weather" both get said.
        val headline = when {
            delivered -> "Delivered"
            status == TrackingStatus.EXCEPTION -> "Delivery exception"
            // Reserve "Waiting for first update" for a genuinely never-refreshed parcel — same
            // guard as the home card (ListViewModel). A known status with no ETA (common for
            // Amazon IN_TRANSIT parcels) falls back to the timeline label so the top bar, the
            // timeline, and the home card can't disagree.
            lastRefreshedAt == null && status == TrackingStatus.UNKNOWN -> "Waiting for first update"
            days == null -> TRACKING_STEP_LABELS[effectiveStepIndex]
            days <= 0 -> "Arriving today"
            days == 1 -> "Arrives tomorrow"
            else -> "Arrives in $days days"
        }
        val tz = TimeZone.currentSystemDefault()
        // Delivered parcels show the actual delivery time (last DELIVERED event) rather than a
        // stale or absent ETA — USPS delivered pages carry no expected-delivery block at all.
        val deliveredAt = if (delivered) events.lastOrNull { it.status == TrackingStatus.DELIVERED }?.timestamp else null
        val windowText = deliveredAt?.toLocalDateTime(tz)?.let { "${it.date.designFormat()} · ${it.time.design12h()}" }
            ?: etaDate?.let { d ->
                // Delivered parcels show a bare time (no "by"/range); otherwise render the window.
                val w = if (delivered) etaWindowEnd?.design12h()
                        else formatEtaWindow(etaWindowStart, etaWindowEnd)
                d.designFormat() + if (w == null) "" else " · $w"
            } ?: "—"

        val effectiveStep = effectiveStepIndex
        val timeline = TRACKING_STEP_LABELS.mapIndexed { i, label ->
            val stepState = when {
                delivered || i < effectiveStep -> StepState.DONE
                i == effectiveStep -> StepState.CURRENT
                else -> StepState.TODO
            }
            val event = events.lastOrNull { it.status?.stepIndex == i }
            val time = event?.let {
                val ldt = it.timestamp.toLocalDateTime(tz)
                val base = ldt.date.designFormat()
                when {
                    i == 4 -> "$base · ${ldt.time.design12h()}"
                    stepState == StepState.CURRENT -> "$base · latest update"
                    else -> base
                }
            }
            TimelineStepUi(label, time, stepState)
        }

        return DetailUiState(
            loaded = true, name = name, carrierName = carrier.displayName, accentHex = carrier.accentHex(),
            headline = headline,
            delayNote = delayNote,
            windowLabel = if (delivered) "Delivered" else "Estimated delivery",
            windowText = windowText,
            locationText = latestLocation ?: events.lastOrNull()?.location,
            trackingNumber = trackingNumber,
            timeline = timeline,
            webCarrierName = webCarrierNames[carrier.code],
            refreshing = refreshing,
        )
    }
}
