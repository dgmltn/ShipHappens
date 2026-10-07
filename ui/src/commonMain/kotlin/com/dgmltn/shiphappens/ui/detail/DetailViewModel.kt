package com.dgmltn.shiphappens.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dgmltn.shiphappens.data.AppClock
import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.TimeFormat
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.design.accentHex
import com.dgmltn.shiphappens.source.webview.WebCapableSource
import com.dgmltn.shiphappens.domain.TRACKING_STEP_LABELS
import com.dgmltn.shiphappens.domain.designTime
import com.dgmltn.shiphappens.domain.designFormat
import com.dgmltn.shiphappens.domain.formatEtaWindow
import com.dgmltn.shiphappens.data.geo.GeoRepository
import com.dgmltn.shiphappens.geo.LatLng
import com.dgmltn.shiphappens.geo.buildRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

enum class StepState { DONE, CURRENT, TODO }
data class TimelineStepUi(val label: String, val time: String?, val state: StepState)

data class StopEventUi(val whenText: String, val description: String)

/** [events] are newest first. */
data class StopUi(val name: String, val at: LatLng, val events: List<StopEventUi>)

data class RouteUi(val stops: List<StopUi>, val delivered: Boolean, val description: String)

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
    /** Null when no scan location resolved; the card then keeps its placeholder. */
    val route: RouteUi? = null,
)

class DetailViewModel(
    private val parcelId: String,
    private val repository: ParcelRepository,
    private val clock: AppClock,
    registry: SourceRegistry,
    private val timeFormat: TimeFormat,
    private val geo: GeoRepository,
) : ViewModel() {

    private val webCarrierNames: Map<String, String> =
        registry.all().filterIsInstance<WebCapableSource>()
            .associate { it.webSpec.carrier.code to it.webSpec.carrier.displayName }

    private val parcel: Flow<Parcel?> =
        repository.observeParcel(parcelId).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // Resolved places arrive after the parcel does, so the screen renders at once and the route
    // fills in later; a geo failure leaves the route empty rather than failing the screen.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val coords: Flow<Map<String, LatLng>> =
        parcel
            .map { p -> p?.events?.mapNotNull { it.location }?.distinct().orEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { places -> geo.observe(places) }
            .catch { emit(emptyMap()) }

    val state: StateFlow<DetailUiState> =
        combine(parcel, repository.refreshingIds, coords.onStart { emit(emptyMap()) }) { parcel, refreshingIds, coords ->
            parcel?.toDetail(refreshing = parcel.id in refreshingIds, coords = coords) ?: DetailUiState()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    /**
     * Blank input is absorbed by the repository, which keeps the existing name — the header's
     * editor cancels back to the stored value rather than showing an empty title.
     */
    fun onRename(name: String) {
        viewModelScope.launch { repository.rename(parcelId, name) }
    }

    private fun Parcel.toDetail(refreshing: Boolean, coords: Map<String, LatLng>): DetailUiState {
        val delivered = status == TrackingStatus.DELIVERED
        val days = etaDate?.let { clock.today().daysUntil(it) }
        val tz = TimeZone.currentSystemDefault()
        val is24Hour = timeFormat.uses24HourClock()
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
            // On the day itself the window is the news: "Arriving 12:45 – 16:45" beats "today".
            days <= 0 -> arrivingWindowText(etaWindowStart, etaWindowEnd, is24Hour) ?: "Arriving today"
            days == 1 -> "Arrives tomorrow"
            else -> "Arrives in $days days"
        }
        // Delivered parcels show the actual delivery time (last DELIVERED event) rather than a
        // stale or absent ETA — USPS delivered pages carry no expected-delivery block at all.
        val deliveredAt = if (delivered) events.lastOrNull { it.status == TrackingStatus.DELIVERED }?.timestamp else null
        val windowText = deliveredAt?.toLocalDateTime(tz)?.let { "${it.date.designFormat()} · ${it.time.designTime(is24Hour)}" }
            ?: etaDate?.let { d ->
                // Delivered parcels show a bare time (no "by"/range); otherwise render the window.
                val w = if (delivered) etaWindowEnd?.designTime(is24Hour)
                        else formatEtaWindow(etaWindowStart, etaWindowEnd, is24Hour)
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
                    i == 4 -> "$base · ${ldt.time.designTime(is24Hour)}"
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
            route = routeUi(coords, delivered, tz, is24Hour),
        )
    }

    private fun Parcel.routeUi(coords: Map<String, LatLng>, delivered: Boolean, tz: TimeZone, is24Hour: Boolean): RouteUi? {
        val stops = buildRoute(events, coords)
        if (stops.isEmpty()) return null
        return RouteUi(
            stops = stops.map { s ->
                StopUi(s.displayName, s.at, s.events.asReversed().map { e ->
                    val ldt = e.timestamp.toLocalDateTime(tz)
                    StopEventUi("${ldt.date.designFormat()} · ${ldt.time.designTime(is24Hour)}", e.description)
                })
            },
            delivered = delivered,
            description = routeDescription(stops.map { it.displayName }),
        )
    }
}

internal fun routeDescription(names: List<String>): String {
    val shown = if (names.size > 5) names.take(2) + "…" + names.takeLast(2) else names
    val count = if (names.size == 1) "1 stop" else "${names.size} stops"
    return "Route: ${shown.joinToString(" → ")}, $count"
}
