package com.shiphappens.data.db

import com.shiphappens.domain.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

fun Parcel.toEntity(archivedAt: Long? = null) = ParcelEntity(
    id = id, name = name, trackingNumber = trackingNumber, normalizedTracking = normalizedTracking,
    carrierCode = carrier.code, carrierName = carrier.displayName, carrierColor = carrier.accentColorHex,
    sourceId = sourceId, status = status.name,
    etaDate = etaDate?.toString(),
    etaWindowStart = etaWindowStart?.toString(), etaWindowEnd = etaWindowEnd?.toString(),
    latestLocation = latestLocation, isArchived = isArchived, archivedAt = archivedAt,
    createdAt = createdAt.toEpochMilliseconds(), lastRefreshedAt = lastRefreshedAt?.toEpochMilliseconds(),
)

fun TrackingEvent.toEntity(parcelId: String) = TrackingEventEntity(
    parcelId = parcelId, timestamp = timestamp.toEpochMilliseconds(),
    description = description, location = location, status = status?.name,
)

fun ParcelWithEvents.toDomain(): Parcel = Parcel(
    id = parcel.id, name = parcel.name, trackingNumber = parcel.trackingNumber,
    carrier = Carrier(parcel.carrierCode, parcel.carrierName, parcel.carrierColor),
    sourceId = parcel.sourceId,
    status = runCatching { TrackingStatus.valueOf(parcel.status) }.getOrDefault(TrackingStatus.UNKNOWN),
    etaDate = parcel.etaDate?.let(LocalDate::parse),
    etaWindowStart = parcel.etaWindowStart?.let(LocalTime::parse),
    etaWindowEnd = parcel.etaWindowEnd?.let(LocalTime::parse),
    events = events.sortedBy { it.timestamp }.map {
        TrackingEvent(
            timestamp = Instant.fromEpochMilliseconds(it.timestamp),
            description = it.description, location = it.location,
            status = it.status?.let { s -> runCatching { TrackingStatus.valueOf(s) }.getOrNull() },
        )
    },
    latestLocation = parcel.latestLocation, isArchived = parcel.isArchived,
    createdAt = Instant.fromEpochMilliseconds(parcel.createdAt),
    lastRefreshedAt = parcel.lastRefreshedAt?.let(Instant::fromEpochMilliseconds),
)
