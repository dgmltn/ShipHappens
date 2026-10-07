package com.dgmltn.shiphappens.data.db

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoDao {
    @Query("SELECT * FROM geo_cache WHERE `key` IN (:keys)")
    fun observe(keys: List<String>): Flow<List<GeoCacheEntity>>

    @Query("SELECT * FROM geo_cache WHERE `key` IN (:keys)")
    suspend fun get(keys: List<String>): List<GeoCacheEntity>

    @Upsert
    suspend fun upsert(e: GeoCacheEntity)

    @Upsert
    suspend fun upsertAll(rows: List<GeoCacheEntity>)
}
