package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarLengthMode
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarRules
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarWindow
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.DeviceCalendar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Creates or edits a calendar trigger:
 * - Basics: name, icon, on/off
 * - Which events: calendars, keywords (title, optionally notes and location), all-day events,
 *   and a preview of the next match
 * - Status: skip declined / "free" events
 * - Lock: until the event ends or for a set length, start early, which lock
 *
 * @param nextMatch the next event these settings would lock for; null if none (or unknown).
 */
@Composable
fun CalendarEditorScreen(
    draft: CalendarTrigger,
    profiles: List<LockProfile>,
    places: List<LocationTrigger>,
    hasPermission: Boolean,
    calendars: List<DeviceCalendar>,
    nextMatch: CalendarWindow?,
    presetsSec: List<Long>,
    onRequestPermission: () -> Unit,
    onDraftChange: (CalendarTrigger) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<CalendarDialog?>(null) }
    // The keyword text as typed, so commas and spaces aren't swallowed mid-edit.
    var keywordText by remember(draft.id) { mutableStateOf(draft.keywords.joinToString(", ")) }
    val isNew = draft.id == 0L
    val targetProfile = profiles.firstOrNull { it.id == draft.profileId }

    EditorScaffold(
        title = stringResource(if (isNew) R.string.calendar_new else R.string.calendar_edit_title),
        onBack = onBack,
        saveEnabled = draft.name.isNotBlank(),
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = CalendarDialog.DELETE }),
        modifier = modifier,
    ) {
        if (!hasPermission) {
            Text(stringResource(R.string.calendar_needs_permission), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.calendar_allow))
            }
        }

        SettingsSection(stringResource(R.string.section_basics)) {
            Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it.take(40))) },
                    label = { Text(stringResource(R.string.location_name)) },
                    placeholder = { Text(stringResource(R.string.calendar_name_hint)) },
                    singleLine = true,
                    isError = draft.name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                ProfileIconField(
                    icon = draft.icon,
                    name = draft.name,
                    onIconChange = { onDraftChange(draft.copy(icon = it)) },
                    fallbackIcon = automationIcon(null, targetProfile),
                )
            }
            SectionDivider()
            SwitchSettingsRow(
                icon = Icons.Filled.PowerSettingsNew,
                title = stringResource(R.string.alarm_trigger_enabled),
                summary = null,
                checked = draft.enabled,
                onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
            )
        }

        SettingsSection(stringResource(R.string.section_which_events)) {
            SettingsRow(
                icon = Icons.Filled.CalendarMonth,
                title = stringResource(R.string.calendar_calendars),
                summary = if (draft.calendarIds.isEmpty()) {
                    stringResource(R.string.calendar_all_calendars)
                } else {
                    calendars.filter { it.id.toString() in draft.calendarIds }.joinToString { it.name }
                        .ifEmpty { stringResource(R.string.calendar_missing_calendars) }
                },
                onClick = { editing = CalendarDialog.CALENDARS },
                enabled = hasPermission,
            )
            SectionDivider()
            Column(SectionContentPadding) {
                OutlinedTextField(
                    value = keywordText,
                    onValueChange = {
                        keywordText = it
                        onDraftChange(draft.copy(keywords = CalendarRules.parseKeywords(it)))
                    },
                    label = { Text(stringResource(R.string.calendar_keywords)) },
                    placeholder = { Text(stringResource(R.string.calendar_keywords_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.calendar_keywords_help), style = MaterialTheme.typography.bodySmall)
            }
            SectionDivider()
            SwitchSettingsRow(
                icon = Icons.Filled.Notes,
                title = stringResource(R.string.calendar_search_notes),
                summary = null,
                checked = draft.searchNotesAndLocation,
                onCheckedChange = { onDraftChange(draft.copy(searchNotesAndLocation = it)) },
            )
            SectionDivider()
            SwitchSettingsRow(
                icon = Icons.Filled.Today,
                title = stringResource(R.string.calendar_all_day),
                summary = stringResource(R.string.calendar_all_day_hint),
                checked = draft.includeAllDay,
                onCheckedChange = { onDraftChange(draft.copy(includeAllDay = it)) },
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Update,
                title = stringResource(R.string.calendar_next_match),
                summary = when {
                    !hasPermission -> stringResource(R.string.calendar_needs_permission)
                    nextMatch == null -> stringResource(R.string.calendar_no_match)
                    else -> formatNextMatch(nextMatch)
                },
                onClick = null,
            )
        }

        SettingsSection(stringResource(R.string.section_status)) {
            SwitchSettingsRow(
                icon = Icons.Filled.EventBusy,
                title = stringResource(R.string.calendar_skip_declined),
                summary = null,
                checked = draft.skipDeclined,
                onCheckedChange = { onDraftChange(draft.copy(skipDeclined = it)) },
            )
            SectionDivider()
            SwitchSettingsRow(
                icon = Icons.Filled.EventAvailable,
                title = stringResource(R.string.calendar_skip_free),
                summary = null,
                checked = draft.skipFree,
                onCheckedChange = { onDraftChange(draft.copy(skipFree = it)) },
            )
        }

        SettingsSection(stringResource(R.string.section_where)) {
            OnlyWhileAtRow(places, draft.requiredPlaceId) { onDraftChange(draft.copy(requiredPlaceId = it)) }
        }

        SettingsSection(stringResource(R.string.alarm_trigger_lock)) {
            RadioSettingsRow(
                title = stringResource(R.string.calendar_length_event),
                summary = null,
                selected = draft.lengthMode == CalendarLengthMode.EVENT_END,
                onSelect = { onDraftChange(draft.copy(lengthMode = CalendarLengthMode.EVENT_END)) },
            )
            RadioSettingsRow(
                title = stringResource(R.string.calendar_length_fixed),
                summary = null,
                selected = draft.lengthMode == CalendarLengthMode.FIXED,
                onSelect = { onDraftChange(draft.copy(lengthMode = CalendarLengthMode.FIXED)) },
            )
            if (draft.lengthMode == CalendarLengthMode.FIXED) {
                SettingsRow(
                    icon = Icons.Filled.Timer,
                    title = stringResource(R.string.lock_length),
                    summary = formatDurationLabel(draft.fixedDurationSec * 1_000),
                    onClick = { editing = CalendarDialog.LENGTH },
                )
            }
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.HourglassEmpty,
                title = stringResource(R.string.calendar_start_early),
                summary = if (draft.startEarlyMinutes == 0) {
                    stringResource(R.string.calendar_start_on_time)
                } else {
                    stringResource(R.string.wait_minutes, draft.startEarlyMinutes)
                },
                onClick = { editing = CalendarDialog.START_EARLY },
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.lock_target),
                summary = targetProfile?.name ?: stringResource(R.string.quick_lock),
                onClick = { editing = CalendarDialog.TARGET },
            )
        }
    }

    when (editing) {
        CalendarDialog.CALENDARS -> CalendarsDialog(
            calendars = calendars,
            selected = draft.calendarIds,
            onConfirm = {
                onDraftChange(draft.copy(calendarIds = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        CalendarDialog.LENGTH -> DurationDialog(
            title = stringResource(R.string.lock_length),
            initialMs = draft.fixedDurationSec * 1_000,
            presetsSec = presetsSec,
            onConfirm = {
                onDraftChange(draft.copy(fixedDurationSec = it / 1_000))
                editing = null
            },
            onDismiss = { editing = null },
        )
        CalendarDialog.START_EARLY -> NumberDialog(
            title = stringResource(R.string.calendar_start_early),
            label = stringResource(R.string.minutes_label),
            initial = draft.startEarlyMinutes,
            range = CalendarTrigger.START_EARLY_RANGE,
            onConfirm = {
                onDraftChange(draft.copy(startEarlyMinutes = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        CalendarDialog.TARGET -> ChoiceDialog(
            title = stringResource(R.string.lock_target),
            options = listOf<Long?>(null) + profiles.map { it.id },
            selected = draft.profileId,
            label = { id -> profiles.firstOrNull { it.id == id }?.name ?: stringResource(R.string.quick_lock) },
            onSelect = {
                onDraftChange(draft.copy(profileId = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        CalendarDialog.DELETE -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.location_delete_title, draft.name)) },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
        null -> Unit
    }
}

private enum class CalendarDialog { CALENDARS, LENGTH, START_EARLY, TARGET, DELETE }

/** "Tue, Sep 30, 9:00 AM · Focus block". */
private fun formatNextMatch(window: CalendarWindow): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    val at = Instant.ofEpochMilli(window.startMs).atZone(ZoneId.systemDefault()).format(formatter)
    return "$at · ${window.event.title}"
}

/** Tick the calendars to watch; none ticked = all calendars. */
@Composable
private fun CalendarsDialog(
    calendars: List<DeviceCalendar>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_calendars)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.calendar_calendars_hint), style = MaterialTheme.typography.bodySmall)
                calendars.forEach { calendar ->
                    val id = calendar.id.toString()
                    SettingsRow(
                        icon = null,
                        title = calendar.name,
                        summary = calendar.account,
                        onClick = { chosen = if (id in chosen) chosen - id else chosen + id },
                        trailing = {
                            Checkbox(checked = id in chosen, onCheckedChange = { chosen = if (id in chosen) chosen - id else chosen + id })
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
