package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a location trigger does when you arrive or leave. */
enum class LocationMode {
    /** Arriving starts the lock; leaving ends it (if this location started it). */
    WHILE_HERE,

    /** Arriving starts the lock for its normal length. */
    ON_ARRIVAL,

    /** Leaving starts the lock for its normal length. */
    ON_LEAVE,

    /** Locked while you're here during one of its time slots ([LocationSlot]). Added in DB version 11. */
    TIME_SLOTS,
}

/** How "being here" is decided when a place has both a GPS circle and a Wi-Fi network. */
enum class LocationMatch {
    GPS_ONLY,
    WIFI_ONLY,

    /** Both needed: the strictest. */
    GPS_AND_WIFI,

    /** Either is enough: the most reliable indoors. */
    GPS_OR_WIFI,
}

/**
 * Starts a lock by place: a circle around a point, and optionally a specific Wi-Fi router
 * (its BSSID), which is exact to a building or room. Added in DB version 10.
 *
 * @param radiusMeters the circle's radius, 25–2000 m.
 * @param wifiBssid when set, you only count as "here" while connected to this router.
 * @param wifiSsid the router's network name, for display only.
 * @param profileId the lock to start; null = Quick lock.
 * @param activeRule which days it's active on; shared with alarm triggers. Not used by
 *   [LocationMode.TIME_SLOTS], whose slots have their own days.
 * @param matchMode how the GPS circle and the Wi-Fi network combine. Added in DB version 11.
 * @param lockOnReturn time slots: lock when you arrive or come back during a slot.
 * @param stayLockedAfterLeaving time slots: leaving keeps the lock until the slot ends, instead
 *   of unlocking.
 */
@Entity(
    tableName = "location_triggers",
    foreignKeys = [
        ForeignKey(
            entity = LockProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class LocationTrigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val wifiBssid: String? = null,
    val wifiSsid: String? = null,
    val mode: LocationMode = LocationMode.WHILE_HERE,
    val profileId: Long? = null,
    val activeRule: AlarmActiveRule = AlarmActiveRule.EVERY_DAY,
    val daysMask: Int = LockSchedule.EVERY_DAY,
    val dates: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "GPS_ONLY") val matchMode: LocationMatch = LocationMatch.GPS_ONLY,
    @ColumnInfo(defaultValue = "1") val lockOnReturn: Boolean = true,
    @ColumnInfo(defaultValue = "0") val stayLockedAfterLeaving: Boolean = false,
) {
    /** Whether the GPS circle takes part in deciding "here". */
    val usesGps: Boolean get() = wifiBssid == null || matchMode != LocationMatch.WIFI_ONLY

    /** Whether the Wi-Fi network takes part in deciding "here". */
    val usesWifi: Boolean get() = wifiBssid != null && matchMode != LocationMatch.GPS_ONLY

    companion object {
        const val DEFAULT_RADIUS_METERS = 100
        val RADIUS_RANGE = 25..2000
    }
}
