package com.fabiantorrestech.lockmyphone_extended.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconPreset
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconScanSpeed
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconTiming

/**
 * How quickly beacon places react: Battery saver / Balanced / Fast, or Advanced numbers (arrive
 * wait, leave wait, re-check, scan speed). The presets are the Simple-mode choice; Advanced is
 * for fine-tuning (see CLAUDE.md, Simple vs Pro).
 *
 * @param preset the current choice; null = "use the default" (only when [defaultTiming] is set).
 * @param defaultTiming for a place: the default it falls back to, offered as the first option.
 * @param onConfirm the chosen preset (null = default) and the Advanced numbers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BeaconTimingDialog(
    preset: BeaconPreset?,
    custom: BeaconTiming,
    defaultTiming: BeaconTiming?,
    onConfirm: (BeaconPreset?, BeaconTiming) -> Unit,
    onDismiss: () -> Unit,
    boostSummary: String? = null,
    onOpenBoost: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var more by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(preset) }
    var arrive by remember { mutableStateOf((custom.arriveMs / 1_000).toString()) }
    var leave by remember { mutableStateOf((custom.leaveMs / 1_000).toString()) }
    var recheck by remember { mutableStateOf((custom.recheckMs / 1_000).toString()) }
    var speed by remember { mutableStateOf(custom.scanSpeed) }

    val customTiming = BeaconTiming(
        arriveMs = (arrive.toIntOrNull() ?: 0) * 1_000L,
        leaveMs = (leave.toIntOrNull() ?: 0) * 1_000L,
        recheckMs = (recheck.toIntOrNull() ?: 0) * 1_000L,
        scanSpeed = speed,
    ).clamped()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.beacon_timing_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (defaultTiming != null) {
                    RadioSettingsRow(
                        title = stringResource(R.string.beacon_timing_default),
                        summary = timingSummary(context, defaultTiming),
                        selected = selected == null,
                        onSelect = { selected = null },
                    )
                }
                listOf(
                    BeaconPreset.BATTERY to BeaconTiming.BATTERY,
                    BeaconPreset.BALANCED to BeaconTiming.BALANCED,
                    BeaconPreset.FAST to BeaconTiming.FAST,
                ).forEach { (option, timing) ->
                    RadioSettingsRow(
                        title = stringResource(beaconPresetLabel(option)),
                        summary = timingSummary(context, timing),
                        selected = selected == option,
                        onSelect = { selected = option },
                    )
                }
                RadioSettingsRow(
                    title = stringResource(R.string.beacon_preset_custom),
                    summary = stringResource(R.string.beacon_preset_custom_desc),
                    selected = selected == BeaconPreset.CUSTOM,
                    onSelect = { selected = BeaconPreset.CUSTOM },
                )
                if (selected == BeaconPreset.CUSTOM) {
                    SecondsField(stringResource(R.string.beacon_timing_arrive, BeaconTiming.ARRIVE_RANGE_SEC.first, BeaconTiming.ARRIVE_RANGE_SEC.last), arrive) { arrive = it }
                    SecondsField(stringResource(R.string.beacon_timing_leave, BeaconTiming.LEAVE_RANGE_SEC.first, BeaconTiming.LEAVE_RANGE_SEC.last), leave) { leave = it }
                    SecondsField(stringResource(R.string.beacon_timing_recheck, BeaconTiming.RECHECK_RANGE_SEC.first, BeaconTiming.RECHECK_RANGE_SEC.last), recheck) { recheck = it }
                    Text(stringResource(R.string.beacon_timing_scan), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BeaconScanSpeed.entries.forEach { option ->
                            FilterChip(
                                selected = speed == option,
                                onClick = { speed = option },
                                label = { Text(stringResource(scanSpeedLabel(option))) },
                            )
                        }
                    }
                    Text(timingSummary(context, customTiming), style = MaterialTheme.typography.bodySmall)
                }
                // "Get ready early" (Pro): today's "Listen harder", now part of Timing.
                if (onOpenBoost != null) {
                    TextButton(onClick = { more = !more }) { Text(stringResource(R.string.setup_advanced_options)) }
                    if (more) {
                        SettingsRow(
                            icon = Icons.Filled.Hearing,
                            title = stringResource(R.string.boost_title),
                            summary = boostSummary,
                            onClick = onOpenBoost,
                        )
                    }
                }
                Text(
                    stringResource(R.string.beacon_timing_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected, customTiming) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun SecondsField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Here after 15 s · gone after 45 s · checks every 1 min". */
fun timingSummary(context: Context, timing: BeaconTiming): String = context.getString(
    R.string.beacon_timing_summary,
    formatWait(context, timing.arriveMs),
    formatWait(context, timing.leaveMs),
    formatWait(context, timing.recheckMs),
)

/** A place's or the default's choice in a row: "Fast" or "Default (Balanced)". */
fun beaconTimingLabel(context: Context, preset: BeaconPreset?, defaultPreset: BeaconPreset): String =
    if (preset == null) {
        context.getString(R.string.beacon_timing_default_named, context.getString(beaconPresetLabel(defaultPreset)))
    } else {
        context.getString(beaconPresetLabel(preset))
    }

fun beaconPresetLabel(preset: BeaconPreset): Int = when (preset) {
    BeaconPreset.BATTERY -> R.string.beacon_preset_battery
    BeaconPreset.BALANCED -> R.string.beacon_preset_balanced
    BeaconPreset.FAST -> R.string.beacon_preset_fast
    BeaconPreset.CUSTOM -> R.string.beacon_preset_custom
}

private fun scanSpeedLabel(speed: BeaconScanSpeed): Int = when (speed) {
    BeaconScanSpeed.LOW_POWER -> R.string.beacon_scan_low_power
    BeaconScanSpeed.BALANCED -> R.string.beacon_scan_balanced
    BeaconScanSpeed.LOW_LATENCY -> R.string.beacon_scan_low_latency
}

/** "45 s", "2 min", "1 min 30 s". */
fun formatWait(context: Context, ms: Long): String {
    val seconds = ms / 1_000
    return when {
        seconds < 60 -> context.getString(R.string.wait_seconds, seconds)
        seconds % 60 == 0L -> context.getString(R.string.wait_minutes, seconds / 60)
        else -> context.getString(R.string.wait_minutes_seconds, seconds / 60, seconds % 60)
    }
}
