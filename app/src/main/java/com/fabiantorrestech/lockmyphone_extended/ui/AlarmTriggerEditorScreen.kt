package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmMatchMode
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmMatcher
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.QrAlarmUpcoming
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Creates or edits an alarm trigger:
 * - Basics: name, icon, on/off
 * - Which alarm: one learned alarm, or the first alarm dismissed each day from chosen apps
 * - Active on: every day, chosen weekdays, once, or specific dates
 * - Lock: which lock, an optional delay, and (for a learned alarm) how often it should ring
 *
 * @param learningWaiting true while the app waits for the alarm to ring.
 * @param notificationAccess false if the listener can't see notifications yet.
 * @param firstAlarmApps the installed alarm apps a "first alarm" trigger can follow.
 * @param dayStartMinutes when a day starts for the day rules (Settings), for the hint.
 */
@Composable
fun AlarmTriggerEditorScreen(
    draft: AlarmTrigger,
    appName: String?,
    profiles: List<LockProfile>,
    learningWaiting: Boolean,
    learningRejectedTimer: Boolean,
    notificationAccess: Boolean,
    learnedTime: String?,
    upcomingQrAlarms: List<QrAlarmUpcoming.Upcoming>,
    firstAlarmApps: List<LaunchableApp>,
    dayStartMinutes: Int,
    onRefreshUpcoming: () -> Unit,
    onPickUpcoming: (QrAlarmUpcoming.Upcoming) -> Unit,
    onDraftChange: (AlarmTrigger) -> Unit,
    onPickApp: () -> Unit,
    onLearn: () -> Unit,
    onGrantAccess: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The hold note and "Test now"; null for a new automation's editor or none at all. */
    automation: AutomationEditorExtras? = null,
) {
    var editing by remember { mutableStateOf<TriggerDialog?>(null) }
    val learned = draft.packageName.isNotEmpty() && draft.notificationId != null
    val isNew = draft.id == 0L
    val targetProfile = profiles.firstOrNull { it.id == draft.profileId }
    val alarmChosen = when (draft.kind) {
        AlarmTriggerKind.SPECIFIC_ALARM -> learned
        AlarmTriggerKind.FIRST_ALARM -> draft.firstAlarmPackages.isNotEmpty()
    }
    val daysChosen = activeOnComplete(draft.activeRule, draft.daysMask, draft.dates)

    EditorScaffold(
        title = stringResource(if (isNew) R.string.alarm_trigger_new else R.string.alarm_trigger_edit_title),
        onBack = onBack,
        saveEnabled = draft.name.isNotBlank() && alarmChosen && daysChosen,
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = TriggerDialog.DELETE }),
        modifier = modifier,
        automation = automation,
    ) {
        if (!notificationAccess) {
            Text(stringResource(R.string.alarm_trigger_needs_access), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_row_enable, stringResource(R.string.setup_notification_access)))
            }
        }

        SettingsSection(stringResource(R.string.section_basics)) {
            Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it.take(40))) },
                    label = { Text(stringResource(R.string.alarm_trigger_name)) },
                    placeholder = { Text(stringResource(R.string.alarm_trigger_name_hint)) },
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

        SettingsSection(stringResource(R.string.section_which_alarm)) {
            RadioSettingsRow(
                title = stringResource(R.string.alarm_kind_specific),
                summary = stringResource(R.string.alarm_kind_specific_desc),
                selected = draft.kind == AlarmTriggerKind.SPECIFIC_ALARM,
                onSelect = { onDraftChange(draft.copy(kind = AlarmTriggerKind.SPECIFIC_ALARM)) },
            )
            RadioSettingsRow(
                title = stringResource(R.string.alarm_kind_first),
                summary = stringResource(R.string.alarm_kind_first_desc),
                selected = draft.kind == AlarmTriggerKind.FIRST_ALARM,
                onSelect = {
                    // Start with every installed alarm app ticked; untick the ones to ignore.
                    val packages = draft.firstAlarmPackages.ifEmpty { firstAlarmApps.map { it.packageName }.toSet() }
                    onDraftChange(draft.copy(kind = AlarmTriggerKind.FIRST_ALARM, firstAlarmPackages = packages))
                },
            )
            SectionDivider()
            when (draft.kind) {
                AlarmTriggerKind.SPECIFIC_ALARM -> SpecificAlarmContent(
                    draft = draft,
                    appName = appName,
                    learned = learned,
                    learningWaiting = learningWaiting,
                    learningRejectedTimer = learningRejectedTimer,
                    notificationAccess = notificationAccess,
                    learnedTime = learnedTime,
                    upcomingQrAlarms = upcomingQrAlarms,
                    onPickApp = onPickApp,
                    onLearn = onLearn,
                    onRefreshUpcoming = onRefreshUpcoming,
                    onPickUpcoming = onPickUpcoming,
                )
                AlarmTriggerKind.FIRST_ALARM -> FirstAlarmApps(
                    apps = firstAlarmApps,
                    selected = draft.firstAlarmPackages,
                    onChange = { onDraftChange(draft.copy(firstAlarmPackages = it)) },
                )
            }
        }

        ActiveOnSection(
            rule = draft.activeRule,
            daysMask = draft.daysMask,
            dates = draft.dates,
            dayStartMinutes = dayStartMinutes,
            onRuleChange = { onDraftChange(draft.copy(activeRule = it)) },
            onDaysMaskChange = { onDraftChange(draft.copy(daysMask = it)) },
            onDatesChange = { onDraftChange(draft.copy(dates = it)) },
        )

        SettingsSection(stringResource(R.string.alarm_trigger_lock)) {
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.lock_target),
                summary = targetProfile?.name ?: stringResource(R.string.quick_lock),
                onClick = { editing = TriggerDialog.TARGET },
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.HourglassEmpty,
                title = stringResource(R.string.alarm_trigger_delay_title),
                summary = if (draft.delayMinutes <= 0) {
                    stringResource(R.string.alarm_trigger_right_away)
                } else {
                    stringResource(R.string.alarm_trigger_after, draft.delayMinutes)
                },
                onClick = { editing = TriggerDialog.DELAY },
            )
            if (draft.kind == AlarmTriggerKind.SPECIFIC_ALARM) {
                SectionDivider()
                SettingsRow(
                    icon = Icons.Filled.Update,
                    title = stringResource(R.string.alarm_trigger_expected_title),
                    summary = stringResource(R.string.alarm_trigger_expected_summary, draft.expectedEveryDays),
                    onClick = { editing = TriggerDialog.EXPECTED },
                )
            }
        }
    }

    when (editing) {
        TriggerDialog.TARGET -> ChoiceDialog(
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
        TriggerDialog.DELAY -> NumberDialog(
            title = stringResource(R.string.alarm_trigger_delay_title),
            label = stringResource(R.string.alarm_trigger_delay),
            initial = draft.delayMinutes,
            range = 0..MAX_DELAY_MINUTES,
            onConfirm = {
                onDraftChange(draft.copy(delayMinutes = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        TriggerDialog.EXPECTED -> NumberDialog(
            title = stringResource(R.string.alarm_trigger_expected_title),
            label = stringResource(R.string.days_label),
            initial = draft.expectedEveryDays,
            range = 1..MAX_EXPECTED_DAYS,
            hint = stringResource(R.string.alarm_trigger_expected_hint),
            onConfirm = {
                onDraftChange(draft.copy(expectedEveryDays = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        TriggerDialog.DELETE -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.alarm_trigger_delete_title, draft.name)) },
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

private enum class TriggerDialog { TARGET, DELAY, EXPECTED, DELETE }

/** The app, then the learned alarm (or how to learn it), plus QRAlarm's notes and upcoming picker. */
@Composable
private fun SpecificAlarmContent(
    draft: AlarmTrigger,
    appName: String?,
    learned: Boolean,
    learningWaiting: Boolean,
    learningRejectedTimer: Boolean,
    notificationAccess: Boolean,
    learnedTime: String?,
    upcomingQrAlarms: List<QrAlarmUpcoming.Upcoming>,
    onPickApp: () -> Unit,
    onLearn: () -> Unit,
    onRefreshUpcoming: () -> Unit,
    onPickUpcoming: (QrAlarmUpcoming.Upcoming) -> Unit,
) {
    val matchMode = AlarmMatcher.effectiveMatchMode(draft)
    val isQrAlarm = QrAlarmUpcoming.isQrAlarm(draft.packageName)
    SettingsRow(
        icon = Icons.Filled.Alarm,
        title = stringResource(R.string.alarm_trigger_app),
        summary = appName ?: stringResource(R.string.alarm_trigger_pick_app),
        onClick = onPickApp,
    )
    if (draft.packageName.isEmpty() || !notificationAccess) return
    SectionDivider()
    Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                learningWaiting && learningRejectedTimer -> stringResource(R.string.alarm_trigger_timer_rejected)
                learningWaiting -> stringResource(R.string.alarm_trigger_waiting)
                // "Internal alarm #N" is QRAlarm's own numbering; other apps just have a notification number.
                learned && matchMode == AlarmMatchMode.NOTIFICATION_ID && !isQrAlarm ->
                    stringResource(R.string.alarm_trigger_learned_notification, draft.notificationId ?: 0)
                learned && matchMode == AlarmMatchMode.NOTIFICATION_ID && learnedTime != null ->
                    stringResource(R.string.alarm_trigger_learned_id_time, draft.notificationId ?: 0, learnedTime)
                learned && matchMode == AlarmMatchMode.NOTIFICATION_ID ->
                    stringResource(R.string.alarm_trigger_learned_id, draft.notificationId ?: 0)
                learned -> stringResource(R.string.alarm_trigger_learned_text, draft.title.orEmpty())
                else -> stringResource(R.string.alarm_trigger_not_learned)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (learned && matchMode == AlarmMatchMode.TITLE) {
            Text(stringResource(R.string.alarm_trigger_text_warning), style = MaterialTheme.typography.bodySmall)
        }
        if (isQrAlarm && learned) {
            Text(stringResource(R.string.alarm_trigger_internal_id_note, draft.notificationId ?: 0), style = MaterialTheme.typography.bodySmall)
        }
        if (isQrAlarm) {
            Text(
                stringResource(R.string.alarm_trigger_qralarm_dont_delete),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.alarm_trigger_qralarm_note), style = MaterialTheme.typography.bodySmall)
            QrAlarmUpcomingPicker(upcomingQrAlarms, draft.notificationId, onRefreshUpcoming, onPickUpcoming)
        }
        OutlinedButton(onClick = onLearn, enabled = !learningWaiting, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (learned) R.string.alarm_trigger_relearn else R.string.alarm_trigger_learn))
        }
        Text(stringResource(R.string.alarm_trigger_learn_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** Checkboxes for the alarm apps a "first alarm" trigger (or Morning lock) listens to. */
@Composable
fun FirstAlarmApps(apps: List<LaunchableApp>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        if (apps.isEmpty()) {
            Text(stringResource(R.string.alarm_first_no_apps), style = MaterialTheme.typography.bodySmall, modifier = SectionContentPadding)
        }
        apps.forEach { app ->
            val checked = app.packageName in selected
            SettingsRow(
                icon = Icons.Filled.Alarm,
                title = app.label,
                summary = null,
                onClick = { onChange(if (checked) selected - app.packageName else selected + app.packageName) },
                trailing = {
                    Checkbox(checked = checked, onCheckedChange = {
                        onChange(if (checked) selected - app.packageName else selected + app.packageName)
                    })
                },
            )
        }
        Text(stringResource(R.string.alarm_first_hint), style = MaterialTheme.typography.bodySmall, modifier = SectionContentPadding)
    }
}

/**
 * QRAlarm alarms announced in its notifications right now (due within about 2 hours). Tapping
 * one learns it without making it ring.
 */
@Composable
private fun QrAlarmUpcomingPicker(
    upcoming: List<QrAlarmUpcoming.Upcoming>,
    selectedAlarmId: Int?,
    onRefresh: () -> Unit,
    onPick: (QrAlarmUpcoming.Upcoming) -> Unit,
) {
    Text(stringResource(R.string.alarm_trigger_upcoming_title), style = MaterialTheme.typography.titleSmall)
    if (upcoming.isEmpty()) {
        Text(stringResource(R.string.alarm_trigger_upcoming_empty), style = MaterialTheme.typography.bodySmall)
    }
    upcoming.forEach { alarm ->
        val label = stringResource(
            R.string.alarm_trigger_upcoming_item,
            alarm.time ?: stringResource(R.string.alarm_trigger_unknown_time),
            alarm.alarmId,
        )
        if (alarm.alarmId == selectedAlarmId) {
            Button(onClick = { onPick(alarm) }, modifier = Modifier.fillMaxWidth()) { Text("✓ $label") }
        } else {
            OutlinedButton(onClick = { onPick(alarm) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
    TextButton(onClick = onRefresh) { Text(stringResource(R.string.alarm_trigger_upcoming_refresh)) }
}

private const val MAX_DELAY_MINUTES = 240
private const val MAX_EXPECTED_DAYS = 31
