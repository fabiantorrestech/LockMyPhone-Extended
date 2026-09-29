package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** How long a calendar-started lock lasts. */
enum class CalendarLengthMode {
    /** From the event's start to its end. */
    EVENT_END,

    /** A set length from the event's start. */
    FIXED,
}

/**
 * Starts a lock from calendar events. Added in DB version 12.
 *
 * @param calendarIds the phone's calendar IDs to watch; empty = all calendars.
 * @param keywords any of these in the title (case-insensitive) matches; empty = any event.
 * @param searchNotesAndLocation also look for the keywords in the event's notes and location.
 * @param includeAllDay all-day events count too (they'd lock the whole day).
 * @param skipDeclined ignore events you declined.
 * @param skipFree ignore events that show you as "free".
 * @param fixedDurationSec with [CalendarLengthMode.FIXED], the lock's length.
 * @param startEarlyMinutes lock this many minutes before the event starts.
 * @param profileId the lock to start; null = Quick lock.
 */
@Entity(
    tableName = "calendar_triggers",
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
data class CalendarTrigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val calendarIds: Set<String> = emptySet(),
    val keywords: Set<String> = emptySet(),
    val searchNotesAndLocation: Boolean = false,
    val includeAllDay: Boolean = false,
    val skipDeclined: Boolean = true,
    val skipFree: Boolean = false,
    val lengthMode: CalendarLengthMode = CalendarLengthMode.EVENT_END,
    val fixedDurationSec: Long = DEFAULT_FIXED_DURATION_SEC,
    val startEarlyMinutes: Int = 0,
    val profileId: Long? = null,
    /** "Only while at" this place ([LocationTrigger] id); null = anywhere. Added in DB version 13. */
    val requiredPlaceId: Long? = null,
) {
    companion object {
        const val DEFAULT_FIXED_DURATION_SEC = 60L * 60
        val START_EARLY_RANGE = 0..120
    }
}
