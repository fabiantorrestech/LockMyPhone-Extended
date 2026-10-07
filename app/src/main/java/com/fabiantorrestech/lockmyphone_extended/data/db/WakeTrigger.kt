package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Morning lock: locks once a day, at the first of
 * 1. an alarm from [alarmPackages] dismissed inside the wake-up window ([alarmFirst]), or
 * 2. the first unlock inside the window after at least [minSleepMinutes] without one, if a check
 *    says you were sleeping: charged for [chargingMinutes] ([checkCharging]) or at one of
 *    [sleepPlaceIds] ([checkPlace]; Wi-Fi first, then GPS).
 * Added in DB version 24 (M22). Spec: doc/plans/2026-10-03-morning-lock.md.
 *
 * @param windowStartMin the window's start, in minutes past midnight.
 * @param windowEndMin the window's end (exclusive), in minutes past midnight; after the start.
 * @param daysMask the weekdays it's active on, one bit per day as in [LockSchedule.dayBit].
 * @param sleepPlaceIds places ([LocationTrigger] IDs) where you sleep.
 * @param lastFiredDay the day (ISO date) it last locked, so it fires once a day.
 * @param profileId the lock to start; null = Quick lock.
 * @param yieldToAlarms an alarm lock firing on the same day stands this down for the day:
 *   any alarm lock, or only [yieldAlarmIds] when that's not empty. Added in DB version 25 (M22.1),
 *   like the fields below.
 * @param countFromScreenOn the lock length counts from the first screen-on inside the window
 *   (you were awake then), not from the unlock.
 * @param altDaysMask on these days the window ends at [altEndMin] instead (0 = none).
 * @param lateCheck from [lateCheckAfterMin] on, ask "Just waking up?" (15 s, then it locks;
 *   holding "I've been up a while" for 5 s skips today).
 */
@Entity(
    tableName = "wake_triggers",
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
data class WakeTrigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val profileId: Long? = null,
    val lockMinutes: Int = DEFAULT_LOCK_MINUTES,
    val windowStartMin: Int = DEFAULT_WINDOW_START,
    val windowEndMin: Int = DEFAULT_WINDOW_END,
    val daysMask: Int = LockSchedule.EVERY_DAY,
    val alarmFirst: Boolean = true,
    val alarmPackages: Set<String> = emptySet(),
    val minSleepMinutes: Int = DEFAULT_MIN_SLEEP_MINUTES,
    val sleepPlaceIds: Set<Long> = emptySet(),
    val checkCharging: Boolean = true,
    val chargingMinutes: Int = DEFAULT_CHARGING_MINUTES,
    val checkPlace: Boolean = true,
    val lastFiredDay: String? = null,
    @ColumnInfo(defaultValue = "1") val yieldToAlarms: Boolean = true,
    @ColumnInfo(defaultValue = "") val yieldAlarmIds: Set<Long> = emptySet(),
    @ColumnInfo(defaultValue = "1") val countFromScreenOn: Boolean = true,
    val altEndMin: Int? = null,
    @ColumnInfo(defaultValue = "0") val altDaysMask: Int = 0,
    @ColumnInfo(defaultValue = "0") val lateCheck: Boolean = false,
    @ColumnInfo(defaultValue = "660") val lateCheckAfterMin: Int = DEFAULT_LATE_CHECK_AFTER,
) {
    companion object {
        const val DEFAULT_LOCK_MINUTES = 60
        const val DEFAULT_WINDOW_START = 5 * 60
        const val DEFAULT_WINDOW_END = 14 * 60
        const val DEFAULT_MIN_SLEEP_MINUTES = 4 * 60
        const val DEFAULT_CHARGING_MINUTES = 2 * 60
        const val DEFAULT_LATE_CHECK_AFTER = 11 * 60
        val LOCK_MINUTES_RANGE = 5..240
        val MIN_SLEEP_RANGE = 3 * 60..8 * 60
        val CHARGING_RANGE = 60..4 * 60
    }
}
