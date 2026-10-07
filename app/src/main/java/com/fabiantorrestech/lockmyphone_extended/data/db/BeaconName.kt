package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Your own name for a Bluetooth beacon (or a paired device heard nearby), so two beacons of the
 * same kind can be told apart. One name per beacon, shared by every place that uses it. Added in
 * DB version 19.
 *
 * @param key the beacon as [com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules] keys it.
 */
@Entity(tableName = "beacon_names")
data class BeaconName(
    @PrimaryKey val key: String,
    val name: String,
)

@Dao
interface BeaconNameDao {
    @Query("SELECT * FROM beacon_names ORDER BY name")
    fun observeAll(): Flow<List<BeaconName>>

    @Query("SELECT * FROM beacon_names ORDER BY name")
    suspend fun getAll(): List<BeaconName>

    @Upsert
    suspend fun upsert(name: BeaconName)

    @Query("DELETE FROM beacon_names WHERE `key` = :key")
    suspend fun delete(key: String)
}
