package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One time slot of a "during time slots" location: on these days, from start to end, the phone
 * is locked while you're at the place. An end at or before the start runs past midnight.
 * Added in DB version 11.
 */
@Entity(
    tableName = "location_slots",
    foreignKeys = [
        ForeignKey(
            entity = LocationTrigger::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("locationId")],
)
data class LocationSlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val locationId: Long = 0,
    val daysMask: Int = LockSchedule.EVERY_DAY,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    /** A slot has the same shape as a weekly schedule, so the schedule maths can be reused. */
    fun asSchedule() = LockSchedule(
        id = id,
        profileId = 0,
        daysMask = daysMask,
        startMinuteOfDay = startMinuteOfDay,
        endMinuteOfDay = endMinuteOfDay,
    )

    val isValid: Boolean get() = daysMask != 0 && startMinuteOfDay != endMinuteOfDay
}
