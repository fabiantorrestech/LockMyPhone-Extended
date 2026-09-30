package com.fabiantorrestech.lockmyphone_extended.ui

import android.content.Context
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.dnd.labelRes
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarLengthMode
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.BtDetection
import com.fabiantorrestech.lockmyphone_extended.data.db.ChargerMode
import com.fabiantorrestech.lockmyphone_extended.data.db.PlaceSignal
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcDuringLock
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTagAction
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** "Every day", "Weekdays", "Weekends" or e.g. "Mon, Wed, Fri". */
fun formatDays(context: Context, daysMask: Int): String {
    val weekdays = DayOfWeek.entries.take(5).fold(0) { acc, d -> acc or LockSchedule.dayBit(d) }
    val weekend = LockSchedule.dayBit(DayOfWeek.SATURDAY) or LockSchedule.dayBit(DayOfWeek.SUNDAY)
    return when (daysMask) {
        LockSchedule.EVERY_DAY -> context.getString(R.string.days_every_day)
        weekdays -> context.getString(R.string.days_weekdays)
        weekend -> context.getString(R.string.days_weekends)
        0 -> context.getString(R.string.days_none)
        else -> DayOfWeek.entries
            .filter { daysMask and LockSchedule.dayBit(it) != 0 }
            .joinToString { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
}

/** Minutes since midnight in the user's short time format, e.g. "10:00 PM" or "22:00". */
fun formatMinuteOfDay(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** "Weekdays · 10:00 PM → 7:00 AM · Night" */
fun formatSchedule(context: Context, schedule: LockSchedule, profileName: String?): String {
    val base = "${formatDays(context, schedule.daysMask)} · " +
        "${formatMinuteOfDay(schedule.startMinuteOfDay)} → ${formatMinuteOfDay(schedule.endMinuteOfDay)}"
    val withProfile = if (profileName != null) "$base · $profileName" else base
    return if (schedule.enabled) withProfile else context.getString(R.string.schedule_off, withProfile)
}

/**
 * A profile's settings, one per line:
 * "0h 30m 00s" / "3 apps" / "early unlock after 10 min" / "🔕 DND (alarms only)" / "◐ grayscale"
 */
fun formatProfileSummary(context: Context, profile: LockProfile): String {
    val parts = mutableListOf(
        formatDurationLabel(profile.defaultDurationSec * 1_000),
        context.resources.getQuantityString(R.plurals.apps_count, profile.allowedPackages.size, profile.allowedPackages.size),
    )
    parts += if (profile.bypassEnabled) {
        context.getString(R.string.profile_summary_bypass, profile.bypassWaitMinutes)
    } else {
        context.getString(R.string.profile_summary_no_bypass)
    }
    if (profile.dndEnabled) {
        parts += context.getString(R.string.profile_summary_dnd, context.getString(profile.dndLevel.labelRes()).lowercase())
    }
    if (profile.grayscale) parts += context.getString(R.string.profile_summary_grayscale)
    return parts.joinToString("\n")
}

/** "“Focus”, “#lock” → Night · until the event ends" or "Any event → Quick lock · 45m (off)". */
fun formatCalendarTrigger(context: Context, trigger: CalendarTrigger, profileName: String?): String {
    val what = if (trigger.keywords.isEmpty()) {
        context.getString(R.string.calendar_any_event)
    } else {
        trigger.keywords.joinToString { "“$it”" }
    }
    val lock = profileName ?: context.getString(R.string.quick_lock)
    val length = when (trigger.lengthMode) {
        CalendarLengthMode.EVENT_END -> context.getString(R.string.calendar_until_end)
        CalendarLengthMode.FIXED -> formatPresetLabel(trigger.fixedDurationSec)
    }
    val summary = "$what → $lock · $length"
    return if (trigger.enabled) summary else context.getString(R.string.schedule_off, summary)
}

/** "Starts Morning · 1h", "During a lock: starts the early-unlock wait", "Starts Quick lock · during a lock: ends it". */
fun formatNfcTag(context: Context, tag: NfcTag, profileName: String?): String {
    val lock = profileName ?: context.getString(R.string.quick_lock)
    val start = tag.durationSec?.let { "$lock · ${formatPresetLabel(it)}" } ?: lock
    val duringLock = context.getString(
        when (tag.duringLock) {
            NfcDuringLock.START_BYPASS_WAIT -> R.string.nfc_during_bypass_short
            NfcDuringLock.END_IF_ALLOWED -> R.string.nfc_during_end_allowed_short
            NfcDuringLock.END_ANY -> R.string.nfc_during_end_any_short
        }
    )
    val summary = when (tag.action) {
        NfcTagAction.START -> context.getString(R.string.nfc_summary_start, start)
        NfcTagAction.END -> context.getString(R.string.nfc_summary_end, duringLock)
        NfcTagAction.TOGGLE -> context.getString(R.string.nfc_summary_toggle, start, duringLock)
    }
    val shown = if (tag.enabled) summary else context.getString(R.string.schedule_off, summary)
    return if (tag.endsLocks && !tag.written) shown + "\n" + context.getString(R.string.nfc_card_not_written) else shown
}

/** "While here → Night · 100 m", "On arrival → Quick lock · Wi-Fi Home · Sat, Sun (off)". */
fun formatLocationTrigger(context: Context, trigger: LocationTrigger, profileName: String?): String {
    val lock = profileName ?: context.getString(R.string.quick_lock)
    // e.g. "100 m + beacon Desk" (all needed) or "Wi-Fi Home / charger" (any one).
    val parts = buildList {
        if (trigger.usesGps) add(context.getString(R.string.meters_value, trigger.radiusMeters))
        if (trigger.uses(PlaceSignal.WIFI)) add(context.getString(R.string.location_summary_wifi, trigger.wifiSsid ?: trigger.wifiBssid ?: "?"))
        if (trigger.uses(PlaceSignal.BEACON)) add(context.getString(R.string.location_summary_beacon, trigger.beaconName ?: "?"))
        if (trigger.uses(PlaceSignal.BT_DEVICE)) {
            val summary = if (trigger.btDetection == BtDetection.NEARBY) R.string.location_summary_bt_near else R.string.location_summary_bt_connected
            add(context.getString(summary, trigger.btName ?: "?"))
        }
        if (trigger.usesCharger) {
            add(context.getString(if (trigger.chargerMode == ChargerMode.WIRELESS) R.string.location_summary_wireless else R.string.location_summary_charger))
        }
    }
    val place = parts.joinToString(if (trigger.requireAll) " + " else " / ")
    val days = when {
        // Time slots carry their own days.
        trigger.mode == LocationMode.TIME_SLOTS || trigger.mode == LocationMode.CONDITION_ONLY -> null
        trigger.activeRule == AlarmActiveRule.EVERY_DAY -> null
        trigger.activeRule == AlarmActiveRule.WEEKDAYS -> formatDays(context, trigger.daysMask)
        trigger.activeRule == AlarmActiveRule.ONCE -> context.getString(R.string.active_once)
        else -> context.resources.getQuantityString(R.plurals.dates_count, trigger.dates.size, trigger.dates.size)
    }
    val summary = listOfNotNull("${context.getString(locationModeLabel(trigger.mode))} → $lock", place, days).joinToString(" · ")
    return if (trigger.enabled) summary else context.getString(R.string.schedule_off, summary)
}

/**
 * "QRAlarm → Morning · right away", "First alarm (Clock, QRAlarm) → Morning · Sat, Sun" or
 * "Clock → Quick lock · after 5 min (off)".
 */
fun formatAlarmTrigger(context: Context, trigger: AlarmTrigger, appLabel: (String) -> String, profileName: String?): String {
    val source = when (trigger.kind) {
        AlarmTriggerKind.SPECIFIC_ALARM -> appLabel(trigger.packageName)
        AlarmTriggerKind.FIRST_ALARM -> context.getString(
            R.string.alarm_first_summary,
            trigger.firstAlarmPackages.sorted().joinToString { appLabel(it) },
        )
    }
    val lock = profileName ?: context.getString(R.string.quick_lock)
    val delay = if (trigger.delayMinutes <= 0) {
        context.getString(R.string.alarm_trigger_right_away)
    } else {
        context.getString(R.string.alarm_trigger_after, trigger.delayMinutes)
    }
    val days = when (trigger.activeRule) {
        AlarmActiveRule.EVERY_DAY -> null
        AlarmActiveRule.WEEKDAYS -> formatDays(context, trigger.daysMask)
        AlarmActiveRule.ONCE -> context.getString(R.string.active_once)
        AlarmActiveRule.DATES -> context.resources.getQuantityString(R.plurals.dates_count, trigger.dates.size, trigger.dates.size)
    }
    val summary = listOfNotNull("$source → $lock", days, delay).joinToString(" · ")
    return if (trigger.enabled) summary else context.getString(R.string.schedule_off, summary)
}

