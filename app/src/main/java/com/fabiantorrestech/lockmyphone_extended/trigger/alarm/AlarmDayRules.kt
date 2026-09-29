package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which days an alarm trigger is active on, and the "first alarm of the day" rule. Pure logic;
 * the time and time zone are passed in.
 */
object AlarmDayRules {

    const val DEFAULT_DAY_START_MINUTES = 2 * 60

    /**
     * The day an alarm belongs to. A day starts at [dayStartMinutes] past midnight, so with the
     * default 2:00 AM an alarm at 1:30 AM still counts toward the previous day.
     */
    fun logicalDay(nowMs: Long, dayStartMinutes: Int, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(zone).minusMinutes(dayStartMinutes.toLong()).toLocalDate()

    fun isActiveOn(trigger: AlarmTrigger, day: LocalDate): Boolean =
        isActiveOn(trigger.activeRule, trigger.daysMask, trigger.dates, day)

    /** The day rule itself; location triggers use it too. */
    fun isActiveOn(rule: AlarmActiveRule, daysMask: Int, dates: Set<String>, day: LocalDate): Boolean = when (rule) {
        AlarmActiveRule.EVERY_DAY, AlarmActiveRule.ONCE -> true
        AlarmActiveRule.WEEKDAYS -> daysMask and LockSchedule.dayBit(day.dayOfWeek) != 0
        AlarmActiveRule.DATES -> day.toString() in dates
    }

    /**
     * Whether a dismissal on [day] should start the lock. A "first alarm" trigger fires only for
     * the first dismissal of each day.
     */
    fun shouldFire(trigger: AlarmTrigger, day: LocalDate): Boolean {
        if (!trigger.enabled || !isActiveOn(trigger, day)) return false
        return trigger.kind != AlarmTriggerKind.FIRST_ALARM || trigger.lastFiredDay != day.toString()
    }
}
