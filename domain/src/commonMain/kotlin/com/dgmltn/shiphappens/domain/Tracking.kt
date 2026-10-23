package com.dgmltn.shiphappens.domain

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

enum class TrackingStatus { LABEL_CREATED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, UNKNOWN }

/** Position on the 5-step timeline; -1 for statuses that don't advance the timeline. */
val TrackingStatus.stepIndex: Int
    get() = when (this) {
        TrackingStatus.LABEL_CREATED -> 0
        TrackingStatus.SHIPPED -> 1
        TrackingStatus.IN_TRANSIT -> 2
        TrackingStatus.OUT_FOR_DELIVERY -> 3
        TrackingStatus.DELIVERED -> 4
        TrackingStatus.EXCEPTION, TrackingStatus.UNKNOWN -> -1
    }

data class TrackingEvent(
    val timestamp: Instant,
    val description: String,
    val location: String? = null,
    val status: TrackingStatus? = null,
    /** Code of the carrier that reported this scan; null means the parcel's own carrier. */
    val carrierCode: String? = null,
)

/** The carrier a parcel's own carrier says now holds it, with the number it tracks under there. */
data class Handoff(val carrier: Carrier, val trackingNumber: String)

data class TrackingSnapshot(
    val status: TrackingStatus,
    val events: List<TrackingEvent> = emptyList(),
    val etaDate: LocalDate? = null,
    val etaWindowStart: LocalTime? = null,
    val etaWindowEnd: LocalTime? = null,
    val latestLocation: String? = null,
    /** Carrier's own explanation of a delay; null means not delayed. See StatusVocabulary. */
    val delayNote: String? = null,
    /** The handoff the source saw on the page or in the API; null when it named none. */
    val handoff: Handoff? = null,
)
