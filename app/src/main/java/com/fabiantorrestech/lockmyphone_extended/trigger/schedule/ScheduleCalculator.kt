package com.fabiantorrestech.lockmyphone_extended.trigger.schedule

import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** One concrete run of a weekly schedule. */
data class ScheduleWindow(
    val schedule: LockSchedule,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
)

/**
 * Turns weekly schedules into concrete time windows. It's pure Kotlin with no Android
 * dependencies; time zones and DST come from java.time.
 */
object ScheduleCalculator {

    /** The window of [schedule] that starts on [date]'s calendar day. */
    fun windowStartingOn(schedule: LockSchedule, date: ZonedDateTime): ScheduleWindow {
        val day = date.truncatedTo(ChronoUnit.DAYS)
        val start = day.with(LocalTime.MIN.plusMinutes(schedule.startMinuteOfDay.toLong()))
        var end = day.with(LocalTime.MIN.plusMinutes(schedule.endMinuteOfDay.toLong()))
        // End at or before the start means the window crosses midnight.
        if (!end.isAfter(start)) end = end.plusDays(1)
        return ScheduleWindow(schedule, start, end)
    }

    /**
     * The enabled window that contains [now], if any. A window that crossed midnight is found
     * by also checking the one that started yesterday. If several match, the one ending last
     * wins.
     */
    fun activeWindow(schedules: List<LockSchedule>, now: ZonedDateTime): ScheduleWindow? =
        schedules.asSequence()
            .filter { it.enabled }
            .flatMap { schedule ->
                sequenceOf(now.minusDays(1), now)
                    .filter { schedule.runsOn(it.dayOfWeek) }
                    .map { windowStartingOn(schedule, it) }
            }
            .filter { !now.isBefore(it.start) && now.isBefore(it.end) }
            .maxByOrNull { it.end }

    /** The next window that starts strictly after [now], looking up to a week ahead. */
    fun nextStart(schedules: List<LockSchedule>, now: ZonedDateTime): ScheduleWindow? =
        schedules.asSequence()
            .filter { it.enabled && it.daysMask != 0 }
            .flatMap { schedule ->
                (0L..7L).asSequence()
                    .map { now.plusDays(it) }
                    .filter { schedule.runsOn(it.dayOfWeek) }
                    .map { windowStartingOn(schedule, it) }
            }
            .filter { it.start.isAfter(now) }
            .minByOrNull { it.start }
}
