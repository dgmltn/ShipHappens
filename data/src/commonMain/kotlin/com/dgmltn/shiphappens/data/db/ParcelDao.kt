package com.dgmltn.shiphappens.data.db

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ParcelDao {
    @Transaction
    @Query("SELECT * FROM parcels WHERE isArchived = :archived")
    fun observe(archived: Boolean): Flow<List<ParcelWithEvents>>

    @Transaction
    @Query("SELECT * FROM parcels WHERE id = :id")
    fun observeById(id: String): Flow<ParcelWithEvents?>

    @Transaction
    @Query("SELECT * FROM parcels WHERE id = :id")
    suspend fun getById(id: String): ParcelWithEvents?

    @Query("SELECT * FROM parcels WHERE isArchived = 0")
    suspend fun allActive(): List<ParcelEntity>

    @Query("SELECT normalizedTracking FROM parcels")
    suspend fun normalizedNumbers(): List<String>

    @Upsert
    suspend fun upsertParcel(p: ParcelEntity)

    @Query("DELETE FROM tracking_events WHERE parcelId = :parcelId")
    suspend fun deleteEvents(parcelId: String)

    @Insert
    suspend fun insertEvents(events: List<TrackingEventEntity>)

    @Transaction
    suspend fun replaceEvents(parcelId: String, events: List<TrackingEventEntity>) {
        deleteEvents(parcelId)
        insertEvents(events)
    }

    @Query("UPDATE parcels SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE parcels SET isArchived = 1, archivedAt = :at WHERE id = :id")
    suspend fun archive(id: String, at: Long)

    @Query("UPDATE parcels SET isArchived = 0, archivedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    @Query("DELETE FROM parcels WHERE id = :id")
    suspend fun deleteParcel(id: String)
}
