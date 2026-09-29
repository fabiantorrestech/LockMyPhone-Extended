package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMatch
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationSlot
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ConnectedWifi
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationRules
import java.util.Locale

/** The state of "Use my current location". */
sealed interface FixRequest {
    data object Idle : FixRequest
    data object Locating : FixRequest
    data class Found(val accuracyMeters: Float) : FixRequest
    data object Failed : FixRequest
}

/**
 * Creates or edits a location trigger:
 * - Basics: name, icon, on/off
 * - Place: current location or typed coordinates, radius, preview in Maps
 * - Wi-Fi: optionally require one exact router
 * - Behaviour: while here / on arrival / when leaving
 * - Active on, and which lock
 *
 * @param hasLocationPermission false until precise location is allowed.
 * @param currentWifi the Wi-Fi network connected right now, if any (for "Require this Wi-Fi").
 */
@Composable
fun LocationEditorScreen(
    draft: LocationTrigger,
    profiles: List<LockProfile>,
    dayStartMinutes: Int,
    hasLocationPermission: Boolean,
    fixRequest: FixRequest,
    currentWifi: ConnectedWifi?,
    slots: List<LocationSlot>,
    onSlotsChange: (List<LocationSlot>) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onPreviewInMaps: () -> Unit,
    onDraftChange: (LocationTrigger) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<LocationDialog?>(null) }
    // The slot being edited in its dialog: an index into [slots], or -1 for a new one.
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    val isNew = draft.id == 0L
    val timeSlots = draft.mode == LocationMode.TIME_SLOTS
    val whenComplete = if (timeSlots) {
        slots.isNotEmpty() && slots.all { it.isValid }
    } else {
        activeOnComplete(draft.activeRule, draft.daysMask, draft.dates)
    }
    val hasPlace = !draft.latitude.isNaN() && !draft.longitude.isNaN()
    val targetProfile = profiles.firstOrNull { it.id == draft.profileId }

    EditorScaffold(
        title = stringResource(if (isNew) R.string.location_new else R.string.location_edit_title),
        onBack = onBack,
        saveEnabled = draft.name.isNotBlank() && hasPlace && whenComplete,
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = LocationDialog.DELETE }),
        modifier = modifier,
    ) {
        if (!hasLocationPermission) {
            Text(stringResource(R.string.location_needs_permission), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRequestLocationPermission, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.location_allow))
            }
        }

        SettingsSection(stringResource(R.string.section_basics)) {
            Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it.take(40))) },
                    label = { Text(stringResource(R.string.location_name)) },
                    placeholder = { Text(stringResource(R.string.location_name_hint)) },
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

        SettingsSection(stringResource(R.string.section_place)) {
            SettingsRow(
                icon = Icons.Filled.MyLocation,
                title = stringResource(R.string.location_use_current),
                summary = when (fixRequest) {
                    FixRequest.Idle -> stringResource(R.string.location_use_current_hint)
                    FixRequest.Locating -> stringResource(R.string.location_locating)
                    is FixRequest.Found -> stringResource(R.string.location_found, fixRequest.accuracyMeters.toInt())
                    FixRequest.Failed -> stringResource(R.string.location_fix_failed)
                },
                onClick = onUseCurrentLocation,
                enabled = hasLocationPermission && fixRequest != FixRequest.Locating,
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Place,
                title = stringResource(R.string.location_coordinates),
                summary = if (hasPlace) formatCoordinates(draft.latitude, draft.longitude) else stringResource(R.string.location_not_set),
                onClick = { editing = LocationDialog.COORDINATES },
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Adjust,
                title = stringResource(R.string.location_radius),
                summary = stringResource(R.string.meters_value, draft.radiusMeters),
                onClick = { editing = LocationDialog.RADIUS },
                enabled = draft.usesGps,
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Map,
                title = stringResource(R.string.location_preview),
                summary = null,
                onClick = onPreviewInMaps,
                enabled = hasPlace,
            )
        }

        SettingsSection(stringResource(R.string.section_wifi)) {
            SwitchSettingsRow(
                icon = Icons.Filled.Wifi,
                title = stringResource(R.string.location_wifi_use),
                summary = when {
                    draft.wifiBssid != null -> stringResource(
                        R.string.location_wifi_required,
                        draft.wifiSsid ?: stringResource(R.string.location_wifi_unnamed),
                        draft.wifiBssid,
                    )
                    currentWifi != null -> stringResource(R.string.location_wifi_offer, currentWifi.ssid ?: currentWifi.bssid)
                    else -> stringResource(R.string.location_wifi_none)
                },
                checked = draft.wifiBssid != null,
                onCheckedChange = { require ->
                    onDraftChange(
                        if (require && currentWifi != null) {
                            // Same as before this setting existed: the Wi-Fi decides on its own.
                            draft.copy(wifiBssid = currentWifi.bssid, wifiSsid = currentWifi.ssid, matchMode = LocationMatch.WIFI_ONLY)
                        } else {
                            draft.copy(wifiBssid = null, wifiSsid = null, matchMode = LocationMatch.GPS_ONLY)
                        }
                    )
                },
                enabled = draft.wifiBssid != null || currentWifi != null,
            )
            if (draft.wifiBssid != null) {
                SectionDivider()
                SettingsRow(
                    icon = Icons.Filled.Tune,
                    title = stringResource(R.string.location_match_title),
                    summary = stringResource(locationMatchLabel(draft.matchMode)),
                    onClick = { editing = LocationDialog.MATCH },
                )
            }
            Text(
                stringResource(R.string.location_wifi_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = SectionContentPadding,
            )
        }

        SettingsSection(stringResource(R.string.section_behaviour)) {
            LocationMode.entries.forEach { mode ->
                RadioSettingsRow(
                    title = stringResource(locationModeLabel(mode)),
                    summary = stringResource(locationModeDescription(mode)),
                    selected = draft.mode == mode,
                    onSelect = { onDraftChange(draft.copy(mode = mode)) },
                )
            }
        }

        if (timeSlots) {
            SettingsSection(stringResource(R.string.section_time_slots)) {
                if (slots.isEmpty()) {
                    Text(stringResource(R.string.slots_empty), style = MaterialTheme.typography.bodySmall, modifier = SectionContentPadding)
                }
                slots.forEachIndexed { index, slot ->
                    SettingsRow(
                        icon = Icons.Filled.Schedule,
                        title = "${formatMinuteOfDay(slot.startMinuteOfDay)} → ${formatMinuteOfDay(slot.endMinuteOfDay)}" +
                            if (slot.endMinuteOfDay <= slot.startMinuteOfDay) " (" + stringResource(R.string.schedule_next_day) + ")" else "",
                        summary = formatDays(LocalContext.current, slot.daysMask),
                        onClick = { editingSlot = index },
                    )
                    SectionDivider()
                }
                TextButton(onClick = { editingSlot = -1 }, modifier = SectionContentPadding) {
                    Text(stringResource(R.string.slots_add))
                }
            }
            SettingsSection(stringResource(R.string.section_during_slot)) {
                SwitchSettingsRow(
                    icon = Icons.AutoMirrored.Filled.Login,
                    title = stringResource(R.string.slots_lock_on_return),
                    summary = stringResource(R.string.slots_lock_on_return_desc),
                    checked = draft.lockOnReturn,
                    onCheckedChange = { onDraftChange(draft.copy(lockOnReturn = it)) },
                )
                SectionDivider()
                Text(
                    stringResource(R.string.slots_when_leaving),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = SectionContentPadding,
                )
                RadioSettingsRow(
                    title = stringResource(R.string.slots_leave_unlock),
                    summary = null,
                    selected = !draft.stayLockedAfterLeaving,
                    onSelect = { onDraftChange(draft.copy(stayLockedAfterLeaving = false)) },
                )
                RadioSettingsRow(
                    title = stringResource(R.string.slots_leave_stay),
                    summary = null,
                    selected = draft.stayLockedAfterLeaving,
                    onSelect = { onDraftChange(draft.copy(stayLockedAfterLeaving = true)) },
                )
            }
        } else {
            ActiveOnSection(
                rule = draft.activeRule,
                daysMask = draft.daysMask,
                dates = draft.dates,
                dayStartMinutes = dayStartMinutes,
                onRuleChange = { onDraftChange(draft.copy(activeRule = it)) },
                onDaysMaskChange = { onDraftChange(draft.copy(daysMask = it)) },
                onDatesChange = { onDraftChange(draft.copy(dates = it)) },
            )
        }

        SettingsSection(stringResource(R.string.alarm_trigger_lock)) {
            SettingsRow(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(R.string.lock_target),
                summary = targetProfile?.name ?: stringResource(R.string.quick_lock),
                onClick = { editing = LocationDialog.TARGET },
            )
        }
    }

    when (editing) {
        LocationDialog.COORDINATES -> CoordinatesDialog(
            initial = if (hasPlace) formatCoordinates(draft.latitude, draft.longitude) else "",
            onConfirm = { (lat, lng) ->
                onDraftChange(draft.copy(latitude = lat, longitude = lng))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LocationDialog.MATCH -> ChoiceDialog(
            title = stringResource(R.string.location_match_title),
            options = LocationMatch.entries,
            selected = draft.matchMode,
            label = { stringResource(locationMatchLabel(it)) },
            description = { stringResource(locationMatchDescription(it)) },
            onSelect = {
                onDraftChange(draft.copy(matchMode = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LocationDialog.RADIUS -> NumberDialog(
            title = stringResource(R.string.location_radius),
            label = stringResource(R.string.meters_label),
            initial = draft.radiusMeters,
            range = LocationTrigger.RADIUS_RANGE,
            hint = stringResource(R.string.location_radius_hint),
            onConfirm = {
                onDraftChange(draft.copy(radiusMeters = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LocationDialog.TARGET -> ChoiceDialog(
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
        LocationDialog.DELETE -> AlertDialog(
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

    editingSlot?.let { index ->
        SlotDialog(
            initial = slots.getOrNull(index) ?: LocationSlot(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60),
            canDelete = index >= 0,
            onConfirm = { slot ->
                onSlotsChange(if (index >= 0) slots.toMutableList().also { it[index] = slot } else slots + slot)
                editingSlot = null
            },
            onDelete = {
                onSlotsChange(slots.filterIndexed { i, _ -> i != index })
                editingSlot = null
            },
            onDismiss = { editingSlot = null },
        )
    }
}

private enum class LocationDialog { COORDINATES, RADIUS, MATCH, TARGET, DELETE }

/** One time slot: days, start and end (an end at or before the start runs past midnight). */
@Composable
private fun SlotDialog(
    initial: LocationSlot,
    canDelete: Boolean,
    onConfirm: (LocationSlot) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var slot by remember { mutableStateOf(initial) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.slot_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayChips(slot.daysMask, onChange = { slot = slot.copy(daysMask = it) })
                SettingsRow(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.schedule_start_title),
                    summary = formatMinuteOfDay(slot.startMinuteOfDay),
                    onClick = { pickingStart = true },
                )
                SettingsRow(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.schedule_end_title),
                    summary = formatMinuteOfDay(slot.endMinuteOfDay) +
                        if (slot.endMinuteOfDay <= slot.startMinuteOfDay) " · " + stringResource(R.string.schedule_next_day) else "",
                    onClick = { pickingEnd = true },
                )
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(slot) }, enabled = slot.isValid) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (pickingStart) {
        TimePickerDialog(
            initialMinuteOfDay = slot.startMinuteOfDay,
            onConfirm = {
                slot = slot.copy(startMinuteOfDay = it)
                pickingStart = false
            },
            onDismiss = { pickingStart = false },
        )
    }
    if (pickingEnd) {
        TimePickerDialog(
            initialMinuteOfDay = slot.endMinuteOfDay,
            onConfirm = {
                slot = slot.copy(endMinuteOfDay = it)
                pickingEnd = false
            },
            onDismiss = { pickingEnd = false },
        )
    }
}

fun locationMatchLabel(match: LocationMatch): Int = when (match) {
    LocationMatch.GPS_ONLY -> R.string.match_gps_only
    LocationMatch.WIFI_ONLY -> R.string.match_wifi_only
    LocationMatch.GPS_AND_WIFI -> R.string.match_gps_and_wifi
    LocationMatch.GPS_OR_WIFI -> R.string.match_gps_or_wifi
}

private fun locationMatchDescription(match: LocationMatch): Int = when (match) {
    LocationMatch.GPS_ONLY -> R.string.match_gps_only_desc
    LocationMatch.WIFI_ONLY -> R.string.match_wifi_only_desc
    LocationMatch.GPS_AND_WIFI -> R.string.match_gps_and_wifi_desc
    LocationMatch.GPS_OR_WIFI -> R.string.match_gps_or_wifi_desc
}

fun locationModeLabel(mode: LocationMode): Int = when (mode) {
    LocationMode.WHILE_HERE -> R.string.location_mode_while_here
    LocationMode.ON_ARRIVAL -> R.string.location_mode_arrival
    LocationMode.ON_LEAVE -> R.string.location_mode_leave
    LocationMode.TIME_SLOTS -> R.string.location_mode_slots
}

private fun locationModeDescription(mode: LocationMode): Int = when (mode) {
    LocationMode.WHILE_HERE -> R.string.location_mode_while_here_desc
    LocationMode.ON_ARRIVAL -> R.string.location_mode_arrival_desc
    LocationMode.ON_LEAVE -> R.string.location_mode_leave_desc
    LocationMode.TIME_SLOTS -> R.string.location_mode_slots_desc
}

/** "40.712776, -74.005974" (6 decimals ≈ 10 cm). */
fun formatCoordinates(latitude: Double, longitude: Double): String =
    String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

/** Type or paste "lat, lng", e.g. copied from Google Maps. */
@Composable
private fun CoordinatesDialog(initial: String, onConfirm: (Pair<Double, Double>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val parsed = LocationRules.parseCoordinates(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_coordinates)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.location_coordinates_label)) },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.location_coordinates_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
