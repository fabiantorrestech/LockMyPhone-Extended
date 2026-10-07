package com.fabiantorrestech.lockmyphone_extended.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiNetwork
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconBoost
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BoostRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BoostZone
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ConnectedWifi
import com.fabiantorrestech.lockmyphone_extended.trigger.location.boost
import com.fabiantorrestech.lockmyphone_extended.trigger.location.hasCoordinates

/**
 * "Listen harder": when a beacon place's background scan speeds up before its beacon is heard.
 * - Automatic (default, Simple): when its own Wi-Fi / GPS / charger signal says you're close
 * - Only after the beacon is heard
 * - Chosen zones (Pro): a Wi-Fi network, near the place's location, while charging
 * Once the beacon is heard, the scan listens harder anyway, until you count as gone.
 *
 * @param draft the place being edited (its signals decide what Automatic uses).
 * @param currentWifi the network connected now, offered as the Wi-Fi zone.
 * @param onUseCurrentLocation sets the place's location to here (the GPS zone's center).
 * @param onConfirm the choice (null = Automatic), the chosen zones, and the Wi-Fi zone's networks
 *   (connected to, or in range of, any one of them).
 */
@Composable
fun BeaconBoostDialog(
    draft: LocationTrigger,
    currentWifi: ConnectedWifi?,
    wifiTools: WifiTools,
    locating: Boolean,
    onUseCurrentLocation: () -> Unit,
    onConfirm: (boost: BeaconBoost?, zones: Set<BoostZone>, networks: List<WifiNetwork>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var boost by remember { mutableStateOf(draft.boost) }
    var zones by remember {
        mutableStateOf(draft.beaconBoostZones.mapNotNull { name -> BoostZone.entries.firstOrNull { it.name == name } }.toSet())
    }
    var networks by remember { mutableStateOf(draft.beaconBoostWifiNetworks) }
    var pickingWifi by remember { mutableStateOf(false) }
    val autoZones = BoostRules.zonesFor(BeaconBoost.AUTO, emptySet(), draft.usesWifi, draft.usesGps, draft.usesCharger)

    fun toggle(zone: BoostZone) {
        zones = if (zone in zones) zones - zone else zones + zone
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.boost_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.boost_intro), style = MaterialTheme.typography.bodySmall)
                RadioSettingsRow(
                    title = stringResource(R.string.boost_auto),
                    summary = if (autoZones.isEmpty()) {
                        stringResource(R.string.boost_auto_none)
                    } else {
                        stringResource(R.string.boost_auto_desc, zoneNames(context, autoZones))
                    },
                    selected = boost == BeaconBoost.AUTO,
                    onSelect = { boost = BeaconBoost.AUTO },
                )
                RadioSettingsRow(
                    title = stringResource(R.string.boost_heard_only),
                    summary = stringResource(R.string.boost_heard_only_desc),
                    selected = boost == BeaconBoost.HEARD_ONLY,
                    onSelect = { boost = BeaconBoost.HEARD_ONLY },
                )
                RadioSettingsRow(
                    title = stringResource(R.string.boost_zones),
                    summary = stringResource(R.string.boost_zones_desc),
                    selected = boost == BeaconBoost.ZONES,
                    onSelect = { boost = BeaconBoost.ZONES },
                )
                if (boost == BeaconBoost.ZONES) {
                    SettingsRow(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.boost_zone_wifi),
                        summary = if (networks.isEmpty()) {
                            stringResource(R.string.boost_zone_wifi_none)
                        } else {
                            stringResource(R.string.boost_zone_wifi_set, networks.joinToString(", ") { it.label })
                        },
                        onClick = { if (networks.isNotEmpty()) toggle(BoostZone.WIFI) else pickingWifi = true },
                        trailing = {
                            Checkbox(
                                checked = BoostZone.WIFI in zones && networks.isNotEmpty(),
                                onCheckedChange = { if (networks.isNotEmpty()) toggle(BoostZone.WIFI) else pickingWifi = true },
                            )
                        },
                    )
                    if (BoostZone.WIFI in zones || networks.isNotEmpty()) {
                        WifiNetworkList(networks = networks, onChange = { networks = it }, onAdd = { pickingWifi = true })
                        Text(stringResource(R.string.boost_zone_wifi_in_range), style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsRow(
                        icon = Icons.Filled.MyLocation,
                        title = stringResource(R.string.boost_zone_gps),
                        summary = when {
                            locating -> stringResource(R.string.location_locating)
                            draft.hasCoordinates -> stringResource(
                                R.string.boost_zone_gps_set,
                                formatCoordinates(draft.latitude, draft.longitude),
                                draft.radiusMeters + BoostRules.GPS_ZONE_MARGIN_METERS,
                            )
                            else -> stringResource(R.string.boost_zone_gps_none)
                        },
                        onClick = { if (draft.hasCoordinates) toggle(BoostZone.GPS) else onUseCurrentLocation() },
                        trailing = {
                            Checkbox(
                                checked = BoostZone.GPS in zones && draft.hasCoordinates,
                                onCheckedChange = { if (draft.hasCoordinates) toggle(BoostZone.GPS) else onUseCurrentLocation() },
                            )
                        },
                    )
                    if (!draft.usesGps) {
                        TextButton(onClick = onUseCurrentLocation, enabled = !locating) {
                            Text(stringResource(R.string.boost_zone_gps_use_here))
                        }
                    }
                    SettingsRow(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = stringResource(R.string.boost_zone_charger),
                        summary = null,
                        onClick = { toggle(BoostZone.CHARGER) },
                        trailing = { Checkbox(checked = BoostZone.CHARGER in zones, onCheckedChange = { toggle(BoostZone.CHARGER) }) },
                    )
                }
                Text(
                    stringResource(R.string.boost_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val chosen = zones.filter { zone ->
                    when (zone) {
                        BoostZone.WIFI -> networks.isNotEmpty()
                        BoostZone.GPS -> draft.hasCoordinates
                        BoostZone.CHARGER -> true
                    }
                }.toSet()
                onConfirm(boost.takeUnless { it == BeaconBoost.AUTO }, chosen, networks)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (pickingWifi) {
        WifiPickerDialog(
            tools = wifiTools,
            connected = currentWifi,
            alreadyAdded = networks,
            onAdd = { picked ->
                networks = networks + picked
                zones = zones + BoostZone.WIFI
                pickingWifi = false
            },
            onDismiss = { pickingWifi = false },
        )
    }
}

/** "Automatic (Wi-Fi + GPS)", "Only after it's heard", "Wi-Fi Home · charging". */
fun boostSummary(context: Context, place: LocationTrigger): String = when (place.boost) {
    BeaconBoost.AUTO -> {
        val zones = BoostRules.zonesFor(BeaconBoost.AUTO, emptySet(), place.usesWifi, place.usesGps, place.usesCharger)
        if (zones.isEmpty()) {
            context.getString(R.string.boost_summary_auto_none)
        } else {
            context.getString(R.string.boost_summary_auto, zoneNames(context, zones))
        }
    }
    BeaconBoost.HEARD_ONLY -> context.getString(R.string.boost_heard_only)
    BeaconBoost.ZONES -> {
        val zones = place.beaconBoostZones.mapNotNull { name -> BoostZone.entries.firstOrNull { it.name == name } }.toSet()
        if (zones.isEmpty()) {
            context.getString(R.string.boost_heard_only)
        } else {
            zoneNames(context, zones, place.beaconBoostWifiNetworks.joinToString(", ") { it.label }.ifEmpty { null })
        }
    }
}

private fun zoneNames(context: Context, zones: Set<BoostZone>, wifiName: String? = null): String =
    zones.sortedBy { it.ordinal }.joinToString(" · ") { zone ->
        when (zone) {
            BoostZone.WIFI -> wifiName?.let { context.getString(R.string.boost_name_wifi_named, it) } ?: context.getString(R.string.boost_name_wifi)
            BoostZone.GPS -> context.getString(R.string.boost_name_gps)
            BoostZone.CHARGER -> context.getString(R.string.boost_name_charger)
        }
    }
