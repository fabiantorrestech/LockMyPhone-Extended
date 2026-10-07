package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiMatch
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiNetwork
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ConnectedWifi
import com.fabiantorrestech.lockmyphone_extended.trigger.location.KnownWifi
import com.fabiantorrestech.lockmyphone_extended.trigger.location.NearbyWifi
import java.text.DateFormat
import java.util.Date

/** What the network picker reads: the app's history, the system's latest scan, and asking for a new one. */
class WifiTools(
    val history: () -> List<KnownWifi>,
    val nearby: () -> List<NearbyWifi>,
    /** False when Android refuses (about 4 scans per 2 minutes). */
    val startScan: () -> Boolean,
)

/**
 * A place's Wi-Fi networks: one row each with how it's recognised (this router / any router
 * with this name) and ✕, then "Add networks".
 */
@Composable
fun WifiNetworkList(networks: List<WifiNetwork>, onChange: (List<WifiNetwork>) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (networks.isEmpty()) {
            Text(stringResource(R.string.wifi_list_empty), style = MaterialTheme.typography.bodyMedium, modifier = SectionContentPadding)
        }
        networks.forEachIndexed { index, network ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Wifi, contentDescription = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(network.ssid ?: stringResource(R.string.location_wifi_unnamed), style = MaterialTheme.typography.bodyLarge)
                        network.bssid?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    IconButton(onClick = { onChange(networks.filterIndexed { i, _ -> i != index }) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.wifi_remove))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = network.matchBy == WifiMatch.ROUTER,
                        onClick = { onChange(networks.toMutableList().also { it[index] = network.copy(matchBy = WifiMatch.ROUTER) }) },
                        label = { Text(stringResource(R.string.wifi_match_router)) },
                        enabled = network.bssid != null,
                    )
                    FilterChip(
                        selected = network.matchBy == WifiMatch.NAME,
                        onClick = { onChange(networks.toMutableList().also { it[index] = network.copy(matchBy = WifiMatch.NAME) }) },
                        label = { Text(stringResource(R.string.wifi_match_name)) },
                        enabled = network.ssid != null,
                    )
                }
                if (network.matchBy == WifiMatch.NAME) {
                    Text(
                        stringResource(R.string.wifi_match_name_warning, network.ssid.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            SectionDivider()
        }
        TextButton(onClick = onAdd, modifier = SectionContentPadding) { Text(stringResource(R.string.wifi_add)) }
    }
}

/** One network in the picker, from either tab. */
private data class PickableWifi(val bssid: String, val ssid: String?, val detail: String)

/**
 * Pick several networks at once, from two tabs:
 * - Connected before: networks this phone has connected to (the app's own history; Android
 *   doesn't share the saved list), the current one marked
 * - Nearby: the latest Wi-Fi scan, with "Scan again"
 * Added as "this router"; already-added ones are disabled.
 */
@Composable
fun WifiPickerDialog(
    tools: WifiTools,
    connected: ConnectedWifi?,
    alreadyAdded: List<WifiNetwork>,
    onAdd: (List<WifiNetwork>) -> Unit,
    onDismiss: () -> Unit,
) {
    var nearbyTab by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf(tools.nearby()) }
    var scanNote by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val connectedNow = stringResource(R.string.wifi_connected_now)

    val history = remember {
        val known = tools.history()
        // The current network first, even before the history has noted it.
        val current = connected?.takeIf { now -> known.none { it.bssid.equals(now.bssid, ignoreCase = true) } }
            ?.let { KnownWifi(it.bssid, it.ssid, System.currentTimeMillis()) }
        listOfNotNull(current) + known
    }
    val rows: List<PickableWifi> = if (nearbyTab) {
        nearby.map { PickableWifi(it.bssid, it.ssid, "${it.rssi} dBm") }
    } else {
        history.map { entry ->
            val isConnected = connected?.bssid.equals(entry.bssid, ignoreCase = true)
            PickableWifi(
                entry.bssid,
                entry.ssid,
                if (isConnected) connectedNow else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.lastConnectedMs)),
            )
        }
    }
    val added = alreadyAdded.mapNotNull { it.bssid?.uppercase() }.toSet()
    // Every pick from both tabs, by router.
    val known = (history.map { it.bssid to it.ssid } + nearby.map { it.bssid to it.ssid }).toMap()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !nearbyTab, onClick = { nearbyTab = false }, label = { Text(stringResource(R.string.wifi_tab_history)) })
                    FilterChip(selected = nearbyTab, onClick = { nearbyTab = true }, label = { Text(stringResource(R.string.wifi_tab_nearby)) })
                }
                if (nearbyTab) {
                    TextButton(onClick = {
                        scanNote = if (tools.startScan()) R.string.wifi_scan_started else R.string.wifi_scan_throttled
                        nearby = tools.nearby()
                    }) { Text(stringResource(R.string.wifi_scan_again)) }
                    scanNote?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { nearby = tools.nearby() }) { Text(stringResource(R.string.wifi_refresh_list)) }
                }
                if (rows.isEmpty()) {
                    Text(
                        stringResource(if (nearbyTab) R.string.wifi_nearby_empty else R.string.wifi_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                rows.forEach { row ->
                    val isAdded = row.bssid.uppercase() in added
                    val checked = isAdded || row.bssid in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isAdded) { selected = if (row.bssid in selected) selected - row.bssid else selected + row.bssid }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { selected = if (it) selected + row.bssid else selected - row.bssid },
                            enabled = !isAdded,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(row.ssid ?: stringResource(R.string.location_wifi_unnamed), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(row.bssid, row.detail, stringResource(R.string.wifi_already_added).takeIf { isAdded }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    stringResource(if (nearbyTab) R.string.wifi_nearby_hint else R.string.wifi_history_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(selected.map { bssid -> WifiNetwork(known[bssid], bssid, WifiMatch.ROUTER) }) },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.wifi_add_count, selected.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
