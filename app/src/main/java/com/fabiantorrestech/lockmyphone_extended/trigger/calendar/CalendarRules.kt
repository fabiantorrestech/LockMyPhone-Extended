package com.fabiantorrestech.lockmyphone_extended.trigger.calendar

import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarLengthMode
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger

/**
 * One occurrence of a calendar event (a recurring event has one per occurrence).
 * Times are epoch milliseconds, already in local time for all-day events.
 */
data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val description: String,
    val location: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val declined: Boolean,
    val free: Boolean,
) {
    /** Identifies this occurrence, so a lock isn't started twice for it. */
    val instanceKey: String get() = "$eventId@$beginMs"
}

/** When a matching event locks the phone. */
data class CalendarWindow(val trigger: CalendarTrigger, val event: CalendarEvent, val startMs: Long, val endMs: Long)

/** Which events a calendar trigger matches, and when they lock. Pure logic. */
object CalendarRules {

    fun matches(trigger: CalendarTrigger, event: CalendarEvent): Boolean {
        if (trigger.calendarIds.isNotEmpty() && event.calendarId.toString() !in trigger.calendarIds) return false
        if (event.allDay && !trigger.includeAllDay) return false
        if (trigger.skipDeclined && event.declined) return false
        if (trigger.skipFree && event.free) return false
        val keywords = trigger.keywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return true
        val haystack = buildList {
            add(event.title)
            if (trigger.searchNotesAndLocation) {
                add(event.description)
                add(event.location)
            }
        }
        return keywords.any { keyword -> haystack.any { it.contains(keyword, ignoreCase = true) } }
    }

    fun window(trigger: CalendarTrigger, event: CalendarEvent): CalendarWindow {
        val start = event.beginMs - trigger.startEarlyMinutes * 60_000L
        val end = when (trigger.lengthMode) {
            CalendarLengthMode.EVENT_END -> event.endMs
            CalendarLengthMode.FIXED -> event.beginMs + trigger.fixedDurationSec * 1_000
        }
        return CalendarWindow(trigger, event, start, end)
    }

    /** Every matching window of [triggers] among [events], earliest first. */
    fun windows(triggers: List<CalendarTrigger>, events: List<CalendarEvent>): List<CalendarWindow> =
        triggers.filter { it.enabled }
            .flatMap { trigger -> events.filter { matches(trigger, it) }.map { window(trigger, it) } }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }

    /** Parses "Focus, Deep work ,#lock" into keywords. */
    fun parseKeywords(text: String): Set<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
