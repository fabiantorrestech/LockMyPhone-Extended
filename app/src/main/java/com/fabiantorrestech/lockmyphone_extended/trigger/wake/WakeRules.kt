package com.fabiantorrestech.lockmyphone_extended.trigger.wake

import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.data.db.WakeTrigger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Why Morning lock didn't lock (for Test now and the log). */
enum class WakeSkip {
    /** Switched off. */
    OFF,

    /** Today isn't one of its days. */
    NOT_TODAY,

    /** It already locked today. */
    DONE_TODAY,

    /** Now is outside the wake-up window. */
    OUTSIDE_WINDOW,

    /** No unlock was ever recorded, so the gap is unknown. */
    NO_HISTORY,

    /** The last unlock was too recent to have been sleep. */
    SHORT_GAP,

    /** Neither check says you were sleeping. */
    NOT_SLEEPING,

    /** Alarm tier: off, or not one of its alarm apps. */
    ALARM_NOT_WATCHED,

    /** The screen first came on long enough ago that the lock's time is already over (M22.1). */
    AWAKE_EARLIER,
}

/** What confirmed you were sleeping. */
enum class SleepProof { CHARGING, PLACE_WIFI, PLACE_GPS, NO_CHECKS, ALARM }

/** The outcome of one unlock or alarm for one Morning lock. */
sealed interface WakeDecision {
    data class Skip(val reason: WakeSkip) : WakeDecision

    data class Lock(val proof: SleepProof) : WakeDecision

    /** The gap is long enough and charging didn't confirm it; a sleep place has to. */
    data object CheckPlace : WakeDecision
}

/**
 * Morning lock's rules (spec doc/plans/2026-10-03-morning-lock.md §2). Pure logic: the time,
 * zone, last unlock and charging time are passed in. The sleep-place check is done by the caller
 * when the result is [WakeDecision.CheckPlace], because it needs Wi-Fi and a GPS fix.
 */
object WakeRules {

    /** The day an unlock or alarm belongs to: the local date (the window can't cross midnight). */
    fun day(nowMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    fun minuteOfDay(nowMs: Long, zone: ZoneId): Int {
        val time = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /** When the window ends on [day]: earlier on the "ends earlier" days. */
    fun windowEndMin(trigger: WakeTrigger, day: LocalDate): Int {
        val alt = trigger.altEndMin
        return if (alt != null && trigger.altDaysMask and LockSchedule.dayBit(day.dayOfWeek) != 0) alt else trigger.windowEndMin
    }

    fun inWindow(trigger: WakeTrigger, nowMs: Long, zone: ZoneId): Boolean =
        minuteOfDay(nowMs, zone) in trigger.windowStartMin until windowEndMin(trigger, day(nowMs, zone))

    fun lockMs(trigger: WakeTrigger): Long = trigger.lockMinutes * 60_000L

    /**
     * What's left of the lock at [nowMs]. With [WakeTrigger.countFromScreenOn] it counts from the
     * first time the screen came on today inside the window (you were awake then), so a phone left
     * untouched after an early start doesn't lock hours later. Glances outside the window (at night)
     * don't count.
     */
    fun remainingLockMs(trigger: WakeTrigger, nowMs: Long, zone: ZoneId, screenOnsMs: List<Long>): Long {
        if (!trigger.countFromScreenOn) return lockMs(trigger)
        val today = day(nowMs, zone)
        val awakeSince = screenOnsMs
            .filter { it <= nowMs && day(it, zone) == today && inWindow(trigger, it, zone) }
            .minOrNull() ?: nowMs
        return lockMs(trigger) - (nowMs - awakeSince)
    }

    /** A lock shorter than this isn't worth starting. */
    const val MIN_LOCK_MS = 60_000L

    /** The checks every moment shares: on, today, not done yet, inside the window. */
    private fun dayCheck(trigger: WakeTrigger, nowMs: Long, zone: ZoneId): WakeSkip? {
        val day = day(nowMs, zone)
        return when {
            !trigger.enabled -> WakeSkip.OFF
            trigger.daysMask and LockSchedule.dayBit(day.dayOfWeek) == 0 -> WakeSkip.NOT_TODAY
            trigger.lastFiredDay == day.toString() -> WakeSkip.DONE_TODAY
            !inWindow(trigger, nowMs, zone) -> WakeSkip.OUTSIDE_WINDOW
            else -> null
        }
    }

    /**
     * An unlock at [nowMs].
     *
     * @param previousUnlockMs the unlock before this one; null if none was ever recorded.
     * @param chargedMs how long the phone charged between the two.
     * @param screenOnsMs when the screen came on between the two (for [remainingLockMs]).
     */
    fun decideUnlock(
        trigger: WakeTrigger,
        nowMs: Long,
        zone: ZoneId,
        previousUnlockMs: Long?,
        chargedMs: Long,
        screenOnsMs: List<Long> = emptyList(),
    ): WakeDecision {
        dayCheck(trigger, nowMs, zone)?.let { return WakeDecision.Skip(it) }
        if (previousUnlockMs == null) return WakeDecision.Skip(WakeSkip.NO_HISTORY)
        if (nowMs - previousUnlockMs < trigger.minSleepMinutes * 60_000L) return WakeDecision.Skip(WakeSkip.SHORT_GAP)
        if (remainingLockMs(trigger, nowMs, zone, screenOnsMs) < MIN_LOCK_MS) return WakeDecision.Skip(WakeSkip.AWAKE_EARLIER)
        // Cheapest check first; any one is enough.
        if (trigger.checkCharging && chargedMs >= trigger.chargingMinutes * 60_000L) return WakeDecision.Lock(SleepProof.CHARGING)
        if (trigger.checkPlace && trigger.sleepPlaceIds.isNotEmpty()) return WakeDecision.CheckPlace
        // Every check switched off: the gap alone decides (the editor warns about this).
        if (!trigger.checkCharging && !trigger.checkPlace) return WakeDecision.Lock(SleepProof.NO_CHECKS)
        return WakeDecision.Skip(WakeSkip.NOT_SLEEPING)
    }

    /** An alarm from [packageName] was dismissed at [nowMs]. No sleep checks: an alarm is proof enough. */
    fun decideAlarm(trigger: WakeTrigger, nowMs: Long, zone: ZoneId, packageName: String): WakeDecision {
        dayCheck(trigger, nowMs, zone)?.let { return WakeDecision.Skip(it) }
        if (!trigger.alarmFirst || packageName !in trigger.alarmPackages) return WakeDecision.Skip(WakeSkip.ALARM_NOT_WATCHED)
        return WakeDecision.Lock(SleepProof.ALARM)
    }
}
