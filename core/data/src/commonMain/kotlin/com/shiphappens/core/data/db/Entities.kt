package com.shiphappens.core.data.db

import androidx.room3.*

@Entity(tableName = "parcels")
data class ParcelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val trackingNumber: String,
    val normalizedTracking: String,
    val carrierCode: String,
    val carrierName: String,
    val carrierColor: String?,
    val sourceId: String?,
    val status: String,
    val etaDate: String?,       // ISO-8601 LocalDate
    val etaTime: String?,       // ISO-8601 LocalTime
    val latestLocation: String?,
    val isArchived: Boolean,
    val archivedAt: Long?,      // epoch millis, for archived-tab ordering
    val createdAt: Long,
    val lastRefreshedAt: Long?,
)

@Entity(
    tableName = "tracking_events",
    foreignKeys = [ForeignKey(
        entity = ParcelEntity::class,
        parentColumns = ["id"], childColumns = ["parcelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("parcelId")],
)
data class TrackingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parcelId: String,
    val timestamp: Long,
    val description: String,
    val location: String?,
    val status: String?,
)

data class ParcelWithEvents(
    @Embedded val parcel: ParcelEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["parcelId"])
    val events: List<TrackingEventEntity>,
)
