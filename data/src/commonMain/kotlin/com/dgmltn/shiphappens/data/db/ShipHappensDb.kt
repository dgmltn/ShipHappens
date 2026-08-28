package com.dgmltn.shiphappens.data.db

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RenameColumn
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.AutoMigrationSpec

@Database(
    entities = [ParcelEntity::class, TrackingEventEntity::class],
    version = 3,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = ShipHappensDb.MoveEtaTimeToWindowEnd::class),
        // v2 -> v3 adds the nullable `delayNote` column (a delay is a modifier on the status,
        // not a status of its own). Specless on purpose: a plain nullable column-add is an
        // `ALTER TABLE ADD COLUMN`, so unlike v1 -> v2 it never recreates `parcels` and cannot
        // endanger the cascading `tracking_events` foreign key described below.
        AutoMigration(from = 2, to = 3),
    ],
)
@ConstructedBy(ShipHappensDbConstructor::class)
abstract class ShipHappensDb : RoomDatabase() {
    abstract fun parcelDao(): ParcelDao

    /**
     * v1 -> v2: the single `etaTime` cutoff became `etaWindowStart`/`etaWindowEnd`. The old value
     * is a valid `etaWindowEnd`, so it's renamed rather than dropped; Room auto-adds the new,
     * nullable `etaWindowStart` column.
     *
     * Safety note: this migration recreates the `parcels` table, and `tracking_events` holds an
     * `ON DELETE CASCADE` foreign key to it. That's only safe because Room 3 never issues
     * `PRAGMA foreign_keys = ON` and `BundledSQLiteDriver` defaults enforcement off. If foreign
     * keys are ever enabled in a database-builder callback, this migration would silently delete
     * every tracking event.
     */
    @RenameColumn(tableName = "parcels", fromColumnName = "etaTime", toColumnName = "etaWindowEnd")
    class MoveEtaTimeToWindowEnd : AutoMigrationSpec
}

// Room's KSP processor generates the actual implementations per platform.
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object ShipHappensDbConstructor : RoomDatabaseConstructor<ShipHappensDb> {
    override fun initialize(): ShipHappensDb
}
