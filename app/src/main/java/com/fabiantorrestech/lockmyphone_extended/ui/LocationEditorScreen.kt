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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconPreset
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconTiming
import com.fabiantorrestech.lockmyphone_extended.trigger.location.beaconTiming
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDevices
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconSpot
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RangeRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Walls
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconGroup
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconStatus
import kotlin.math.roundToInt
import com.fabiantorrestech.lockmyphone_extended.trigger.location.knownOneMeterDbm
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
    /** A ~15 s scan listing nearby beacons, one per physical beacon, strongest first. */
    val discover: suspend () -> List<BeaconGroup>,
    /** ~20 s of readings of one beacon, for calibration. */
    val sample: suspend (key: String) -> List<Int>,
    /** Your nicknames for beacons, by key (`device:<address>` for a whole beacon). */
    val names: Map<String, String> = emptyMap(),
    /** Saves a nickname for a beacon (all its formats when [address] is known); blank removes it. */
    val rename: (key: String, address: String?, name: String) -> Unit = { _, _, _ -> },
    /** An ~8 s scan for the 1 m signal a beacon says it has (iBeacon, Eddystone, AltBeacon), or null. */
    val advertisedPower: suspend (key: String) -> Int? = { null },
    /** Every sighting for [durationMs], for the live signal meter. */
    val watch: suspend (durationMs: Long, onSightings: (List<BeaconSighting>) -> Unit) -> Unit = { _, _ -> },
    /** Settings → Beacons: how quickly places react unless they have their own. */
    val defaultPreset: BeaconPreset = BeaconPreset.BALANCED,
    val defaultTiming: BeaconTiming = BeaconTiming.BALANCED,
    /** The background check's view of a place's beacon (device = its nearby paired device), for the too-strict warning. */
    val status: (place: LocationTrigger, device: Boolean) -> BeaconStatus? = { _, _ -> null },
) {
    /** A beacon's nickname: the whole beacon's (by address) first, else the format key's. */
    fun nameOf(key: String?, address: String?): String? =
        address?.let { names[BeaconRules.deviceKey(it)] } ?: key?.let { names[it] }
}

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
    wifiTools: WifiTools,
    pairedDeviceTools: PairedDeviceTools,
    onSlotsChange: (List<LocationSlot>) -> Unit,
    slotClash: String?,
    onRequestLocationPermission: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onPreviewInMaps: () -> Unit,
    onDraftChange: (LocationTrigger) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The hold note and "Test now"; null for a new automation's editor or none at all. */
    automation: AutomationEditorExtras? = null,
    /** The Morning locks using this place as a sleep place (empty = none). */
    sleepPlaceOf: List<String> = emptyList(),
) {
    var editing by remember { mutableStateOf<LocationDialog?>(null) }
    // Made by Morning lock (condition only): say so, so it isn't deleted by mistake.
    val madeByMorningLock = sleepPlaceOf.isNotEmpty() && draft.mode == LocationMode.CONDITION_ONLY
    // The Wi-Fi network picker for the place's Wi-Fi signal is open.
    var pickingWifi by remember { mutableStateOf(false) }
    // The guided setup that is open (beacon or nearby device), if any.
    var settingUp by remember { mutableStateOf<SetupKind?>(null) }
    // A measuring dialog opened from the Range screen: for which signal, and how.
    var measuring by remember { mutableStateOf<Pair<SetupKind, AreaMethod>?>(null) }
    // The slot being edited in its dialog: an index into [slots], or -1 for a new one.
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    val isNew = draft.id == 0L
    val timeSlots = draft.mode == LocationMode.TIME_SLOTS
    val signals = draft.signalSet
    val hasPlace = !draft.latitude.isNaN() && !draft.longitude.isNaN()
    val targetProfile = profiles.firstOrNull { it.id == draft.profileId }
    // How quickly this place's beacon (or nearby device) reacts: its own preset, or the default.
    val placePreset = BeaconPreset.entries.firstOrNull { it.name == draft.beaconPreset }
    val placeTiming = draft.beaconTiming(beaconTools.defaultTiming)

    val whenComplete = when (draft.mode) {
        LocationMode.TIME_SLOTS -> slots.isNotEmpty() && slots.all { it.isValid }
        LocationMode.CONDITION_ONLY -> true
        else -> activeOnComplete(draft.activeRule, draft.daysMask, draft.dates)
    }
    val signalsComplete = signals.isNotEmpty() &&
        (PlaceSignal.GPS !in signals || hasPlace) &&
        (PlaceSignal.WIFI !in signals || draft.wifiNetworks.isNotEmpty()) &&
        (PlaceSignal.BEACON !in signals || draft.beaconKey != null) &&
        (PlaceSignal.BT_DEVICE !in signals || draft.btAddress != null)

    fun toggleSignal(signal: PlaceSignal) {
        val turningOn = signal !in signals
        val names = if (turningOn) draft.signals + signal.name else draft.signals - signal.name
        onDraftChange(draft.copy(signals = names))
        // A beacon that isn't set up yet: go straight to the guided setup (spec §2).
        if (turningOn && signal == PlaceSignal.BEACON && draft.beaconKey == null && beaconTools.hasPermission && beaconTools.bluetoothOn) {
            settingUp = SetupKind.BEACON
        }
    }

    EditorScaffold(
        title = stringResource(if (isNew) R.string.location_new else R.string.location_edit_title),
        onBack = onBack,
        saveEnabled = draft.name.isNotBlank() && signalsComplete && whenComplete,
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = LocationDialog.DELETE }),
        modifier = modifier,
        automation = automation,
    ) {
        if (sleepPlaceOf.isNotEmpty()) {
            WarningNote(
                stringResource(
                    if (madeByMorningLock) R.string.place_sleep_banner else R.string.place_sleep_banner_shared,
                    sleepPlaceOf.joinToString(),
                )
            )
        }
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
                // Connected to any one of these = here.
                WifiNetworkList(
                    networks = draft.wifiNetworks,
                    onChange = { onDraftChange(draft.copy(wifiNetworks = it)) },
                    onAdd = { pickingWifi = true },
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
            val canScan = beaconTools.hasPermission && beaconTools.bluetoothOn
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
                    title = stringResource(R.string.beacon_row),
                    summary = draft.beaconKey?.let { key ->
                        listOf(
                            beaconTools.nameOf(key, draft.beaconAddress) ?: draft.beaconName ?: "?",
                            stringResource(matchLabel(key)),
                            "ID ${BeaconRules.shortId(draft.beaconAddress?.let(BeaconRules::deviceKey) ?: key)}",
                        ).joinToString(" · ")
                    } ?: stringResource(R.string.beacon_not_set_up),
                    onClick = { settingUp = SetupKind.BEACON },
                    enabled = canScan,
                )
                if (draft.beaconKey != null) {
                    SectionDivider()
                    SettingsRow(
                        icon = Icons.Filled.SignalCellularAlt,
                        title = stringResource(R.string.range_row),
                        summary = rangeSummary(draft.beaconRssiThreshold, draft.knownOneMeterDbm),
                        onClick = { editing = LocationDialog.RANGE_BEACON },
                    )
                    SectionDivider()
                    SettingsRow(
                        icon = Icons.Filled.Speed,
                        title = stringResource(R.string.beacon_timing_title),
                        summary = beaconTimingLabel(LocalContext.current, placePreset, beaconTools.defaultPreset) +
                            "\n" + timingSummary(LocalContext.current, placeTiming),
                        onClick = { editing = LocationDialog.TIMING },
                    )
                    SectionDivider()
                    SettingsRow(
                        icon = Icons.Filled.GraphicEq,
                        title = stringResource(R.string.live_row),
                        summary = stringResource(R.string.live_row_hint),
                        onClick = { editing = LocationDialog.BEACON_LIVE },
                        enabled = canScan,
                    )
                    val status = remember(draft.id, draft.beaconKey) { beaconTools.status(draft, false) }
                    if (BeaconRules.rangeTooStrict(status, draft.beaconRssiThreshold, System.currentTimeMillis())) {
                        TooStrictWarning(
                            averageDbm = status?.averageDbm?.roundToInt() ?: 0,
                            thresholdDbm = draft.beaconRssiThreshold,
                            onMeasureAgain = { editing = LocationDialog.RANGE_BEACON },
                        )
                    }
                }
                Text(
                    stringResource(R.string.beacon_hint, formatWait(LocalContext.current, placeTiming.arriveMs), formatWait(LocalContext.current, placeTiming.leaveMs)),
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
                    title = stringResource(R.string.device_row),
                    summary = draft.btAddress?.let { address ->
                        beaconTools.nameOf(BeaconRules.deviceKey(address), PairedDevices.normalize(address)) ?: draft.btName
                    } ?: stringResource(R.string.bt_device_none),
                    // Detected nearby: the guided setup sets its range too. Connected: just pick it.
                    onClick = { if (nearby) settingUp = SetupKind.DEVICE else editing = LocationDialog.DEVICE_PICK },
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
                    if (draft.btAddress != null) {
                        val canScan = beaconTools.hasPermission && beaconTools.bluetoothOn
                        SectionDivider()
                        SettingsRow(
                            icon = Icons.Filled.SignalCellularAlt,
                            title = stringResource(R.string.range_row),
                            summary = rangeSummary(draft.btRssiThreshold, null),
                            onClick = { editing = LocationDialog.RANGE_DEVICE },
                        )
                        // Without a beacon signal, the nearby device is the only one using this timing.
                        if (PlaceSignal.BEACON !in signals) {
                            SectionDivider()
                            SettingsRow(
                                icon = Icons.Filled.Speed,
                                title = stringResource(R.string.beacon_timing_title),
                                summary = beaconTimingLabel(LocalContext.current, placePreset, beaconTools.defaultPreset) +
                                    "\n" + timingSummary(LocalContext.current, placeTiming),
                                onClick = { editing = LocationDialog.TIMING },
                            )
                        }
                        SectionDivider()
                        SettingsRow(
                            icon = Icons.Filled.GraphicEq,
                            title = stringResource(R.string.live_row),
                            summary = stringResource(R.string.live_row_hint),
                            onClick = { editing = LocationDialog.DEVICE_LIVE },
                            enabled = canScan,
                        )
                        val status = remember(draft.id, draft.btAddress) { beaconTools.status(draft, true) }
                        if (BeaconRules.rangeTooStrict(status, draft.btRssiThreshold, System.currentTimeMillis())) {
                            TooStrictWarning(
                                averageDbm = status?.averageDbm?.roundToInt() ?: 0,
                                thresholdDbm = draft.btRssiThreshold,
                                onMeasureAgain = { editing = LocationDialog.RANGE_DEVICE },
                            )
                        }
                    }
                }
                Text(
                    if (nearby) {
                        stringResource(R.string.bt_nearby_hint, formatWait(LocalContext.current, placeTiming.arriveMs), formatWait(LocalContext.current, placeTiming.leaveMs))
                    } else {
                        stringResource(R.string.bt_connected_hint)
                    },
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
                    slotClash?.let { WarningNote(it) }
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
        LocationDialog.RANGE_BEACON -> BeaconRangeDialog(
            thresholdDbm = draft.beaconRssiThreshold,
            oneMeterDbm = draft.knownOneMeterDbm,
            canMeasure = draft.beaconKey != null && beaconTools.hasPermission && beaconTools.bluetoothOn,
            onMeasure = { method ->
                measuring = SetupKind.BEACON to method
                editing = null
            },
            onRoomSize = { editing = LocationDialog.BEACON_RANGE },
            onTypeValue = { editing = LocationDialog.BEACON_THRESHOLD },
            onGuide = { editing = LocationDialog.NEAR_INFO },
            onDismiss = { editing = null },
        )
        LocationDialog.RANGE_DEVICE -> BeaconRangeDialog(
            thresholdDbm = draft.btRssiThreshold,
            oneMeterDbm = null,
            canMeasure = draft.btAddress != null && beaconTools.hasPermission && beaconTools.bluetoothOn,
            onMeasure = { method ->
                measuring = SetupKind.DEVICE to method
                editing = null
            },
            onRoomSize = { editing = LocationDialog.DEVICE_RANGE },
            onTypeValue = { editing = LocationDialog.DEVICE_THRESHOLD },
            onGuide = { editing = LocationDialog.NEAR_INFO },
            onDismiss = { editing = null },
        )
        LocationDialog.BOOST -> BeaconBoostDialog(
            draft = draft,
            currentWifi = currentWifi,
            wifiTools = wifiTools,
            locating = fixRequest == FixRequest.Locating,
            onUseCurrentLocation = onUseCurrentLocation,
            onConfirm = { boost, zones, networks ->
                onDraftChange(
                    draft.copy(
                        beaconBoost = boost?.name,
                        beaconBoostZones = zones.map { it.name }.toSet(),
                        beaconBoostWifiNetworks = networks,
                    )
                )
                editing = null
            },
            onDismiss = { editing = null },
        )
        LocationDialog.TIMING -> BeaconTimingDialog(
            preset = placePreset,
            custom = placeTiming,
            defaultTiming = beaconTools.defaultTiming,
            onConfirm = { preset, custom ->
                val isCustom = preset == BeaconPreset.CUSTOM
                onDraftChange(
                    draft.copy(
                        beaconPreset = preset?.name,
                        // The numbers only matter for Advanced; other choices keep none.
                        beaconArriveSec = (custom.arriveMs / 1_000).toInt().takeIf { isCustom },
                        beaconLeaveSec = (custom.leaveMs / 1_000).toInt().takeIf { isCustom },
                        beaconRecheckSec = (custom.recheckMs / 1_000).toInt().takeIf { isCustom },
                        beaconScanSpeed = custom.scanSpeed.name.takeIf { isCustom },
                    )
                )
                editing = null
            },
            onDismiss = { editing = null },
            boostSummary = boostSummary(LocalContext.current, draft),
            onOpenBoost = { editing = LocationDialog.BOOST },
        )
        LocationDialog.NEAR_INFO -> NearMeansInfoDialog(
            // The beacon section's 1 m signal; the paired-device section has none saved.
            oneMeterDbm = draft.knownOneMeterDbm.takeIf { PlaceSignal.BEACON in signals },
            onDismiss = { editing = null },
        )
        LocationDialog.BEACON_LIVE -> draft.beaconKey?.let { key ->
            LiveSignalDialog(
                matchKey = key,
                address = draft.beaconAddress,
                thresholdDbm = draft.beaconRssiThreshold,
                oneMeterDbm = draft.knownOneMeterDbm,
                watch = beaconTools.watch,
                onDismiss = { editing = null },
            )
        }
        LocationDialog.DEVICE_LIVE -> draft.btAddress?.let { address ->
            LiveSignalDialog(
                matchKey = BeaconRules.deviceKey(address),
                address = PairedDevices.normalize(address),
                thresholdDbm = draft.btRssiThreshold,
                oneMeterDbm = null,
                watch = beaconTools.watch,
                onDismiss = { editing = null },
            )
        }
        LocationDialog.BEACON_RANGE -> RangeCalculatorDialog(
            savedOneMeter = draft.knownOneMeterDbm,
            readAdvertised = {
                val key = draft.beaconKey
                if (key != null && beaconTools.hasPermission && beaconTools.bluetoothOn) beaconTools.advertisedPower(key) else null
            },
            measureOneMeter = { draft.beaconKey?.let { beaconTools.sample(it) }.orEmpty() },
            onUse = { value, oneMeter ->
                onDraftChange(draft.copy(beaconRssiThreshold = -value, beaconOneMeterDbm = oneMeter ?: draft.knownOneMeterDbm))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LocationDialog.DEVICE_RANGE -> RangeCalculatorDialog(
            savedOneMeter = null,
            // Paired devices don't broadcast their 1 m signal; it can be measured.
            readAdvertised = { null },
            measureOneMeter = {
                draft.btAddress?.takeIf { beaconTools.hasPermission && beaconTools.bluetoothOn }
                    ?.let { beaconTools.sample(BeaconRules.deviceKey(it)) }
                    .orEmpty()
            },
            onUse = { value, _ ->
                onDraftChange(draft.copy(btRssiThreshold = -value))
                editing = null
            },
            onDismiss = { editing = null },
        )
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
                editing = null
            },
            onDismiss = { editing = null },
        )
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
            text = {
                Text(
                    if (sleepPlaceOf.isEmpty()) {
                        stringResource(R.string.location_delete_body)
                    } else {
                        stringResource(R.string.place_sleep_delete_body, sleepPlaceOf.joinToString()) + "\n\n" +
                            stringResource(R.string.location_delete_body)
                    }
                )
            },
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

    if (pickingWifi) {
        WifiPickerDialog(
            tools = wifiTools,
            connected = currentWifi,
            alreadyAdded = draft.wifiNetworks,
            onAdd = { picked ->
                onDraftChange(draft.copy(wifiNetworks = draft.wifiNetworks + picked))
                pickingWifi = false
            },
            onDismiss = { pickingWifi = false },
        )
    }

    settingUp?.let { kind ->
        val initial = if (kind == SetupKind.BEACON) {
            BeaconSetupState(
                key = draft.beaconKey,
                address = draft.beaconAddress,
                label = draft.beaconName,
                oneMeterDbm = draft.knownOneMeterDbm,
                name = beaconTools.nameOf(draft.beaconKey, draft.beaconAddress).orEmpty(),
                thresholdDbm = draft.beaconKey?.let { draft.beaconRssiThreshold },
            )
        } else {
            BeaconSetupState(
                key = draft.btAddress?.let(BeaconRules::deviceKey),
                address = draft.btAddress,
                label = draft.btName,
                name = draft.btAddress?.let { beaconTools.nameOf(BeaconRules.deviceKey(it), PairedDevices.normalize(it)) }.orEmpty(),
                thresholdDbm = draft.btAddress?.let { draft.btRssiThreshold },
            )
        }
        BeaconSetupFlow(
            kind = kind,
            initial = initial,
            beaconTools = beaconTools,
            pairedDeviceTools = pairedDeviceTools,
            onDone = { result ->
                // Only Done writes; Cancel changed nothing.
                val nameAddress = if (kind == SetupKind.DEVICE) result.address?.let(PairedDevices::normalize) else result.address
                if (result.name.isNotBlank() && result.name != beaconTools.nameOf(result.key, nameAddress)) {
                    beaconTools.rename(result.key, nameAddress, result.name)
                }
                onDraftChange(
                    if (kind == SetupKind.BEACON) {
                        draft.copy(
                            beaconKey = result.key,
                            beaconAddress = result.address,
                            beaconName = result.name.ifBlank { null } ?: result.label,
                            beaconOneMeterDbm = result.oneMeterDbm,
                            beaconRssiThreshold = result.thresholdDbm,
                        )
                    } else {
                        draft.copy(
                            btAddress = result.address,
                            btName = result.label ?: draft.btName,
                            btRssiThreshold = result.thresholdDbm,
                        )
                    }
                )
                settingUp = null
            },
            onCancel = { settingUp = null },
        )
    }

    measuring?.let { (kind, method) ->
        val key = if (kind == SetupKind.BEACON) draft.beaconKey else draft.btAddress?.let(BeaconRules::deviceKey)
        if (key != null) {
            BeaconMeasureDialog(
                watch = beaconTools.watch,
                key = key,
                method = method,
                oneMeterDbm = if (kind == SetupKind.BEACON) draft.knownOneMeterDbm else null,
                onUse = { threshold ->
                    onDraftChange(
                        if (kind == SetupKind.BEACON) draft.copy(beaconRssiThreshold = threshold) else draft.copy(btRssiThreshold = threshold)
                    )
                    measuring = null
                },
                onDismiss = { measuring = null },
            )
        }
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
    COORDINATES, RADIUS, BEACON_THRESHOLD, BEACON_RANGE, BEACON_LIVE, RANGE_BEACON, NEAR_INFO,
    DEVICE_PICK, DEVICE_THRESHOLD, DEVICE_RANGE, DEVICE_LIVE, RANGE_DEVICE, TIMING, BOOST, TARGET, DELETE,
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
                PairedDeviceList(
                    devices = devices,
                    seen = seen,
                    scanning = seenNearby != null && seen == null,
                    selectedAddress = null,
                    onPick = onPick,
                )
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

/**
 * What "Near means" numbers mean for *this* beacon: from its 1 m signal ([oneMeterDbm], or a
 * typical one), the value at a few distances and good starting values for common spots, worked
 * out with [RangeRules]. Then why calibrating beats guessing, and a tip for multi-format beacons.
 */
@Composable
private fun NearMeansInfoDialog(oneMeterDbm: Int?, onDismiss: () -> Unit) {
    val oneMeter = oneMeterDbm ?: RangeRules.DEFAULT_ONE_METER_DBM
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.near_info_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.near_info_intro), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (oneMeterDbm != null) {
                        stringResource(R.string.near_info_one_meter_known, oneMeterDbm)
                    } else {
                        stringResource(R.string.near_info_one_meter_typical, oneMeter)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.near_info_distances_title), style = MaterialTheme.typography.titleSmall)
                NEAR_INFO_DISTANCES.forEach { (labelRes, meters) ->
                    Text(
                        "• " + stringResource(R.string.near_info_line, RangeRules.typedValueAt(meters, oneMeter), stringResource(labelRes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(R.string.near_info_uses_title), style = MaterialTheme.typography.titleSmall)
                NEAR_INFO_USES.forEach { use ->
                    val value = RangeRules.suggest(oneMeter, use.walls, use.farthestMeters).typedValue
                    Text("• " + stringResource(R.string.near_info_line, value, stringResource(use.labelRes)), style = MaterialTheme.typography.bodyMedium)
                }
                Text("• " + stringResource(R.string.near_info_use_house), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.beacon_feasy_tip), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.near_info_caveat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.near_info_tools), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** The distances the ⓘ guide lists: what to type to count as near up to there. */
private val NEAR_INFO_DISTANCES = listOf(
    R.string.near_info_d_touching to 0.05,
    R.string.near_info_d_25cm to 0.25,
    R.string.near_info_d_1m to 1.0,
    R.string.near_info_d_2m to 2.5,
    R.string.near_info_d_4m to 4.0,
    R.string.near_info_d_7m to 7.5,
    R.string.near_info_d_15m to 15.0,
)

/** A common spot, as the 📐 calculator would work it out (the beacon in the middle). */
private class NearInfoUse(val labelRes: Int, val farthestMeters: Double, val walls: Walls)

private val NEAR_INFO_USES = listOf(
    NearInfoUse(R.string.near_info_use_desk, RangeRules.farthestInRoom(1.2, 0.8, BeaconSpot.CENTER), Walls.OPEN),
    NearInfoUse(R.string.near_info_use_nightstand, 1.0, Walls.OPEN),
    NearInfoUse(R.string.near_info_use_small_room, RangeRules.farthestInArea(12.0, BeaconSpot.CENTER), Walls.OPEN),
    NearInfoUse(R.string.near_info_use_large_room, RangeRules.farthestInArea(25.0, BeaconSpot.CENTER), Walls.OPEN),
    NearInfoUse(R.string.near_info_use_studio, RangeRules.farthestInArea(40.0, BeaconSpot.CENTER), Walls.SOME),
    NearInfoUse(R.string.near_info_use_one_bed, RangeRules.farthestInArea(65.0, BeaconSpot.CENTER), Walls.SOME),
    NearInfoUse(R.string.near_info_use_two_bed, RangeRules.farthestInArea(95.0, BeaconSpot.CENTER), Walls.SOME),
)

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
