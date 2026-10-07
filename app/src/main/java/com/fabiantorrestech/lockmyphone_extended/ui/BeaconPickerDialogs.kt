package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconGroup
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconType
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDevice
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDeviceKind
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RangeRules

/**
 * One row per physical beacon: nickname or name, short ID, formats, signal and (when its 1 m
 * signal is known) a rough distance. [selectedAddress] is marked; [onRename] adds ✏ when set.
 */
@Composable
fun BeaconGroupList(
    groups: List<BeaconGroup>,
    nameOf: (BeaconGroup) -> String?,
    selectedAddress: String?,
    onPick: (BeaconGroup) -> Unit,
    onRename: ((BeaconGroup) -> Unit)? = null,
) {
    groups.forEach { group ->
        val formats = group.frames.map { stringResource(beaconTypeLabel(it.type)) }
            .ifEmpty { listOf(stringResource(R.string.beacon_type_device)) }
        val distance = group.oneMeterPower?.let { power ->
            "≈ " + RangeRules.formatBoth(RangeRules.estimateDistance(group.strongestRssi.toDouble(), power))
        }
        val selected = group.address == selectedAddress
        SettingsRow(
            icon = if (selected) Icons.Filled.CheckCircle else Icons.Filled.Bluetooth,
            title = nameOf(group) ?: group.name ?: stringResource(R.string.beacon_unnamed),
            summary = listOfNotNull(
                "ID ${BeaconRules.shortId(group.deviceKey)}",
                formats.joinToString(" + "),
                "${group.strongestRssi} dBm",
                distance,
            ).joinToString(" · "),
            onClick = { onPick(group) },
            trailing = onRename?.let { rename ->
                {
                    IconButton(onClick = { rename(group) }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.beacon_nickname))
                    }
                }
            },
        )
    }
}

/**
 * The ways [group] can be recognised: any signal (by address, unless it rotates) or one format's
 * ID. [onSelect] gets the key and that choice's 1 m signal.
 */
@Composable
fun MatchByOptions(group: BeaconGroup, selected: String?, onSelect: (key: String, oneMeterPower: Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!group.addressRotates) {
            RadioSettingsRow(
                title = stringResource(R.string.match_by_any),
                summary = stringResource(R.string.match_by_any_desc, group.strongestRssi),
                selected = selected == group.deviceKey,
                onSelect = { onSelect(group.deviceKey, group.oneMeterPower) },
            )
        } else {
            Text(stringResource(R.string.match_by_rotates), style = MaterialTheme.typography.bodySmall)
        }
        group.frames.forEach { frame ->
            RadioSettingsRow(
                title = stringResource(R.string.match_by_format, stringResource(beaconTypeLabel(frame.type))),
                summary = listOfNotNull(
                    "${frame.rssi} dBm",
                    frame.oneMeterPower?.let { stringResource(R.string.match_by_one_meter, it) },
                    "ID ${BeaconRules.shortId(frame.key)}",
                ).joinToString(" · "),
                selected = selected == frame.key,
                onSelect = { onSelect(frame.key, frame.oneMeterPower ?: group.oneMeterPower) },
            )
        }
        if (group.addressRotates && group.frames.isEmpty()) {
            Text(stringResource(R.string.match_by_nothing), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The phone's paired devices; with [seen] (a nearby scan), which ones it can hear right now. */
@Composable
fun PairedDeviceList(
    devices: List<PairedDevice>,
    seen: Map<String, Int>?,
    scanning: Boolean,
    selectedAddress: String?,
    onPick: (PairedDevice) -> Unit,
) {
    if (devices.isEmpty()) Text(stringResource(R.string.bt_device_none_paired))
    if (scanning) {
        CircularProgressIndicator()
        Text(stringResource(R.string.bt_device_checking_nearby))
    }
    devices.forEach { device ->
        val heard = seen?.get(device.address)
        val nearbyNote = when {
            seen == null -> null
            heard != null -> stringResource(R.string.bt_device_heard, heard)
            else -> stringResource(R.string.bt_device_not_heard)
        }
        SettingsRow(
            icon = if (device.address == selectedAddress) Icons.Filled.CheckCircle else pairedDeviceIcon(device.kind),
            title = device.name,
            summary = listOfNotNull(stringResource(pairedDeviceKindLabel(device.kind)), nearbyNote).joinToString(" · "),
            onClick = { onPick(device) },
        )
    }
}

fun pairedDeviceIcon(kind: PairedDeviceKind): ImageVector = when (kind) {
    PairedDeviceKind.AUDIO -> Icons.Filled.Headphones
    PairedDeviceKind.WATCH -> Icons.Filled.Watch
    PairedDeviceKind.COMPUTER -> Icons.Filled.Computer
    PairedDeviceKind.PHONE -> Icons.Filled.Smartphone
    PairedDeviceKind.INPUT -> Icons.Filled.Keyboard
    PairedDeviceKind.OTHER -> Icons.Filled.Bluetooth
}

fun pairedDeviceKindLabel(kind: PairedDeviceKind): Int = when (kind) {
    PairedDeviceKind.AUDIO -> R.string.bt_kind_audio
    PairedDeviceKind.WATCH -> R.string.bt_kind_watch
    PairedDeviceKind.COMPUTER -> R.string.bt_kind_computer
    PairedDeviceKind.PHONE -> R.string.bt_kind_phone
    PairedDeviceKind.INPUT -> R.string.bt_kind_input
    PairedDeviceKind.OTHER -> R.string.bt_kind_other
}

fun beaconTypeLabel(type: BeaconType): Int = when (type) {
    BeaconType.IBEACON -> R.string.beacon_type_ibeacon
    BeaconType.EDDYSTONE -> R.string.beacon_type_eddystone
    BeaconType.ALTBEACON -> R.string.beacon_type_altbeacon
    BeaconType.APP -> R.string.beacon_type_app
    BeaconType.DEVICE -> R.string.beacon_type_device
}

/** "any signal" / "iBeacon ID"…: how a place with [key] recognises its beacon. */
fun matchLabel(key: String): Int = when (BeaconRules.typeOf(key)) {
    BeaconType.DEVICE, null -> R.string.match_label_any
    BeaconType.IBEACON -> R.string.match_label_ibeacon
    BeaconType.EDDYSTONE -> R.string.match_label_eddystone
    BeaconType.ALTBEACON -> R.string.match_label_altbeacon
    BeaconType.APP -> R.string.match_label_app
}
