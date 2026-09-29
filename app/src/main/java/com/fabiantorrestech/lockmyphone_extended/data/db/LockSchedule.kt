package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.DayOfWeek

/**
 * A weekly window that starts [profileId]'s lock at [startMinuteOfDay] on each day in
 * [daysMask] and ends it at [endMinuteOfDay]. If the end is at or before the start, the window
 * runs past midnight into the next day (e.g. 22:00 → 07:00).
 *
 * @param daysMask bit 0 = Monday … bit 6 = Sunday (the start day).
 */
@Entity(
    tableName = "schedules",
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
data class LockSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val daysMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val enabled: Boolean = true,
    /** An icon ("mi:<Name>" or an emoji); null shows the profile's icon. Added in DB version 8. */
    val icon: String? = null,
    /** Position in the schedule list, set by dragging. Added in DB version 8. */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    /** "Only while at" this place ([LocationTrigger] id); null = anywhere. Added in DB version 13. */
    val requiredPlaceId: Long? = null,
) {
    fun runsOn(day: DayOfWeek): Boolean = daysMask and dayBit(day) != 0

    companion object {
        fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)
        const val EVERY_DAY = 0b111_1111
    }
}
