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
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import java.text.DateFormat
import java.util.Date
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcDuringLock
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcLinkMode
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.NfcRules
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTagAction
import com.fabiantorrestech.lockmyphone_extended.trigger.Clash
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarClash
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
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
    val visibleCount = (profile.allowedPackages - profile.hiddenPackages).size
    val hiddenCount = (profile.hiddenPackages intersect profile.allowedPackages).size
    val parts = mutableListOf(
        formatDurationLabel(profile.defaultDurationSec * 1_000),
        context.resources.getQuantityString(R.plurals.apps_count, visibleCount, visibleCount),
    )
    if (hiddenCount > 0) parts += context.getString(R.string.apps_hidden_count, hiddenCount)
    parts += if (profile.bypassEnabled) {
        if (profile.bypassWaitMinutes == 0) {
            context.getString(R.string.profile_summary_bypass_instant)
        } else {
            context.getString(R.string.profile_summary_bypass, profile.bypassWaitMinutes)
        }
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

/** "⚠ Clashes with Night (Mon 22:00–7:00): whichever starts first runs; the other is skipped." */
fun formatClash(context: Context, clash: Clash): String = context.getString(
    R.string.clash_warning,
    clash.other.name,
    clash.day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
    formatMinuteOfDay(clash.startMinute),
    formatMinuteOfDay(clash.endMinute),
)

/** "⚠ “Focus” (Tue 9:00) clashes with Night: whichever starts first runs; the other is skipped." */
fun formatCalendarClash(context: Context, clash: CalendarClash): String {
    val start = Instant.ofEpochMilli(clash.window.startMs).atZone(ZoneId.systemDefault())
    val at = start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " +
        formatMinuteOfDay(start.hour * 60 + start.minute)
    return context.getString(R.string.calendar_clash_warning, clash.window.event.title, at, clash.otherName)
}

/** "Already in a schedule lock (Night) until 7:00 AM!" for a start that was refused. */
fun formatAlreadyLocked(context: Context, running: LockSession): String {
    val kind = context.getString(lockKindLabel(running.source.kind))
    val name = running.source.name.ifEmpty { context.getString(R.string.quick_lock) }
    val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(running.effectiveEndMs))
    return context.getString(R.string.already_locked, kind, name, until)
}

/** The lock kinds, as words in a sentence ("a schedule lock"). */
fun lockKindLabel(kind: LockKind): Int = when (kind) {
    LockKind.MANUAL -> R.string.lock_kind_manual
    LockKind.NFC -> R.string.lock_kind_nfc
    LockKind.INTENT -> R.string.lock_kind_intent
    LockKind.SCHEDULE -> R.string.lock_kind_schedule
    LockKind.CALENDAR -> R.string.lock_kind_calendar
    LockKind.PLACE -> R.string.lock_kind_place
    LockKind.ALARM -> R.string.lock_kind_alarm
    LockKind.WAKE -> R.string.lock_kind_wake
}

/**
 * The tag's ID, then "Starts Study, Night · 1h", "During a lock: starts the early-unlock wait" or
 * "Starts All locks · during a lock: ends it", and "Not written to the tag yet" when it isn't.
 */
fun formatNfcTag(context: Context, tag: NfcTag, profiles: List<LockProfile>): String {
    val locks = if (tag.linkMode == NfcLinkMode.ALL) {
        context.getString(R.string.nfc_all_locks)
    } else {
        val names = profiles.filter { tag.links(it.id.toString()) }.map { it.name } +
            listOfNotNull(context.getString(R.string.quick_lock).takeIf { tag.links(QUICK_LOCK_ID) })
        names.joinToString(", ").ifEmpty { "?" }
    }
    val start = tag.durationSec?.let { "$locks · ${formatPresetLabel(it)}" } ?: locks
    val duringLock = context.getString(
        when (tag.duringLock) {
            NfcDuringLock.START_BYPASS_WAIT -> R.string.nfc_during_bypass_short
            NfcDuringLock.END_IF_ALLOWED -> R.string.nfc_during_end_allowed_short
            NfcDuringLock.END_ANY -> R.string.nfc_during_end_any_short
        }
    )
    val summary = when (tag.action) {
        NfcTagAction.START -> context.getString(R.string.nfc_summary_start, start) +
            (if (tag.takeOver) " · " + context.getString(R.string.nfc_summary_takes_over) else "")
        NfcTagAction.END -> context.getString(R.string.nfc_summary_end, duringLock)
        NfcTagAction.TOGGLE -> context.getString(R.string.nfc_summary_toggle, start, duringLock)
    }
    // The tag's ID tells which physical tag this is.
    val id = tag.uid.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.nfc_tag_id_short, NfcRules.formatUid(it)) }
    val line = listOfNotNull(id, summary).joinToString("\n")
    val shown = if (tag.enabled) line else context.getString(R.string.schedule_off, line)
    return if (tag.written) shown else shown + "\n" + context.getString(R.string.nfc_card_not_written)
}

/** "While here → Night · 100 m", "On arrival → Quick lock · Wi-Fi Home · Sat, Sun (off)". */
fun formatLocationTrigger(context: Context, trigger: LocationTrigger, profileName: String?): String {
    val lock = profileName ?: context.getString(R.string.quick_lock)
    // e.g. "100 m + beacon Desk" (all needed) or "Wi-Fi Home / charger" (any one).
    val parts = buildList {
        if (trigger.usesGps) add(context.getString(R.string.meters_value, trigger.radiusMeters))
        if (trigger.uses(PlaceSignal.WIFI)) {
            val names = trigger.wifiNetworks.take(2).joinToString(", ") { it.label }.ifEmpty { "?" }
            val more = (trigger.wifiNetworks.size - 2).takeIf { it > 0 }?.let { " (+$it)" }.orEmpty()
            add(context.getString(R.string.location_summary_wifi, names + more))
        }
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

