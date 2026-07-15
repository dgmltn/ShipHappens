package com.shiphappens.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.data.AppClock
import com.shiphappens.data.ParcelRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import com.shiphappens.design.accentHex
import com.shiphappens.source.webview.WebCapableSource
import com.shiphappens.ui.util.TRACKING_STEP_LABELS
import com.shiphappens.ui.util.design12h
import com.shiphappens.ui.util.designFormat
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
        val headline = when {
            delivered -> "Delivered"
            status == TrackingStatus.EXCEPTION -> "Delivery exception"
            days == null -> "Waiting for first update"
            days <= 0 -> "Arriving today"
            days == 1 -> "Arrives tomorrow"
            else -> "Arrives in $days days"
        }
        val windowText = etaDate?.let { d ->
            val t = etaTime?.design12h()
            d.designFormat() + when { t == null -> ""; delivered -> " · $t"; else -> " · by $t" }
        } ?: "—"

        val effectiveStep = effectiveStepIndex

        val tz = TimeZone.currentSystemDefault()
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
