package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Active on": every day, chosen weekdays, once, or specific dates. Shared by alarm and
 * location triggers.
 */
@Composable
fun ActiveOnSection(
    rule: AlarmActiveRule,
    daysMask: Int,
    dates: Set<String>,
    dayStartMinutes: Int,
    onRuleChange: (AlarmActiveRule) -> Unit,
    onDaysMaskChange: (Int) -> Unit,
    onDatesChange: (Set<String>) -> Unit,
) {
    var addingDate by remember { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.section_active_on)) {
        AlarmActiveRule.entries.forEach { option ->
            RadioSettingsRow(
                title = stringResource(activeRuleLabel(option)),
                summary = null,
                selected = rule == option,
                onSelect = { onRuleChange(option) },
            )
        }
        when (rule) {
            AlarmActiveRule.WEEKDAYS -> {
                SectionDivider()
                DayChips(daysMask, onChange = onDaysMaskChange, modifier = SectionContentPadding)
            }
            AlarmActiveRule.DATES -> {
                SectionDivider()
                DateList(dates = dates, onRemove = { onDatesChange(dates - it) }, onAdd = { addingDate = true })
            }
            AlarmActiveRule.ONCE -> {
                SectionDivider()
                Text(stringResource(R.string.active_once_hint), style = MaterialTheme.typography.bodySmall, modifier = SectionContentPadding)
            }
            AlarmActiveRule.EVERY_DAY -> Unit
        }
        Text(
            stringResource(R.string.active_day_start_hint, formatMinuteOfDay(dayStartMinutes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = SectionContentPadding,
        )
    }
    if (addingDate) {
        AddDateDialog(
            onConfirm = {
                onDatesChange(dates + it.toString())
                addingDate = false
            },
            onDismiss = { addingDate = false },
        )
    }
}

/** Whether the chosen rule has what it needs (at least one weekday or date). */
fun activeOnComplete(rule: AlarmActiveRule, daysMask: Int, dates: Set<String>): Boolean = when (rule) {
    AlarmActiveRule.WEEKDAYS -> daysMask != 0
    AlarmActiveRule.DATES -> dates.isNotEmpty()
    else -> true
}

fun activeRuleLabel(rule: AlarmActiveRule): Int = when (rule) {
    AlarmActiveRule.EVERY_DAY -> R.string.active_every_day
    AlarmActiveRule.WEEKDAYS -> R.string.active_weekdays
    AlarmActiveRule.ONCE -> R.string.active_once
    AlarmActiveRule.DATES -> R.string.active_dates
}

/** The chosen dates, each removable, plus "Add date". */
@Composable
private fun DateList(dates: Set<String>, onRemove: (String) -> Unit, onAdd: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        dates.sorted().forEach { iso ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = runCatching { LocalDate.parse(iso).format(formatter) }.getOrDefault(iso)
                Text(label, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(iso) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove))
                }
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.active_add_date))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDateDialog(onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // The picker reports midnight UTC of the chosen day.
                    state.selectedDateMillis?.let { onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                },
                enabled = state.selectedDateMillis != null,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    ) {
        DatePicker(state = state)
    }
}
