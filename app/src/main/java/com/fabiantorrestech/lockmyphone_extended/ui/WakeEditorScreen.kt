package com.fabiantorrestech.lockmyphone_extended.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.WakeTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiNetwork
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ConnectedWifi

/** "5:00 AM – 2:00 PM · 1h · Quick lock" and, on a second line, the sleep places. */
fun formatWakeTrigger(context: Context, trigger: WakeTrigger, profileName: String?, places: List<LocationTrigger>): String {
    val window = context.getString(R.string.wake_window_value, formatMinuteOfDay(trigger.windowStartMin), formatMinuteOfDay(trigger.windowEndMin))
    val first = context.getString(
        R.string.wake_summary,
        window,
        formatPresetLabel(trigger.lockMinutes * 60L),
        profileName ?: context.getString(R.string.quick_lock),
    )
    val names = sleepPlaceNames(trigger, places)
    val second = context.getString(R.string.wake_summary_places, names.ifEmpty { context.getString(R.string.wake_places_none) })
    return "$first\n$second"
}

private fun sleepPlaceNames(trigger: WakeTrigger, places: List<LocationTrigger>): String =
    places.filter { it.id in trigger.sleepPlaceIds }.joinToString { it.name }

/** The window must end after it starts, on the same day. */
private fun windowValid(trigger: WakeTrigger): Boolean = trigger.windowEndMin > trigger.windowStartMin

/** The earlier end, when set, must also be after the start. */
private fun altEndValid(trigger: WakeTrigger): Boolean =
    trigger.altDaysMask == 0 || (trigger.altEndMin ?: trigger.windowEndMin) > trigger.windowStartMin

/** Each place used as a sleep place → the names of the Morning locks using it. */
fun sleepPlaceOwners(triggers: List<WakeTrigger>): Map<Long, List<String>> =
    triggers.flatMap { trigger -> trigger.sleepPlaceIds.map { it to trigger.name } }
        .groupBy({ it.first }, { it.second })

/**
 * A place that only exists for Morning lock: "condition only" (it never locks by itself) and used
 * as a sleep place. These are listed under Morning lock, not with the other places.
 */
fun LocationTrigger.isMorningLockSleepPlace(owners: Map<Long, List<String>>): Boolean =
    mode == LocationMode.CONDITION_ONLY && id in owners

/** Morning lock's icon when it has none of its own. */
const val WAKE_ICON = "☀️"

/** A sleep place's icon when it has none of its own. */
const val SLEEP_PLACE_ICON = "🛏️"

private enum class WakeDialog { START, END, LENGTH, TARGET, PLACES, MIN_SLEEP, CHARGING, DELETE, ALT_END, ALT_END_TIME, YIELD, LATE_TIME, WIFI }

/**
 * Edits a Morning lock (spec doc/plans/2026-10-03-morning-lock.md §4). Simple: window, lock,
 * sleep places. Advanced (Pro): days, alarm first and its apps, minimum sleep, the checks.
 *
 * @param usingHere true while "I'm in bed now – use here" is finding the Wi-Fi and location.
 */
@Composable
fun WakeEditorScreen(
    draft: WakeTrigger,
    profiles: List<LockProfile>,
    places: List<LocationTrigger>,
    alarmApps: List<LaunchableApp>,
    setup: SetupStatus,
    setupActions: SetupActions,
    presetsSec: List<Long>,
    usingHere: Boolean,
    onUseHere: () -> Unit,
    alarmTriggers: List<AlarmTrigger>,
    wifiTools: WifiTools,
    currentWifi: ConnectedWifi?,
    onAddWifiSleepPlace: (List<WifiNetwork>) -> Unit,
    onDraftChange: (WakeTrigger) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    automation: AutomationEditorExtras? = null,
) {
    var editing by remember { mutableStateOf<WakeDialog?>(null) }
    var advanced by remember { mutableStateOf(false) }
    val isNew = draft.id == 0L
    val targetProfile = profiles.firstOrNull { it.id == draft.profileId }

    EditorScaffold(
        title = stringResource(if (isNew) R.string.wake_new_title else R.string.wake_edit_title),
        onBack = onBack,
        saveEnabled = draft.name.isNotBlank() && windowValid(draft) && altEndValid(draft) && draft.daysMask != 0,
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = WakeDialog.DELETE }),
        modifier = modifier,
        automation = automation,
    ) {
        WakePermissionNotes(draft, setup, setupActions)

        SettingsSection(stringResource(R.string.section_basics)) {
            Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it.take(40))) },
                    label = { Text(stringResource(R.string.wake_name)) },
                    singleLine = true,
                    isError = draft.name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                ProfileIconField(
                    icon = draft.icon,
                    name = draft.name,
                    onIconChange = { onDraftChange(draft.copy(icon = it)) },
                    fallbackIcon = WAKE_ICON,
                )
            }
            SectionDivider()
            SwitchSettingsRow(
                icon = Icons.Filled.PowerSettingsNew,
                title = stringResource(R.string.wake_enabled),
                summary = null,
                checked = draft.enabled,
                onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
            )
        }

        SettingsSection(stringResource(R.string.wake_section)) {
            SettingsRow(
                icon = Icons.Filled.Schedule,
                title = stringResource(R.string.wake_window),
                summary = stringResource(R.string.wake_window_value, formatMinuteOfDay(draft.windowStartMin), formatMinuteOfDay(draft.windowEndMin)),
                onClick = { editing = WakeDialog.START },
            )
            if (!windowValid(draft)) WarningNote(stringResource(R.string.wake_window_bad))
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Timer,
                title = stringResource(R.string.wake_lock_length),
                summary = formatPresetLabel(draft.lockMinutes * 60L),
                onClick = { editing = WakeDialog.LENGTH },
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.lock_target),
                summary = targetProfile?.name ?: stringResource(R.string.quick_lock),
                onClick = { editing = WakeDialog.TARGET },
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Hotel,
                title = stringResource(R.string.wake_places),
                summary = sleepPlaceNames(draft, places).ifEmpty { stringResource(R.string.wake_places_none) },
                onClick = { editing = WakeDialog.PLACES },
            )
            Text(
                stringResource(R.string.wake_how),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = SectionContentPadding,
            )
        }

        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.setup_advanced_options)) }
        if (advanced) {
            WakeAdvancedSection(draft, alarmApps, alarmTriggers, onDraftChange, onEdit = { editing = it })
        }
    }

    when (editing) {
        WakeDialog.START -> TimePickerDialog(
            initialMinuteOfDay = draft.windowStartMin,
            onConfirm = {
                onDraftChange(draft.copy(windowStartMin = it))
                // Straight on to the end time.
                editing = WakeDialog.END
            },
            onDismiss = { editing = null },
        )
        WakeDialog.END -> TimePickerDialog(
            initialMinuteOfDay = draft.windowEndMin,
            onConfirm = {
                onDraftChange(draft.copy(windowEndMin = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        WakeDialog.LENGTH -> DurationDialog(
            title = stringResource(R.string.wake_lock_length),
            initialMs = draft.lockMinutes * 60_000L,
            presetsSec = presetsSec,
            onConfirm = { ms ->
                val minutes = (ms / 60_000).toInt().coerceIn(WakeTrigger.LOCK_MINUTES_RANGE)
                onDraftChange(draft.copy(lockMinutes = minutes))
                editing = null
            },
            onDismiss = { editing = null },
        )
        WakeDialog.TARGET -> ChoiceDialog(
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
        WakeDialog.PLACES -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.wake_places_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SleepPlacesContent(draft, places, setup, setupActions, usingHere, onUseHere, onDraftChange)
                    // Pro: a sleep place from Wi-Fi networks you've used (Android hides the saved list).
                    if (advanced) {
                        OutlinedButton(onClick = { editing = WakeDialog.WIFI }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.wake_add_from_wifi))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.setup_done)) } },
        )
        WakeDialog.MIN_SLEEP -> NumberDialog(
            title = stringResource(R.string.wake_min_sleep),
            label = stringResource(R.string.wake_min_sleep_label),
            initial = draft.minSleepMinutes / 60,
            range = WakeTrigger.MIN_SLEEP_RANGE.first / 60..WakeTrigger.MIN_SLEEP_RANGE.last / 60,
            onConfirm = {
                onDraftChange(draft.copy(minSleepMinutes = it * 60))
                editing = null
            },
            onDismiss = { editing = null },
        )
        WakeDialog.CHARGING -> NumberDialog(
            title = stringResource(R.string.wake_charging_length),
            label = stringResource(R.string.wake_charging_label),
            initial = draft.chargingMinutes,
            range = WakeTrigger.CHARGING_RANGE,
            onConfirm = {
                onDraftChange(draft.copy(chargingMinutes = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        WakeDialog.WIFI -> WifiPickerDialog(
            tools = wifiTools,
            connected = currentWifi,
            alreadyAdded = emptyList(),
            onAdd = { networks ->
                if (networks.isNotEmpty()) onAddWifiSleepPlace(networks)
                editing = WakeDialog.PLACES
            },
            onDismiss = { editing = WakeDialog.PLACES },
        )
        WakeDialog.ALT_END -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.wake_alt_end)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayChips(draft.altDaysMask, onChange = { onDraftChange(draft.copy(altDaysMask = it)) })
                    SettingsRow(
                        icon = Icons.Filled.Schedule,
                        title = stringResource(R.string.wake_alt_end_time),
                        summary = formatMinuteOfDay(draft.altEndMin ?: draft.windowEndMin),
                        onClick = { editing = WakeDialog.ALT_END_TIME },
                    )
                    if (!altEndValid(draft)) WarningNote(stringResource(R.string.wake_window_bad))
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.setup_done)) } },
            dismissButton = {
                TextButton(onClick = {
                    onDraftChange(draft.copy(altDaysMask = 0, altEndMin = null))
                    editing = null
                }) { Text(stringResource(R.string.wake_alt_end_off)) }
            },
        )
        WakeDialog.ALT_END_TIME -> TimePickerDialog(
            initialMinuteOfDay = draft.altEndMin ?: draft.windowEndMin,
            onConfirm = {
                onDraftChange(draft.copy(altEndMin = it))
                editing = WakeDialog.ALT_END
            },
            onDismiss = { editing = WakeDialog.ALT_END },
        )
        WakeDialog.YIELD -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.wake_yield_which)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RadioSettingsRow(
                        title = stringResource(R.string.wake_yield_any),
                        summary = null,
                        selected = draft.yieldAlarmIds.isEmpty(),
                        onSelect = { onDraftChange(draft.copy(yieldAlarmIds = emptySet())) },
                    )
                    alarmTriggers.forEach { alarm ->
                        val checked = alarm.id in draft.yieldAlarmIds
                        val toggle = {
                            onDraftChange(draft.copy(yieldAlarmIds = if (checked) draft.yieldAlarmIds - alarm.id else draft.yieldAlarmIds + alarm.id))
                        }
                        SettingsRow(
                            icon = Icons.Filled.Alarm,
                            title = alarm.name,
                            summary = null,
                            onClick = toggle,
                            trailing = { Checkbox(checked = checked, onCheckedChange = { toggle() }) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.setup_done)) } },
        )
        WakeDialog.LATE_TIME -> TimePickerDialog(
            initialMinuteOfDay = draft.lateCheckAfterMin,
            onConfirm = {
                onDraftChange(draft.copy(lateCheckAfterMin = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        WakeDialog.DELETE -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.location_delete_title, draft.name)) },
            text = { Text(stringResource(R.string.wake_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
        null -> Unit
    }
}

/** Pro: days, alarm first and its apps, minimum sleep, and what counts as sleeping. */
@Composable
private fun WakeAdvancedSection(
    draft: WakeTrigger,
    alarmApps: List<LaunchableApp>,
    alarmTriggers: List<AlarmTrigger>,
    onDraftChange: (WakeTrigger) -> Unit,
    onEdit: (WakeDialog) -> Unit,
) {
    val context = LocalContext.current
    SettingsSection(stringResource(R.string.setup_advanced_options)) {
        Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wake_days), style = MaterialTheme.typography.titleSmall)
            DayChips(draft.daysMask, onChange = { onDraftChange(draft.copy(daysMask = it)) })
        }
        SectionDivider()
        SwitchSettingsRow(
            icon = Icons.Filled.Alarm,
            title = stringResource(R.string.wake_alarm_first),
            summary = stringResource(R.string.wake_alarm_first_desc),
            checked = draft.alarmFirst,
            onCheckedChange = { onDraftChange(draft.copy(alarmFirst = it)) },
        )
        if (draft.alarmFirst) {
            FirstAlarmApps(alarmApps, draft.alarmPackages, onChange = { onDraftChange(draft.copy(alarmPackages = it)) })
        }
        SectionDivider()
        SettingsRow(
            icon = Icons.Filled.Weekend,
            title = stringResource(R.string.wake_alt_end),
            summary = draft.altEndMin?.takeIf { draft.altDaysMask != 0 }?.let {
                stringResource(R.string.wake_alt_end_value, formatDays(context, draft.altDaysMask), formatMinuteOfDay(it))
            } ?: stringResource(R.string.wake_alt_end_none),
            onClick = { onEdit(WakeDialog.ALT_END) },
        )
        SectionDivider()
        SwitchSettingsRow(
            icon = Icons.Filled.AlarmOff,
            title = stringResource(R.string.wake_yield),
            summary = if (draft.yieldAlarmIds.isEmpty()) {
                stringResource(R.string.wake_yield_any_desc)
            } else {
                alarmTriggers.filter { it.id in draft.yieldAlarmIds }.joinToString { it.name }
            },
            checked = draft.yieldToAlarms,
            onCheckedChange = { onDraftChange(draft.copy(yieldToAlarms = it)) },
        )
        if (draft.yieldToAlarms && alarmTriggers.isNotEmpty()) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.wake_yield_which),
                summary = null,
                onClick = { onEdit(WakeDialog.YIELD) },
            )
        }
        SectionDivider()
        SwitchSettingsRow(
            icon = Icons.Filled.WbSunny,
            title = stringResource(R.string.wake_screen_on),
            summary = stringResource(R.string.wake_screen_on_desc),
            checked = draft.countFromScreenOn,
            onCheckedChange = { onDraftChange(draft.copy(countFromScreenOn = it)) },
        )
        SectionDivider()
        SwitchSettingsRow(
            icon = Icons.Filled.HelpOutline,
            title = stringResource(R.string.wake_late_check),
            summary = stringResource(R.string.wake_late_check_desc, formatMinuteOfDay(draft.lateCheckAfterMin)),
            checked = draft.lateCheck,
            onCheckedChange = { onDraftChange(draft.copy(lateCheck = it)) },
        )
        if (draft.lateCheck) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.wake_late_check_after),
                summary = formatMinuteOfDay(draft.lateCheckAfterMin),
                onClick = { onEdit(WakeDialog.LATE_TIME) },
            )
        }
        SectionDivider()
        SettingsRow(
            icon = Icons.Filled.Bedtime,
            title = stringResource(R.string.wake_min_sleep),
            summary = stringResource(R.string.wake_min_sleep_value, draft.minSleepMinutes / 60),
            onClick = { onEdit(WakeDialog.MIN_SLEEP) },
        )
        SectionDivider()
        SwitchSettingsRow(
            icon = Icons.Filled.BatteryChargingFull,
            title = stringResource(R.string.wake_check_charging),
            summary = stringResource(R.string.wake_check_charging_desc, formatPresetLabel(draft.chargingMinutes * 60L)),
            checked = draft.checkCharging,
            onCheckedChange = { onDraftChange(draft.copy(checkCharging = it)) },
        )
        if (draft.checkCharging) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.wake_charging_length),
                summary = formatPresetLabel(draft.chargingMinutes * 60L),
                onClick = { onEdit(WakeDialog.CHARGING) },
            )
        }
        SectionDivider()
        SwitchSettingsRow(
            icon = Icons.Filled.Place,
            title = stringResource(R.string.wake_check_place),
            summary = null,
            checked = draft.checkPlace,
            onCheckedChange = { onDraftChange(draft.copy(checkPlace = it)) },
        )
        if (!draft.checkCharging && !draft.checkPlace) {
            DangerBanner(stringResource(R.string.wake_no_checks_warning, draft.minSleepMinutes / 60))
        }
    }
}

/** What's missing for Morning lock to work, each with a button to fix it. */
@Composable
private fun WakePermissionNotes(draft: WakeTrigger, setup: SetupStatus, setupActions: SetupActions) {
    if (!setup.accessibilityEnabled) {
        PermissionNote(stringResource(R.string.wake_needs_accessibility), stringResource(R.string.setup_accessibility), setupActions.onEnableAccessibility)
    }
    if (draft.alarmFirst && !setup.notificationAccessGranted) {
        PermissionNote(stringResource(R.string.wake_needs_notification_access), stringResource(R.string.setup_notification_access), setupActions.onGrantNotificationAccess)
    }
    if (draft.checkPlace && draft.sleepPlaceIds.isNotEmpty() && !setup.preciseLocationGranted) {
        PermissionNote(stringResource(R.string.wake_needs_location), stringResource(R.string.setup_location), setupActions.onGrantLocation)
    }
}

@Composable
private fun PermissionNote(text: String, what: String, onFix: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onFix, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_row_enable, what)) }
}

/** "I'm in bed now – use here", then a checkbox per place. Shared by the editor and the setup. */
@Composable
private fun SleepPlacesContent(
    draft: WakeTrigger,
    places: List<LocationTrigger>,
    setup: SetupStatus,
    setupActions: SetupActions,
    usingHere: Boolean,
    onUseHere: () -> Unit,
    onDraftChange: (WakeTrigger) -> Unit,
) {
    if (!setup.preciseLocationGranted) {
        PermissionNote(stringResource(R.string.wake_needs_location), stringResource(R.string.setup_location), setupActions.onGrantLocation)
    }
    Button(onClick = onUseHere, enabled = !usingHere, modifier = Modifier.fillMaxWidth()) {
        if (usingHere) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.wake_use_here_busy))
            }
        } else {
            Text(stringResource(R.string.wake_use_here))
        }
    }
    places.forEach { place ->
        val checked = place.id in draft.sleepPlaceIds
        val toggle = {
            onDraftChange(draft.copy(sleepPlaceIds = if (checked) draft.sleepPlaceIds - place.id else draft.sleepPlaceIds + place.id))
        }
        SettingsRow(
            icon = Icons.Filled.Place,
            title = place.name,
            summary = null,
            onClick = toggle,
            trailing = { Checkbox(checked = checked, onCheckedChange = { toggle() }) },
        )
    }
    Text(stringResource(R.string.wake_places_hint), style = MaterialTheme.typography.bodySmall)
}

private enum class WakeSetupStep { TIMES, PLACES, DONE }

/**
 * The first-time setup (spec §3): when you wake up, where you sleep, then a summary that offers
 * to turn off alarm locks Morning lock now covers. Done saves; Cancel discards.
 *
 * @param alarmTriggers the enabled alarm locks, offered for turning off.
 * @param onDone gets the alarm locks to turn off.
 */
@Composable
fun WakeSetupFlow(
    draft: WakeTrigger,
    profiles: List<LockProfile>,
    places: List<LocationTrigger>,
    alarmTriggers: List<AlarmTrigger>,
    setup: SetupStatus,
    setupActions: SetupActions,
    presetsSec: List<Long>,
    usingHere: Boolean,
    onUseHere: () -> Unit,
    onDraftChange: (WakeTrigger) -> Unit,
    onDone: (turnOffAlarmIds: Set<Long>) -> Unit,
    onCancel: () -> Unit,
) {
    var step by remember { mutableStateOf(WakeSetupStep.TIMES) }
    var editing by remember { mutableStateOf<WakeDialog?>(null) }
    var turnOff by remember { mutableStateOf(emptySet<Long>()) }
    val context = LocalContext.current

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setup_step_of, step.ordinal + 1, WakeSetupStep.entries.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(
                        when (step) {
                            WakeSetupStep.TIMES -> R.string.wake_setup_step1
                            WakeSetupStep.PLACES -> R.string.wake_setup_step2
                            WakeSetupStep.DONE -> R.string.wake_setup_step3
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (step) {
                        WakeSetupStep.TIMES -> {
                            SettingsRow(
                                icon = Icons.Filled.Schedule,
                                title = stringResource(R.string.wake_window),
                                summary = stringResource(R.string.wake_window_value, formatMinuteOfDay(draft.windowStartMin), formatMinuteOfDay(draft.windowEndMin)),
                                onClick = { editing = WakeDialog.START },
                            )
                            if (!windowValid(draft)) WarningNote(stringResource(R.string.wake_window_bad))
                            SettingsRow(
                                icon = Icons.Filled.Timer,
                                title = stringResource(R.string.wake_lock_length),
                                summary = formatPresetLabel(draft.lockMinutes * 60L),
                                onClick = { editing = WakeDialog.LENGTH },
                            )
                            SettingsRow(
                                icon = Icons.Filled.Lock,
                                title = stringResource(R.string.lock_target),
                                summary = profiles.firstOrNull { it.id == draft.profileId }?.name ?: stringResource(R.string.quick_lock),
                                onClick = { editing = WakeDialog.TARGET },
                            )
                        }
                        WakeSetupStep.PLACES -> SleepPlacesContent(draft, places, setup, setupActions, usingHere, onUseHere, onDraftChange)
                        WakeSetupStep.DONE -> {
                            Text(
                                stringResource(
                                    R.string.wake_setup_done_text,
                                    formatPresetLabel(draft.lockMinutes * 60L),
                                    formatMinuteOfDay(draft.windowStartMin),
                                    formatMinuteOfDay(draft.windowEndMin),
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(formatWakeTrigger(context, draft, profiles.firstOrNull { it.id == draft.profileId }?.name, places))
                            if (draft.sleepPlaceIds.isEmpty()) {
                                WarningNote(stringResource(R.string.wake_setup_no_place, formatPresetLabel(draft.chargingMinutes * 60L)))
                            }
                            alarmTriggers.forEach { alarm ->
                                val checked = alarm.id in turnOff
                                val toggle = { turnOff = if (checked) turnOff - alarm.id else turnOff + alarm.id }
                                SettingsRow(
                                    icon = Icons.Filled.Alarm,
                                    title = stringResource(R.string.wake_setup_alarm_offer, alarm.name),
                                    summary = null,
                                    onClick = toggle,
                                    trailing = { Checkbox(checked = checked, onCheckedChange = { toggle() }) },
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.weight(1f))
                    if (step != WakeSetupStep.TIMES) {
                        OutlinedButton(onClick = { step = WakeSetupStep.entries[step.ordinal - 1] }) { Text(stringResource(R.string.setup_back)) }
                    }
                    if (step == WakeSetupStep.DONE) {
                        Button(onClick = { onDone(turnOff) }) { Text(stringResource(R.string.setup_done)) }
                    } else {
                        Button(
                            onClick = { step = WakeSetupStep.entries[step.ordinal + 1] },
                            enabled = windowValid(draft),
                        ) { Text(stringResource(R.string.setup_next)) }
                    }
                }
            }
        }
    }

    when (editing) {
        WakeDialog.START -> TimePickerDialog(
            initialMinuteOfDay = draft.windowStartMin,
            onConfirm = {
                onDraftChange(draft.copy(windowStartMin = it))
                editing = WakeDialog.END
            },
            onDismiss = { editing = null },
        )
        WakeDialog.END -> TimePickerDialog(
            initialMinuteOfDay = draft.windowEndMin,
            onConfirm = {
                onDraftChange(draft.copy(windowEndMin = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        WakeDialog.LENGTH -> DurationDialog(
            title = stringResource(R.string.wake_lock_length),
            initialMs = draft.lockMinutes * 60_000L,
            presetsSec = presetsSec,
            onConfirm = { ms ->
                onDraftChange(draft.copy(lockMinutes = (ms / 60_000).toInt().coerceIn(WakeTrigger.LOCK_MINUTES_RANGE)))
                editing = null
            },
            onDismiss = { editing = null },
        )
        WakeDialog.TARGET -> ChoiceDialog(
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
        else -> Unit
    }
}
