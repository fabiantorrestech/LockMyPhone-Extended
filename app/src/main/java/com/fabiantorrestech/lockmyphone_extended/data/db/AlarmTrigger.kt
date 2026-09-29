package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** How a trigger recognises "its" alarm among all alarms from the same app. */
/** Which alarm a trigger follows. */
enum class AlarmTriggerKind {
    /** One learned alarm (by its ID or label). */
    SPECIFIC_ALARM,

    /** The first alarm dismissed each day from any of the chosen apps. Timers never count. */
    FIRST_ALARM,
}

/** Which days an alarm trigger is active on. */
enum class AlarmActiveRule {
    EVERY_DAY,

    /** Only on the weekdays in the trigger's `daysMask`. */
    WEEKDAYS,

    /** The next time only; the trigger turns itself off after it fires. */
    ONCE,

    /** Only on the listed dates. */
    DATES,
}

enum class AlarmMatchMode {
    /** The ringing notification's ID. QRAlarm uses the alarm's own ID, so it's stable per alarm. */
    NOTIFICATION_ID,

    /**
     * The ringing notification's title, e.g. a Google Clock alarm's label. Google Clock uses the
     * same notification ID for every alarm, and its text includes the weekday.
     */
    TITLE,
}

/**
 * Starts a lock when one specific alarm is dismissed. The alarm is learned from one ring,
 * which fills in [packageName] … [text].
 *
 * @param profileId the profile to start, or null for Quick lock.
 * @param delayMinutes wait this long after the dismissal before locking (0 = right away).
 * @param expectedEveryDays how often the alarm normally rings. If it isn't seen for longer, the app
 *   warns that it may have been deleted or turned off. Added in DB version 4.
 */
@Entity(
    tableName = "alarm_triggers",
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
data class AlarmTrigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val packageName: String,
    val channelId: String?,
    val notificationId: Int?,
    val title: String?,
    val text: String?,
    val matchMode: AlarmMatchMode,
    val profileId: Long?,
    val delayMinutes: Int,
    val enabled: Boolean,
    @ColumnInfo(defaultValue = "1") val expectedEveryDays: Int = 1,
    /** An icon ("mi:<Name>" or an emoji); null shows the lock's icon. Added in DB version 8. */
    val icon: String? = null,
    /** Position in the trigger list, set by dragging. Added in DB version 8. */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    /** Added in DB version 9, like the fields below. */
    @ColumnInfo(defaultValue = "SPECIFIC_ALARM") val kind: AlarmTriggerKind = AlarmTriggerKind.SPECIFIC_ALARM,
    /** For [AlarmTriggerKind.FIRST_ALARM]: the alarm apps whose alarms count. */
    @ColumnInfo(defaultValue = "") val firstAlarmPackages: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "EVERY_DAY") val activeRule: AlarmActiveRule = AlarmActiveRule.EVERY_DAY,
    /** For [AlarmActiveRule.WEEKDAYS]: one bit per weekday, as in [LockSchedule.dayBit]. */
    @ColumnInfo(defaultValue = "127") val daysMask: Int = LockSchedule.EVERY_DAY,
    /** For [AlarmActiveRule.DATES]: ISO dates, e.g. "2026-10-03". */
    @ColumnInfo(defaultValue = "") val dates: Set<String> = emptySet(),
    /** The last day (ISO) a first-alarm trigger fired, so it fires once per day. */
    val lastFiredDay: String? = null,
)
