package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.db.BtDetection
import com.fabiantorrestech.lockmyphone_extended.data.db.ChargerMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationSlot
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.PlaceSignal
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconSighting
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ConnectedWifi
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDevice
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDeviceKind
import java.util.Locale

/** The state of "Use my current location". */
sealed interface FixRequest {
    data object Idle : FixRequest
    data object Locating : FixRequest
    data class Found(val accuracyMeters: Float) : FixRequest
    data object Failed : FixRequest
}

/** What the place editor needs for beacons: permission, and scans run by the caller. */
class BeaconTools(
    val hasPermission: Boolean,
    val bluetoothOn: Boolean,
    val onRequestPermission: () -> Unit,
    /** A ~15 s scan listing nearby beacons, strongest first. */
    val discover: suspend () -> List<BeaconSighting>,
    /** ~20 s of readings of one beacon, for calibration. */
    val sample: suspend (key: String) -> List<Int>,
)

/** What the place editor needs for paired Bluetooth devices. */
class PairedDeviceTools(
    val hasPermission: Boolean,
    val onRequestPermission: () -> Unit,
    val list: () -> List<PairedDevice>,
    /** A ~15 s scan: the addresses heard, with their signal. Null without the scan permission. */
    val seenNearby: (suspend () -> Map<String, Int>)?,
)

/**
 * Creates or edits a place:
 * - Basics: name, icon, on/off
 * - How we know you're here: any mix of GPS circle, Wi-Fi network, Bluetooth beacon, charger,
 *   all of them or any one
 * - The settings of each signal
 * - Behaviour (incl. time slots, or "only as a condition"), Active on, which lock
 *
 * @param hasLocationPermission false until precise location is allowed (needed for GPS, Wi-Fi, beacons).
 * @param currentWifi the Wi-Fi network connected right now, if any.
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
    beaconTools: BeaconTools,
    pairedDeviceTools: PairedDeviceTools,
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
    val signals = draft.signalSet
    val hasPlace = !draft.latitude.isNaN() && !draft.longitude.isNaN()
    val targetProfile = profiles.firstOrNull { it.id == draft.profileId }

    val whenComplete = when (draft.mode) {
        LocationMode.TIME_SLOTS -> slots.isNotEmpty() && slots.all { it.isValid }
        LocationMode.CONDITION_ONLY -> true
        else -> activeOnComplete(draft.activeRule, draft.daysMask, draft.dates)
    }
    val signalsComplete = signals.isNotEmpty() &&
        (PlaceSignal.GPS !in signals || hasPlace) &&
        (PlaceSignal.WIFI !in signals || draft.wifiBssid != null) &&
        (PlaceSignal.BEACON !in signals || draft.beaconKey != null) &&
        (PlaceSignal.BT_DEVICE !in signals || draft.btAddress != null)

    fun toggleSignal(signal: PlaceSignal) {
        val names = if (signal in signals) draft.signals - signal.name else draft.signals + signal.name
        onDraftChange(draft.copy(signals = names))
    }

    EditorScaffold(
        title = stringResource(if (isNew) R.string.location_new else R.string.location_edit_title),
        onBack = onBack,
        saveEnabled = draft.name.isNotBlank() && signalsComplete && whenComplete,
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = LocationDialog.DELETE }),
        modifier = modifier,
    ) {
        if (!hasLocationPermission && (signals - PlaceSignal.CHARGER).isNotEmpty()) {
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

        SettingsSection(stringResource(R.string.section_signals)) {
            PlaceSignal.entries.forEach { signal ->
                SettingsRow(
                    icon = signalIcon(signal),
                    title = stringResource(signalLabel(signal)),
                    summary = stringResource(signalDescription(signal)),
                    onClick = { toggleSignal(signal) },
                    trailing = { Checkbox(checked = signal in signals, onCheckedChange = { toggleSignal(signal) }) },
                )
            }
            if (signals.size > 1) {
                SectionDivider()
                RadioSettingsRow(
                    title = stringResource(R.string.signals_all),
                    summary = stringResource(R.string.signals_all_desc),
                    selected = draft.requireAll,
                    onSelect = { onDraftChange(draft.copy(requireAll = true)) },
                )
                RadioSettingsRow(
                    title = stringResource(R.string.signals_any),
                    summary = stringResource(R.string.signals_any_desc),
                    selected = !draft.requireAll,
                    onSelect = { onDraftChange(draft.copy(requireAll = false)) },
                )
            }
        }

        if (PlaceSignal.GPS in signals) {
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
        }

        if (PlaceSignal.WIFI in signals) {
            SettingsSection(stringResource(R.string.section_wifi)) {
                SettingsRow(
                    icon = Icons.Filled.Wifi,
                    title = stringResource(R.string.location_wifi_use_current),
                    summary = when {
                        draft.wifiBssid != null -> stringResource(
                            R.string.location_wifi_required,
                            draft.wifiSsid ?: stringResource(R.string.location_wifi_unnamed),
                            draft.wifiBssid,
                        )
                        currentWifi != null -> stringResource(R.string.location_wifi_offer, currentWifi.ssid ?: currentWifi.bssid)
                        else -> stringResource(R.string.location_wifi_none)
                    },
                    onClick = {
                        currentWifi?.let { onDraftChange(draft.copy(wifiBssid = it.bssid, wifiSsid = it.ssid)) }
                    },
                    enabled = currentWifi != null,
                )
                Text(
                    stringResource(R.string.location_wifi_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = SectionContentPadding,
                )
            }
        }

        if (PlaceSignal.BEACON in signals) {
            SettingsSection(stringResource(R.string.section_beacon)) {
                if (!beaconTools.hasPermission) {
                    Column(SectionContentPadding) {
                        Text(stringResource(R.string.beacon_needs_permission), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = beaconTools.onRequestPermission) { Text(stringResource(R.string.beacon_allow)) }
                    }
                } else if (!beaconTools.bluetoothOn) {
                    Text(stringResource(R.string.beacon_bluetooth_off), style = MaterialTheme.typography.bodyMedium, modifier = SectionContentPadding)
                }
                SettingsRow(
                    icon = Icons.Filled.BluetoothSearching,
                    title = stringResource(R.string.beacon_pick),
                    summary = draft.beaconName?.let { "$it · ${draft.beaconKey}" } ?: stringResource(R.string.beacon_none),
                    onClick = { editing = LocationDialog.BEACON_PICK },
                    enabled = beaconTools.hasPermission && beaconTools.bluetoothOn,
                )
                SectionDivider()
                SettingsRow(
                    icon = Icons.Filled.NetworkCheck,
                    title = stringResource(R.string.beacon_calibrate),
                    summary = stringResource(R.string.beacon_calibrate_hint),
                    onClick = { editing = LocationDialog.BEACON_CALIBRATE },
                    enabled = draft.beaconKey != null && beaconTools.hasPermission && beaconTools.bluetoothOn,
                )
                SectionDivider()
                SettingsRow(
                    icon = Icons.Filled.SignalCellularAlt,
                    title = stringResource(R.string.beacon_threshold),
                    summary = stringResource(R.string.beacon_threshold_value, draft.beaconRssiThreshold),
                    onClick = { editing = LocationDialog.BEACON_THRESHOLD },
                )
                Text(
                    stringResource(R.string.beacon_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = SectionContentPadding,
                )
            }
        }

        if (PlaceSignal.BT_DEVICE in signals) {
            val nearby = draft.btDetection == BtDetection.NEARBY
            SettingsSection(stringResource(R.string.section_bt_device)) {
                if (!pairedDeviceTools.hasPermission) {
                    Column(SectionContentPadding) {
                        Text(stringResource(R.string.bt_device_needs_permission), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = pairedDeviceTools.onRequestPermission) { Text(stringResource(R.string.beacon_allow)) }
                    }
                } else if (!beaconTools.bluetoothOn) {
                    Text(stringResource(R.string.beacon_bluetooth_off), style = MaterialTheme.typography.bodyMedium, modifier = SectionContentPadding)
                }
                SettingsRow(
                    icon = Icons.Filled.BluetoothConnected,
                    title = stringResource(R.string.bt_device_pick),
                    summary = draft.btName?.let { "$it · ${draft.btAddress}" } ?: stringResource(R.string.bt_device_none),
                    onClick = { editing = LocationDialog.DEVICE_PICK },
                    enabled = pairedDeviceTools.hasPermission,
                )
                SectionDivider()
                RadioSettingsRow(
                    title = stringResource(R.string.bt_detect_connected),
                    summary = stringResource(R.string.bt_detect_connected_desc),
                    selected = !nearby,
                    onSelect = { onDraftChange(draft.copy(btDetection = BtDetection.CONNECTED)) },
                )
                RadioSettingsRow(
                    title = stringResource(R.string.bt_detect_nearby),
                    summary = stringResource(R.string.bt_detect_nearby_desc),
                    selected = nearby,
                    onSelect = { onDraftChange(draft.copy(btDetection = BtDetection.NEARBY)) },
                )
                if (nearby) {
                    if (!beaconTools.hasPermission) {
                        Column(SectionContentPadding) {
                            Text(stringResource(R.string.bt_nearby_needs_scan), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = beaconTools.onRequestPermission) { Text(stringResource(R.string.beacon_allow)) }
                        }
                    }
                    SectionDivider()
                    SettingsRow(
                        icon = Icons.Filled.NetworkCheck,
                        title = stringResource(R.string.beacon_calibrate),
                        summary = stringResource(R.string.beacon_calibrate_hint),
                        onClick = { editing = LocationDialog.DEVICE_CALIBRATE },
                        enabled = draft.btAddress != null && beaconTools.hasPermission && beaconTools.bluetoothOn,
                    )
                    SectionDivider()
                    SettingsRow(
                        icon = Icons.Filled.SignalCellularAlt,
                        title = stringResource(R.string.beacon_threshold),
                        summary = stringResource(R.string.beacon_threshold_value, draft.btRssiThreshold),
                        onClick = { editing = LocationDialog.DEVICE_THRESHOLD },
                    )
                }
                Text(
                    stringResource(if (nearby) R.string.bt_nearby_hint else R.string.bt_connected_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = SectionContentPadding,
                )
            }
        }

        if (PlaceSignal.CHARGER in signals) {
            SettingsSection(stringResource(R.string.section_charger)) {
                RadioSettingsRow(
                    title = stringResource(R.string.charger_any),
                    summary = null,
                    selected = draft.chargerMode == ChargerMode.ANY,
                    onSelect = { onDraftChange(draft.copy(chargerMode = ChargerMode.ANY)) },
                )
                RadioSettingsRow(
                    title = stringResource(R.string.charger_wireless),
                    summary = stringResource(R.string.charger_wireless_desc),
                    selected = draft.chargerMode == ChargerMode.WIRELESS,
                    onSelect = { onDraftChange(draft.copy(chargerMode = ChargerMode.WIRELESS)) },
                )
            }
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

        when (draft.mode) {
            LocationMode.TIME_SLOTS -> {
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
            }
            LocationMode.CONDITION_ONLY -> Text(
                stringResource(R.string.location_mode_condition_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            else -> ActiveOnSection(
                rule = draft.activeRule,
                daysMask = draft.daysMask,
                dates = draft.dates,
                dayStartMinutes = dayStartMinutes,
                onRuleChange = { onDraftChange(draft.copy(activeRule = it)) },
                onDaysMaskChange = { onDraftChange(draft.copy(daysMask = it)) },
                onDatesChange = { onDraftChange(draft.copy(dates = it)) },
            )
        }

        if (draft.mode != LocationMode.CONDITION_ONLY) {
            SettingsSection(stringResource(R.string.alarm_trigger_lock)) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.lock_target),
                    summary = targetProfile?.name ?: stringResource(R.string.quick_lock),
                    onClick = { editing = LocationDialog.TARGET },
                )
            }
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
        LocationDialog.BEACON_PICK -> BeaconPickerDialog(
            discover = beaconTools.discover,
            onPick = { sighting ->
                onDraftChange(draft.copy(beaconKey = sighting.key, beaconName = sighting.label))
                editing = LocationDialog.BEACON_CALIBRATE
            },
            onDismiss = { editing = null },
        )
        LocationDialog.BEACON_CALIBRATE -> draft.beaconKey?.let { key ->
            CalibrationDialog(
                sample = { beaconTools.sample(key) },
                onConfirm = { threshold ->
                    onDraftChange(draft.copy(beaconRssiThreshold = threshold))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
        LocationDialog.BEACON_THRESHOLD -> NumberDialog(
            title = stringResource(R.string.beacon_threshold),
            label = stringResource(R.string.beacon_threshold_label),
            initial = -draft.beaconRssiThreshold,
            range = 30..100,
            hint = stringResource(R.string.beacon_threshold_hint),
            onConfirm = {
                onDraftChange(draft.copy(beaconRssiThreshold = -it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LocationDialog.DEVICE_PICK -> PairedDevicePickerDialog(
            devices = remember { pairedDeviceTools.list() },
            // Only worth the scan when the device will be detected by being nearby.
            seenNearby = pairedDeviceTools.seenNearby.takeIf { draft.btDetection == BtDetection.NEARBY },
            onPick = { device ->
                onDraftChange(draft.copy(btAddress = device.address, btName = device.name))
                editing = if (draft.btDetection == BtDetection.NEARBY && beaconTools.hasPermission) LocationDialog.DEVICE_CALIBRATE else null
            },
            onDismiss = { editing = null },
        )
        LocationDialog.DEVICE_CALIBRATE -> draft.btAddress?.let { address ->
            CalibrationDialog(
                sample = { beaconTools.sample(BeaconRules.deviceKey(address)) },
                onConfirm = { threshold ->
                    onDraftChange(draft.copy(btRssiThreshold = threshold))
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
        LocationDialog.DEVICE_THRESHOLD -> NumberDialog(
            title = stringResource(R.string.beacon_threshold),
            label = stringResource(R.string.beacon_threshold_label),
            initial = -draft.btRssiThreshold,
            range = 30..100,
            hint = stringResource(R.string.beacon_threshold_hint),
            onConfirm = {
                onDraftChange(draft.copy(btRssiThreshold = -it))
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
            text = { Text(stringResource(R.string.location_delete_body)) },
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

private enum class LocationDialog {
    COORDINATES, RADIUS, BEACON_PICK, BEACON_CALIBRATE, BEACON_THRESHOLD, DEVICE_PICK, DEVICE_CALIBRATE, DEVICE_THRESHOLD, TARGET, DELETE,
}

/**
 * The phone's paired devices; tap one to use it. With [seenNearby] (nearby detection), a scan
 * shows which ones this phone can actually hear right now.
 */
@Composable
private fun PairedDevicePickerDialog(
    devices: List<PairedDevice>,
    seenNearby: (suspend () -> Map<String, Int>)?,
    onPick: (PairedDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    var seen by remember { mutableStateOf<Map<String, Int>?>(null) }
    LaunchedEffect(Unit) { seen = seenNearby?.invoke() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bt_device_pick)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (devices.isEmpty()) Text(stringResource(R.string.bt_device_none_paired))
                if (seenNearby != null && seen == null) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.bt_device_checking_nearby))
                }
                devices.forEach { device ->
                    val heard = seen?.get(device.address)
                    val nearbyNote = when {
                        seenNearby == null || seen == null -> null
                        heard != null -> stringResource(R.string.bt_device_heard, heard)
                        else -> stringResource(R.string.bt_device_not_heard)
                    }
                    SettingsRow(
                        icon = pairedDeviceIcon(device.kind),
                        title = device.name,
                        summary = listOfNotNull(stringResource(pairedDeviceKindLabel(device.kind)), nearbyNote).joinToString(" · "),
                        onClick = { onPick(device) },
                    )
                }
                Text(
                    stringResource(if (seenNearby != null) R.string.bt_device_pick_nearby_hint else R.string.bt_device_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun pairedDeviceIcon(kind: PairedDeviceKind) = when (kind) {
    PairedDeviceKind.AUDIO -> Icons.Filled.Headphones
    PairedDeviceKind.WATCH -> Icons.Filled.Watch
    PairedDeviceKind.COMPUTER -> Icons.Filled.Computer
    PairedDeviceKind.PHONE -> Icons.Filled.Smartphone
    PairedDeviceKind.INPUT -> Icons.Filled.Keyboard
    PairedDeviceKind.OTHER -> Icons.Filled.Bluetooth
}

private fun pairedDeviceKindLabel(kind: PairedDeviceKind): Int = when (kind) {
    PairedDeviceKind.AUDIO -> R.string.bt_kind_audio
    PairedDeviceKind.WATCH -> R.string.bt_kind_watch
    PairedDeviceKind.COMPUTER -> R.string.bt_kind_computer
    PairedDeviceKind.PHONE -> R.string.bt_kind_phone
    PairedDeviceKind.INPUT -> R.string.bt_kind_input
    PairedDeviceKind.OTHER -> R.string.bt_kind_other
}

/** A ~15 s scan, then the beacons heard, strongest first; tap one to use it. */
@Composable
private fun BeaconPickerDialog(
    discover: suspend () -> List<BeaconSighting>,
    onPick: (BeaconSighting) -> Unit,
    onDismiss: () -> Unit,
) {
    var found by remember { mutableStateOf<List<BeaconSighting>?>(null) }
    LaunchedEffect(Unit) { found = discover() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.beacon_pick)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val list = found
                when {
                    list == null -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.beacon_scanning))
                    }
                    list.isEmpty() -> Text(stringResource(R.string.beacon_none_found))
                    else -> list.forEach { sighting ->
                        SettingsRow(
                            icon = Icons.Filled.Bluetooth,
                            title = sighting.label,
                            summary = "${stringResource(beaconTypeLabel(sighting))} · ${sighting.rssi} dBm",
                            onClick = { onPick(sighting) },
                        )
                    }
                }
                Text(stringResource(R.string.beacon_pick_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Sit at your spot while it listens for ~20 s; the threshold is set a little below the typical signal. */
@Composable
private fun CalibrationDialog(
    sample: suspend () -> List<Int>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var readings by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(Unit) { readings = sample() }
    val threshold = readings?.let(BeaconRules::calibratedThreshold)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.beacon_calibrate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = readings
                when {
                    list == null -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.beacon_calibrating))
                    }
                    threshold == null -> Text(stringResource(R.string.beacon_calibrate_failed))
                    else -> Text(stringResource(R.string.beacon_calibrated, list.size, list.sorted()[list.size / 2], threshold))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { threshold?.let(onConfirm) }, enabled = threshold != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun beaconTypeLabel(sighting: BeaconSighting): Int = when (sighting.type) {
    com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconType.IBEACON -> R.string.beacon_type_ibeacon
    com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconType.EDDYSTONE -> R.string.beacon_type_eddystone
    com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconType.APP -> R.string.beacon_type_app
    com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconType.DEVICE -> R.string.beacon_type_device
}

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

private fun signalIcon(signal: PlaceSignal) = when (signal) {
    PlaceSignal.GPS -> Icons.Filled.GpsFixed
    PlaceSignal.WIFI -> Icons.Filled.Wifi
    PlaceSignal.BEACON -> Icons.Filled.Bluetooth
    PlaceSignal.BT_DEVICE -> Icons.Filled.BluetoothConnected
    PlaceSignal.CHARGER -> Icons.Filled.BatteryChargingFull
}

fun signalLabel(signal: PlaceSignal): Int = when (signal) {
    PlaceSignal.GPS -> R.string.signal_gps
    PlaceSignal.WIFI -> R.string.signal_wifi
    PlaceSignal.BEACON -> R.string.signal_beacon
    PlaceSignal.BT_DEVICE -> R.string.signal_bt_device
    PlaceSignal.CHARGER -> R.string.signal_charger
}

private fun signalDescription(signal: PlaceSignal): Int = when (signal) {
    PlaceSignal.GPS -> R.string.signal_gps_desc
    PlaceSignal.WIFI -> R.string.signal_wifi_desc
    PlaceSignal.BEACON -> R.string.signal_beacon_desc
    PlaceSignal.BT_DEVICE -> R.string.signal_bt_device_desc
    PlaceSignal.CHARGER -> R.string.signal_charger_desc
}

fun locationModeLabel(mode: LocationMode): Int = when (mode) {
    LocationMode.WHILE_HERE -> R.string.location_mode_while_here
    LocationMode.ON_ARRIVAL -> R.string.location_mode_arrival
    LocationMode.ON_LEAVE -> R.string.location_mode_leave
    LocationMode.TIME_SLOTS -> R.string.location_mode_slots
    LocationMode.CONDITION_ONLY -> R.string.location_mode_condition
}

private fun locationModeDescription(mode: LocationMode): Int = when (mode) {
    LocationMode.WHILE_HERE -> R.string.location_mode_while_here_desc
    LocationMode.ON_ARRIVAL -> R.string.location_mode_arrival_desc
    LocationMode.ON_LEAVE -> R.string.location_mode_leave_desc
    LocationMode.TIME_SLOTS -> R.string.location_mode_slots_desc
    LocationMode.CONDITION_ONLY -> R.string.location_mode_condition_desc
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
