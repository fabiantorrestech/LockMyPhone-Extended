package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import com.fabiantorrestech.lockmyphone_extended.R

/*
 * Building blocks for the settings-style editors (profile, Lock defaults, Quick lock, schedule,
 * alarm trigger): a scaffold with a pinned Save button, grouped sections, rows that show a
 * summary and open a dialog for the details, and the dialogs themselves.
 */

/**
 * A full-screen editor: top bar (← back, title, 🗑 when [onDelete] is set), scrolling
 * [content], and Save pinned at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            },
            // The activity's Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
        Surface(tonalElevation = 3.dp) {
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

/** A titled group of rows in a card. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** Padding for free-form content (text fields, chips, notes) inside a [SettingsSection]. */
val SectionContentPadding = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)

/** A thin line between rows of a section. */
@Composable
fun SectionDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One setting: icon, title, one-line summary and a trailing control. Tapping it calls [onClick]
 * (a chevron is shown when there's no other trailing control).
 */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    summary: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha))
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
    }
}

/** A setting that's simply on or off; the whole row toggles it. */
@Composable
fun SwitchSettingsRow(
    icon: ImageVector?,
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingsRow(
        icon = icon,
        title = title,
        summary = summary,
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
    )
}

/** A radio-button row, for choosing one option inside a section. */
@Composable
fun RadioSettingsRow(title: String, summary: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One-tap lock lengths. The chip matching [selectedMs] is shown selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DurationPresetChips(presetsSec: List<Long>, selectedMs: Long?, onPick: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (presetsSec.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presetsSec.forEach { seconds ->
            FilterChip(
                selected = selectedMs == seconds * 1_000,
                onClick = { onPick(seconds * 1_000) },
                label = { Text(formatPresetLabel(seconds)) },
            )
        }
    }
}

/** "15m", "1h", "1h 30m", "45s". */
fun formatPresetLabel(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "${it}h" },
        m.takeIf { it > 0 }?.let { "${it}m" },
        s.takeIf { it > 0 }?.let { "${it}s" },
    ).joinToString(" ").ifEmpty { "0s" }
}

/** Pick a lock length: preset chips plus hours / minutes / seconds. */
@Composable
fun DurationDialog(
    title: String,
    initialMs: Long,
    presetsSec: List<Long>,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDurationInputState(initialMs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DurationPresetChips(presetsSec, state.durationMs, onPick = { ms ->
                    val seconds = ms / 1_000
                    state.hoursText = (seconds / 3600).toString()
                    state.minutesText = ((seconds % 3600) / 60).toString()
                    state.secondsText = (seconds % 60).toString()
                })
                DurationInput(state)
                Text(stringResource(R.string.start_duration_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { state.durationMs?.let(onConfirm) }, enabled = state.durationMs != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Enter a whole number in [range], e.g. minutes or days. */
@Composable
fun NumberDialog(
    title: String,
    label: String,
    initial: Int,
    range: IntRange,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in range }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DurationField(
                    value = text,
                    onValueChange = { text = it },
                    label = label,
                    isError = value == null,
                    maxDigits = range.last.toString().length,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Choose one of [options]; picking one confirms right away. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    description: (@Composable (T) -> String?)? = null,
    footer: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    RadioSettingsRow(
                        title = label(option),
                        summary = description?.invoke(option),
                        selected = option == selected,
                        onSelect = { onSelect(option) },
                    )
                }
                if (footer != null) {
                    Text(footer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Pick a time of day; returns minutes past midnight. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initialMinuteOfDay: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
