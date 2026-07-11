package com.shiphappens.core.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(entities = [ParcelEntity::class, TrackingEventEntity::class], version = 1)
@ConstructedBy(ShipHappensDbConstructor::class)
abstract class ShipHappensDb : RoomDatabase() {
    abstract fun parcelDao(): ParcelDao
}

// Room's KSP processor generates the actual implementations per platform.
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object ShipHappensDbConstructor : RoomDatabaseConstructor<ShipHappensDb> {
    override fun initialize(): ShipHappensDb
}
