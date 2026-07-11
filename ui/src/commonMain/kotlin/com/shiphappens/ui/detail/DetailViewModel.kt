package com.shiphappens.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.data.AppClock
import com.shiphappens.data.ParcelRepository
import com.shiphappens.domain.*
import com.shiphappens.design.accentHex
import com.shiphappens.ui.util.design12h
import com.shiphappens.ui.util.designFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
)

private val STEP_LABELS = listOf("Label created", "Shipped", "In transit", "Out for delivery", "Delivered")

class DetailViewModel(
    parcelId: String,
    repository: ParcelRepository,
    private val clock: AppClock,
) : ViewModel() {

    val state: StateFlow<DetailUiState> = repository.observeParcel(parcelId)
        .map { parcel -> parcel?.toDetail() ?: DetailUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    private fun Parcel.toDetail(): DetailUiState {
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

        val effectiveStep = if (status.stepIndex >= 0) status.stepIndex
            else events.mapNotNull { it.status?.stepIndex }.filter { it >= 0 }.maxOrNull() ?: 0

        val tz = TimeZone.currentSystemDefault()
        val timeline = STEP_LABELS.mapIndexed { i, label ->
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
        )
    }
}
