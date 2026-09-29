package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Creates or edits a weekly schedule: profile, icon, on/off, days, start and end time. */
@Composable
fun ScheduleEditorScreen(
    draft: LockSchedule,
    profiles: List<LockProfile>,
    onDraftChange: (LockSchedule) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ScheduleDialog?>(null) }
    val profile = profiles.firstOrNull { it.id == draft.profileId } ?: profiles.firstOrNull()
    val isNew = draft.id == 0L

    EditorScaffold(
        title = stringResource(if (isNew) R.string.schedule_new else R.string.schedule_edit_title),
        onBack = onBack,
        saveEnabled = draft.daysMask != 0 && profile != null && draft.startMinuteOfDay != draft.endMinuteOfDay,
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = ScheduleDialog.DELETE }),
        modifier = modifier,
    ) {
        Text(formatSchedule(context, draft, profile?.name), style = MaterialTheme.typography.bodyMedium)

        SettingsSection(stringResource(R.string.section_basics)) {
            if (profile != null) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.lock_target),
                    summary = profile.name,
                    onClick = { editing = ScheduleDialog.PROFILE },
                )
                SectionDivider()
                ProfileIconField(
                    icon = draft.icon,
                    name = profile.name,
                    onIconChange = { onDraftChange(draft.copy(icon = it)) },
                    fallbackIcon = profile.icon,
                    modifier = SectionContentPadding,
                )
                SectionDivider()
            }
            SwitchSettingsRow(
                icon = Icons.Filled.PowerSettingsNew,
                title = stringResource(R.string.schedule_enabled),
                summary = null,
                checked = draft.enabled,
                onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
            )
        }

        SettingsSection(stringResource(R.string.section_when)) {
            DayChips(draft.daysMask, onChange = { onDraftChange(draft.copy(daysMask = it)) }, modifier = SectionContentPadding)
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Bedtime,
                title = stringResource(R.string.schedule_start_title),
                summary = formatMinuteOfDay(draft.startMinuteOfDay),
                onClick = { editing = ScheduleDialog.START },
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.WbSunny,
                title = stringResource(R.string.schedule_end_title),
                summary = formatMinuteOfDay(draft.endMinuteOfDay) +
                    if (draft.endMinuteOfDay <= draft.startMinuteOfDay) " · " + stringResource(R.string.schedule_next_day) else "",
                onClick = { editing = ScheduleDialog.END },
            )
        }
    }

    when (editing) {
        ScheduleDialog.PROFILE -> profile?.let {
            ChoiceDialog(
                title = stringResource(R.string.lock_target),
                options = profiles,
                selected = it,
                label = { option -> option.name },
                onSelect = { option ->
                    onDraftChange(draft.copy(profileId = option.id))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
        ScheduleDialog.START -> TimePickerDialog(
            initialMinuteOfDay = draft.startMinuteOfDay,
            onConfirm = {
                onDraftChange(draft.copy(startMinuteOfDay = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        ScheduleDialog.END -> TimePickerDialog(
            initialMinuteOfDay = draft.endMinuteOfDay,
            onConfirm = {
                onDraftChange(draft.copy(endMinuteOfDay = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        ScheduleDialog.DELETE -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.schedule_delete_title)) },
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

private enum class ScheduleDialog { PROFILE, START, END, DELETE }

/** Monday-to-Sunday toggle chips over a [LockSchedule.dayBit] mask. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayChips(daysMask: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            val bit = LockSchedule.dayBit(day)
            FilterChip(
                selected = daysMask and bit != 0,
                onClick = { onChange(daysMask xor bit) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
            )
        }
    }
}
