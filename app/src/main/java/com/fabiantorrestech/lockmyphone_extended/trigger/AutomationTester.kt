package com.fabiantorrestech.lockmyphone_extended.trigger

import android.content.Context
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import com.fabiantorrestech.lockmyphone_extended.data.db.BtDetection
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationSlot
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.data.db.PlaceSignal
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmDayRules
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarTriggerManager
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationTriggerManager
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Presence
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Readings
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RangeRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.WifiRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.knownOneMeterDbm
import com.fabiantorrestech.lockmyphone_extended.trigger.location.nearbyDeviceKey
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleCalculator
import com.fabiantorrestech.lockmyphone_extended.data.db.WakeTrigger
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.SleepPlaceChecker
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.SleepProof
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.WakeRules
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.WakeSignalStore
import java.time.ZoneId
import java.text.DateFormat
import java.time.ZonedDateTime
import java.util.Date

/** How one line of a Test now report came out. */
enum class TestMark { PASS, FAIL, UNKNOWN, INFO }

data class TestLine(val mark: TestMark, val text: String)

/**
 * What Test now found for an automation's current (maybe unsaved) settings.
 *
 * @param wouldLock whether it would start its lock right now.
 * @param verdict one sentence: would it lock now, and if not, why or when.
 */
data class TestReport(val lines: List<TestLine>, val verdict: String, val wouldLock: Boolean)

/**
 * Test now: checks an automation's editor settings against right now, with the same rules the
 * automation itself uses ([ScheduleCalculator], [LocationRules], [AlarmDayRules], the calendar
 * rules). It only reads; it never starts a lock or changes saved state.
 */
class AutomationTester(
    private val context: Context,
    private val locationManager: () -> LocationTriggerManager,
    private val calendarManager: () -> CalendarTriggerManager,
    private val wakeSignals: WakeSignalStore,
    private val sleepPlaces: () -> SleepPlaceChecker,
    private val dayStartMinutes: () -> Int,
) {

    /**
     * Morning lock: "if you woke up now". The gap can't be tested (you just unlocked to run this),
     * so it's shown as info; the day, window and sleeping checks are tested for real.
     *
     * @param alarmAppNames the alarm apps it follows, for the alarm line.
     */
    suspend fun wake(draft: WakeTrigger, places: List<LocationTrigger>, accessibilityOn: Boolean, alarmAppNames: String): TestReport {
        val lines = mutableListOf<TestLine>()
        if (!draft.enabled) lines += TestLine(TestMark.FAIL, string(R.string.test_turned_off))
        lines += TestLine(
            if (accessibilityOn) TestMark.PASS else TestMark.FAIL,
            string(if (accessibilityOn) R.string.test_wake_guard_on else R.string.test_wake_guard_off),
        )
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val day = WakeRules.day(nowMs, zone)
        val dayOk = draft.daysMask and LockSchedule.dayBit(day.dayOfWeek) != 0
        if (!dayOk) lines += TestLine(TestMark.FAIL, string(R.string.test_wake_not_today))
        val doneToday = draft.lastFiredDay == day.toString()
        if (doneToday) lines += TestLine(TestMark.INFO, string(R.string.test_wake_done_today))
        val window = string(R.string.wake_window_value, minuteText(draft.windowStartMin), minuteText(WakeRules.windowEndMin(draft, day)))
        val inWindow = WakeRules.inWindow(draft, nowMs, zone)
        lines += TestLine(
            if (inWindow) TestMark.PASS else TestMark.FAIL,
            string(if (inWindow) R.string.test_wake_window_in else R.string.test_wake_window_out, window),
        )
        // You just unlocked to run this, so the live gap is tiny: judge the last real wake-up instead.
        val wake = wakeSignals.lastWake
        if (wake == null) {
            lines += TestLine(TestMark.INFO, string(R.string.test_wake_no_wake))
        } else {
            val gapOk = wake.gapMs >= draft.minSleepMinutes * 60_000L
            lines += TestLine(
                if (gapOk) TestMark.PASS else TestMark.FAIL,
                string(R.string.test_wake_last_wake, dateTime(wake.atMs), minutesText(wake.gapMs), draft.minSleepMinutes / 60),
            )
        }

        // Every check off: the gap alone decides.
        var slept = !draft.checkCharging && !draft.checkPlace
        if (draft.checkCharging) {
            val chargedMs = wake?.chargedMs ?: 0L
            val enough = chargedMs >= draft.chargingMinutes * 60_000L
            slept = slept || enough
            lines += TestLine(
                if (enough) TestMark.PASS else TestMark.INFO,
                string(R.string.test_wake_charging, minutesText(chargedMs), minutesText(draft.chargingMinutes * 60_000L)),
            )
        }
        if (draft.checkPlace) {
            val sleepPlaces = places.filter { it.id in draft.sleepPlaceIds }
            if (sleepPlaces.isEmpty()) {
                lines += TestLine(TestMark.INFO, string(R.string.test_wake_place_none))
            } else {
                val match = sleepPlaces().check(sleepPlaces)
                slept = slept || match != null
                lines += if (match == null) {
                    TestLine(TestMark.FAIL, string(R.string.test_wake_place_no))
                } else {
                    val via = string(if (match.proof == SleepProof.PLACE_WIFI) R.string.test_wake_via_wifi else R.string.test_wake_via_gps)
                    TestLine(TestMark.PASS, string(R.string.test_wake_place_yes, match.place.name, via))
                }
            }
        }
        if (draft.alarmFirst) lines += TestLine(TestMark.INFO, string(R.string.test_wake_alarm, alarmAppNames))
        if (draft.yieldToAlarms) lines += TestLine(TestMark.INFO, string(R.string.test_wake_yield))

        val wouldLock = draft.enabled && accessibilityOn && dayOk && !doneToday && inWindow && slept
        val verdict = if (wouldLock) {
            string(R.string.test_verdict_wake_yes, minutesText(draft.lockMinutes * 60_000L))
        } else {
            string(R.string.test_verdict_wake_no)
        }
        return TestReport(lines, verdict, wouldLock)
    }

    /** "1h 30m", "45m", "0m". */
    private fun minutesText(ms: Long): String {
        val minutes = ms / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return listOfNotNull(h.takeIf { it > 0 }?.let { "${it}h" }, m.takeIf { it > 0 || h == 0L }?.let { "${it}m" }).joinToString(" ")
    }

    private fun minuteText(minuteOfDay: Int): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(
            Date(ZonedDateTime.now().toLocalDate().atStartOfDay(ZoneId.systemDefault()).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli())
        )

    fun schedule(draft: LockSchedule, places: List<LocationTrigger>): TestReport {
        val now = ZonedDateTime.now()
        val asOn = listOf(draft.copy(enabled = true))
        val window = ScheduleCalculator.activeWindow(asOn, now)
        val lines = mutableListOf<TestLine>()
        if (!draft.enabled) lines += TestLine(TestMark.FAIL, string(R.string.test_turned_off))
        lines += if (window != null) {
            TestLine(TestMark.PASS, string(R.string.test_schedule_in_window, time(window.end.toInstant().toEpochMilli())))
        } else {
            TestLine(TestMark.INFO, string(R.string.test_schedule_not_in_window))
        }
        val placeOk = placeCondition(draft.requiredPlaceId, places, lines)
        ScheduleCalculator.nextStart(asOn, now)?.let { next ->
            lines += TestLine(TestMark.INFO, string(R.string.test_next_start, dateTime(next.start.toInstant().toEpochMilli())))
        }
        val wouldLock = draft.enabled && window != null && placeOk
        val verdict = when {
            wouldLock -> string(R.string.test_verdict_lock_until, time(window!!.end.toInstant().toEpochMilli()))
            else -> string(R.string.test_verdict_no_lock)
        }
        return TestReport(lines, verdict, wouldLock)
    }

    suspend fun place(draft: LocationTrigger, slots: List<LocationSlot>): TestReport {
        val reading = locationManager().readNow(draft)
        val lines = mutableListOf<TestLine>()
        if (!draft.enabled) lines += TestLine(TestMark.FAIL, string(R.string.test_turned_off))
        for (signal in draft.signalSet) {
            // Each signal on its own, then all of them together below.
            val alone = LocationRules.presence(draft.copy(signals = setOf(signal.name), requireAll = true), reading.readings)
            lines += TestLine(mark(alone), "${string(signalName(signal))}: ${signalDetail(signal, draft, reading.readings, reading.heard, alone)}")
        }
        val presence = LocationRules.presence(draft, reading.readings)
        if (draft.signalSet.size > 1) {
            lines += TestLine(TestMark.INFO, string(if (draft.requireAll) R.string.test_place_all else R.string.test_place_any))
        }
        lines += TestLine(mark(presence), string(presenceText(presence)))

        val now = ZonedDateTime.now()
        val today = AlarmDayRules.logicalDay(now.toInstant().toEpochMilli(), dayStartMinutes(), now.zone)
        val here = presence == Presence.INSIDE
        var wouldLock = false
        val verdict = when (draft.mode) {
            LocationMode.CONDITION_ONLY -> string(if (here) R.string.test_verdict_condition_met else R.string.test_verdict_condition_not_met)
            LocationMode.TIME_SLOTS -> {
                val window = ScheduleCalculator.activeWindow(slots.map { it.asSchedule() }, now)
                lines += if (window != null) {
                    TestLine(TestMark.PASS, string(R.string.test_slot_running, time(window.end.toInstant().toEpochMilli())))
                } else {
                    TestLine(TestMark.INFO, string(R.string.test_slot_none))
                }
                wouldLock = draft.enabled && here && window != null
                if (wouldLock) string(R.string.test_verdict_lock_until, time(window!!.end.toInstant().toEpochMilli())) else string(R.string.test_verdict_no_lock)
            }
            else -> {
                val activeToday = AlarmDayRules.isActiveOn(draft.activeRule, draft.daysMask, draft.dates, today)
                if (!activeToday) lines += TestLine(TestMark.FAIL, string(R.string.test_not_active_today))
                when (draft.mode) {
                    LocationMode.WHILE_HERE -> {
                        wouldLock = draft.enabled && activeToday && here
                        string(if (wouldLock) R.string.test_verdict_lock_while_here else R.string.test_verdict_no_lock)
                    }
                    LocationMode.ON_ARRIVAL -> string(if (here) R.string.test_verdict_on_arrival_here else R.string.test_verdict_on_arrival_away)
                    else -> string(if (here) R.string.test_verdict_on_leave_here else R.string.test_verdict_on_leave_away)
                }
            }
        }
        return TestReport(lines, verdict, wouldLock)
    }

    suspend fun calendar(draft: CalendarTrigger, places: List<LocationTrigger>): TestReport {
        val lines = mutableListOf<TestLine>()
        if (!draft.enabled) lines += TestLine(TestMark.FAIL, string(R.string.test_turned_off))
        val manager = calendarManager()
        if (!manager.reader.hasPermission()) {
            lines += TestLine(TestMark.FAIL, string(R.string.test_calendar_no_permission))
            return TestReport(lines, string(R.string.test_verdict_no_lock), false)
        }
        val window = manager.nextMatch(draft)
        val nowMs = System.currentTimeMillis()
        val runningNow = window != null && window.startMs <= nowMs && nowMs < window.endMs
        lines += when {
            window == null -> TestLine(TestMark.INFO, string(R.string.test_calendar_no_match))
            runningNow -> TestLine(TestMark.PASS, string(R.string.test_calendar_now, window.event.title, time(window.endMs)))
            else -> TestLine(TestMark.INFO, string(R.string.test_calendar_next, window.event.title, dateTime(window.startMs)))
        }
        val placeOk = placeCondition(draft.requiredPlaceId, places, lines)
        val wouldLock = draft.enabled && runningNow && placeOk
        val verdict = if (wouldLock) string(R.string.test_verdict_lock_until, time(window!!.endMs)) else string(R.string.test_verdict_no_lock)
        return TestReport(lines, verdict, wouldLock)
    }

    fun alarm(draft: AlarmTrigger, notificationAccess: Boolean): TestReport {
        val lines = mutableListOf<TestLine>()
        if (!draft.enabled) lines += TestLine(TestMark.FAIL, string(R.string.test_turned_off))
        lines += TestLine(
            if (notificationAccess) TestMark.PASS else TestMark.FAIL,
            string(if (notificationAccess) R.string.test_alarm_access_on else R.string.test_alarm_access_off),
        )
        val linked = draft.kind == AlarmTriggerKind.FIRST_ALARM || draft.notificationId != null || draft.title != null
        lines += TestLine(if (linked) TestMark.PASS else TestMark.FAIL, string(if (linked) R.string.test_alarm_linked else R.string.test_alarm_not_linked))
        val nowMs = System.currentTimeMillis()
        val today = AlarmDayRules.logicalDay(nowMs, dayStartMinutes(), ZonedDateTime.now().zone)
        val firesToday = AlarmDayRules.shouldFire(draft.copy(enabled = true), today)
        lines += TestLine(if (firesToday) TestMark.PASS else TestMark.FAIL, string(if (firesToday) R.string.test_alarm_today else R.string.test_alarm_not_today))
        if (draft.delayMinutes > 0) lines += TestLine(TestMark.INFO, string(R.string.test_alarm_delay, draft.delayMinutes))
        lines += TestLine(TestMark.INFO, string(R.string.test_alarm_cant_ring))
        val ready = draft.enabled && notificationAccess && linked && firesToday
        return TestReport(lines, string(if (ready) R.string.test_verdict_alarm_ready else R.string.test_verdict_no_lock), false)
    }

    fun nfc(draft: NfcTag, hasNfc: Boolean, nfcOn: Boolean, linkedLockCount: Int): TestReport {
        val lines = mutableListOf<TestLine>()
        if (!draft.enabled) lines += TestLine(TestMark.FAIL, string(R.string.test_turned_off))
        lines += TestLine(if (hasNfc && nfcOn) TestMark.PASS else TestMark.FAIL, string(if (hasNfc && nfcOn) R.string.test_nfc_on else R.string.test_nfc_off))
        lines += TestLine(if (draft.written) TestMark.PASS else TestMark.FAIL, string(if (draft.written) R.string.test_nfc_written else R.string.test_nfc_not_written))
        lines += TestLine(
            if (linkedLockCount > 0) TestMark.PASS else TestMark.FAIL,
            if (linkedLockCount > 0) string(R.string.test_nfc_links, linkedLockCount) else string(R.string.test_nfc_no_links),
        )
        lines += TestLine(TestMark.INFO, string(R.string.test_nfc_hold))
        val ready = draft.enabled && hasNfc && nfcOn && draft.written && linkedLockCount > 0
        return TestReport(lines, string(if (ready) R.string.test_verdict_nfc_ready else R.string.test_verdict_no_lock), false)
    }

    /** "Only while at…": adds its line and returns whether it's met (true without one). */
    private fun placeCondition(placeId: Long?, places: List<LocationTrigger>, lines: MutableList<TestLine>): Boolean {
        val place = placeId?.let { id -> places.firstOrNull { it.id == id } } ?: return true
        val here = locationManager().isAt(place.id)
        lines += TestLine(if (here) TestMark.PASS else TestMark.FAIL, string(if (here) R.string.test_at_place else R.string.test_not_at_place, place.name))
        return here
    }

    private fun signalDetail(
        signal: PlaceSignal,
        draft: LocationTrigger,
        readings: Readings,
        heard: Map<String, Int>?,
        presence: Presence,
    ): String = when (signal) {
        PlaceSignal.GPS -> readings.fix?.let { fix ->
            val distance = LocationRules.distanceMeters(fix.latitude, fix.longitude, draft.latitude, draft.longitude).toInt()
            string(R.string.test_gps_detail, distance, fix.accuracyMeters.toInt(), draft.radiusMeters)
        } ?: string(R.string.test_gps_no_fix)
        PlaceSignal.WIFI -> when {
            !readings.wifiKnown -> string(R.string.test_wifi_unknown)
            readings.wifi == null -> string(R.string.test_wifi_none)
            WifiRules.matchesAny(draft.wifiNetworks, readings.wifi) ->
                string(R.string.test_wifi_detail, readings.wifi.ssid ?: readings.wifi.bssid)
            else -> string(R.string.test_wifi_other, readings.wifi.ssid ?: readings.wifi.bssid)
        }
        PlaceSignal.BEACON -> rssiDetail(heard, draft.beaconKey, draft.beaconRssiThreshold, draft.knownOneMeterDbm)
        PlaceSignal.BT_DEVICE -> if (draft.btDetection == BtDetection.NEARBY) {
            rssiDetail(heard, nearbyDeviceKey(draft), draft.btRssiThreshold)
        } else {
            string(if (presence == Presence.INSIDE) R.string.test_bt_connected else R.string.test_bt_not_connected)
        }
        PlaceSignal.CHARGER -> readings.charger?.let {
            string(if (it.pluggedIn) (if (it.wireless) R.string.test_charger_wireless else R.string.test_charger_wired) else R.string.test_charger_none)
        } ?: string(R.string.test_signal_unknown)
    }

    /** "heard at -61 dBm (near is -75 or stronger) · ≈ 1.2 m", the distance only when the 1 m signal is known. */
    private fun rssiDetail(heard: Map<String, Int>?, key: String?, threshold: Int, oneMeterDbm: Int? = null): String = when {
        heard == null -> string(R.string.test_scan_unavailable)
        key == null -> string(R.string.test_signal_unknown)
        heard[key] == null -> string(R.string.test_beacon_not_heard, threshold)
        else -> {
            val rssi = heard.getValue(key)
            val estimate = oneMeterDbm?.let { " · ≈ " + RangeRules.formatBoth(RangeRules.estimateDistance(rssi.toDouble(), it)) }
            string(R.string.test_beacon_heard, rssi, threshold) + estimate.orEmpty()
        }
    }

    private fun signalName(signal: PlaceSignal): Int = when (signal) {
        PlaceSignal.GPS -> R.string.signal_gps
        PlaceSignal.WIFI -> R.string.signal_wifi
        PlaceSignal.BEACON -> R.string.signal_beacon
        PlaceSignal.BT_DEVICE -> R.string.signal_bt_device
        PlaceSignal.CHARGER -> R.string.signal_charger
    }

    private fun presenceText(presence: Presence): Int = when (presence) {
        Presence.INSIDE -> R.string.test_place_here
        Presence.OUTSIDE -> R.string.test_place_away
        Presence.UNKNOWN -> R.string.test_place_unknown
    }

    private fun mark(presence: Presence): TestMark = when (presence) {
        Presence.INSIDE -> TestMark.PASS
        Presence.OUTSIDE -> TestMark.FAIL
        Presence.UNKNOWN -> TestMark.UNKNOWN
    }

    private fun string(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    private fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    private fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}
