package com.fabiantorrestech.lockmyphone_extended.trigger

import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleCalculator
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * An automatic commitment that locks at known weekly times: a schedule, or a place's time slots.
 * Each window has a weekly schedule's shape (days, start, end; overnight allowed).
 *
 * @param key tells commitments apart, e.g. "schedule:3" or "place:7".
 */
data class TimedCommitment(val key: String, val name: String, val windows: List<LockSchedule>)

/** [other]'s window on [day] (from [startMinute] to [endMinute], minutes of the day) overlaps. */
data class Clash(val other: TimedCommitment, val day: DayOfWeek, val startMinute: Int, val endMinute: Int)

/** One concrete time span of an automation, in epoch millis (end exclusive). */
data class TimeSpan(val startMs: Long, val endMs: Long)

/**
 * Predicts which automatic commitments will clash. Only one lock runs at a time and commitments
 * rank equally, so when two overlap, the one that starts first runs and the other is skipped
 * (ADR 0005). Pure Kotlin.
 */
object ConflictRules {

    private const val MINUTES_PER_DAY = 24 * 60
    private const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY

    /** The first window of another commitment that overlaps any window of [target], or null. */
    fun firstClash(target: TimedCommitment, others: List<TimedCommitment>): Clash? {
        val targetSpans = target.windows.flatMap(::weekSpans)
        for (other in others) {
            if (other.key == target.key) continue
            for (window in other.windows) {
                for (day in DayOfWeek.entries.filter(window::runsOn)) {
                    val span = daySpan(window, day)
                    if (splitAtWeekEnd(span).any { part -> targetSpans.any { overlaps(it, part) } }) {
                        return Clash(other, day, window.startMinuteOfDay, window.endMinuteOfDay)
                    }
                }
            }
        }
        return null
    }

    /** Whether a concrete span (e.g. a calendar event's lock) overlaps any of [others]. */
    fun firstSpanClash(span: TimeSpan, others: List<Pair<String, List<TimeSpan>>>): String? =
        others.firstOrNull { (_, spans) -> spans.any { it.startMs < span.endMs && span.startMs < it.endMs } }?.first

    /**
     * A commitment's windows as concrete spans from the day before [from] to [days] days after it,
     * so a window already running at [from] counts too.
     */
    fun spansBetween(commitment: TimedCommitment, from: ZonedDateTime, days: Int): List<TimeSpan> =
        commitment.windows.flatMap { window ->
            (-1..days).map { from.plusDays(it.toLong()) }
                .filter { window.runsOn(it.dayOfWeek) }
                .map { ScheduleCalculator.windowStartingOn(window, it) }
                .map { TimeSpan(it.start.toInstant().toEpochMilli(), it.end.toInstant().toEpochMilli()) }
        }

    /** The minutes of the week (Monday 0:00 = 0) a window covers, split where the week wraps. */
    private fun weekSpans(window: LockSchedule): List<IntRange> =
        DayOfWeek.entries.filter(window::runsOn).flatMap { splitAtWeekEnd(daySpan(window, it)) }

    /** The window starting on [day], as [start, end) minutes of the week; it may run past the week's end. */
    private fun daySpan(window: LockSchedule, day: DayOfWeek): IntRange {
        val start = (day.value - 1) * MINUTES_PER_DAY + window.startMinuteOfDay
        // An end at or before the start means the window crosses midnight (equal = a full day).
        val length = if (window.endMinuteOfDay > window.startMinuteOfDay) {
            window.endMinuteOfDay - window.startMinuteOfDay
        } else {
            window.endMinuteOfDay + MINUTES_PER_DAY - window.startMinuteOfDay
        }
        return start until start + length
    }

    /** A span past Sunday midnight continues on Monday. */
    private fun splitAtWeekEnd(span: IntRange): List<IntRange> =
        if (span.last < MINUTES_PER_WEEK) {
            listOf(span)
        } else {
            listOf(span.first until MINUTES_PER_WEEK, 0..(span.last - MINUTES_PER_WEEK))
        }

    private fun overlaps(a: IntRange, b: IntRange): Boolean = a.first <= b.last && b.first <= a.last
}
