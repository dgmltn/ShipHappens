package com.dgmltn.shiphappens.data.db

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Keyed by PlaceKey.normalize output. Null lat/lng remembers that the place has no answer. */
@Entity(tableName = "geo_cache")
data class GeoCacheEntity(
    @PrimaryKey val key: String,
    val lat: Double?,
    val lng: Double?,
    val resolvedAt: Long,  // epoch millis
)
