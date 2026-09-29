package com.fabiantorrestech.lockmyphone_extended.ui

import android.content.Context
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.dnd.labelRes
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMatch
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
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

/** "While here → Night · 100 m", "On arrival → Quick lock · Wi-Fi Home · Sat, Sun (off)". */
fun formatLocationTrigger(context: Context, trigger: LocationTrigger, profileName: String?): String {
    val lock = profileName ?: context.getString(R.string.quick_lock)
    val wifi = trigger.wifiSsid ?: trigger.wifiBssid
    val place = when {
        trigger.wifiBssid == null || trigger.matchMode == LocationMatch.GPS_ONLY -> context.getString(R.string.meters_value, trigger.radiusMeters)
        trigger.matchMode == LocationMatch.WIFI_ONLY -> context.getString(R.string.location_summary_wifi, wifi)
        trigger.matchMode == LocationMatch.GPS_AND_WIFI -> context.getString(R.string.location_summary_gps_and_wifi, trigger.radiusMeters, wifi)
        else -> context.getString(R.string.location_summary_gps_or_wifi, trigger.radiusMeters, wifi)
    }
    val days = when {
        // Time slots carry their own days.
        trigger.mode == LocationMode.TIME_SLOTS -> null
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

