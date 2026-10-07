package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconSighting
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconType
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RangeRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Reading
import kotlin.math.roundToInt

/** What the meter has heard of one format (or of any signal from the beacon). */
private data class LiveFrame(val type: BeaconType, val latest: Int, val readings: List<Reading>)

/**
 * Listens for [LISTEN_MS] and shows each format the beacon sends, side by side: its latest
 * signal, the 20 s trimmed mean the place uses ([BeaconRules.trimmedMean]), a rough distance when
 * the 1 m signal is known, and whether that counts as near. Handy for comparing a multi-format
 * beacon's slots, or against the maker's app.
 *
 * @param matchKey the key the place recognises the beacon by (marked "used").
 * @param address the beacon's address, so all its formats show; null = only [matchKey].
 */
@Composable
fun LiveSignalDialog(
    matchKey: String,
    address: String?,
    thresholdDbm: Int,
    oneMeterDbm: Int?,
    watch: suspend (durationMs: Long, onSightings: (List<BeaconSighting>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val frames = remember { mutableStateMapOf<String, LiveFrame>() }
    var listening by remember { mutableStateOf(true) }
    // Bumped by "Listen again" to restart the scan.
    var run by remember { mutableIntStateOf(0) }

    LaunchedEffect(run) {
        listening = true
        watch(LISTEN_MS) { sightings ->
            val nowMs = System.currentTimeMillis()
            sightings
                .filter { it.key == matchKey || (address != null && it.address == address) }
                .forEach { sighting ->
                    val previous = frames[sighting.key]?.readings.orEmpty()
                    val recent = (previous + Reading(nowMs, sighting.rssi)).filter { nowMs - it.timeMs < BeaconRules.WINDOW_MS }
                    frames[sighting.key] = LiveFrame(sighting.type, sighting.rssi, recent)
                }
        }
        listening = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.live_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (listening) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(if (listening) R.string.live_listening else R.string.live_stopped, LISTEN_MS / 1_000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (frames.isEmpty()) {
                    Text(stringResource(if (listening) R.string.live_waiting else R.string.live_nothing), style = MaterialTheme.typography.bodyMedium)
                }
                // Any signal first, then the formats in a fixed order.
                frames.entries.sortedBy { (_, frame) -> if (frame.type == BeaconType.DEVICE) -1 else frame.type.ordinal }
                    .forEach { (key, frame) -> LiveFrameRow(key, frame, used = key == matchKey, thresholdDbm, oneMeterDbm) }
                Text(stringResource(R.string.live_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    frames.clear()
                    run++
                },
                enabled = !listening,
            ) { Text(stringResource(R.string.live_again)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun LiveFrameRow(key: String, frame: LiveFrame, used: Boolean, thresholdDbm: Int, oneMeterDbm: Int?) {
    val mean = BeaconRules.trimmedMean(frame.readings.map { it.rssi })
    val near = mean != null && mean >= thresholdDbm
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            (if (frame.type == BeaconType.DEVICE) stringResource(R.string.live_any_signal) else stringResource(beaconTypeLabel(frame.type))) +
                if (used) "  " + stringResource(R.string.live_used) else "",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (used) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            listOfNotNull(
                stringResource(R.string.live_latest, frame.latest),
                mean?.let { stringResource(R.string.live_mean, it.roundToInt(), frame.readings.size) },
                if (mean != null && oneMeterDbm != null) "≈ " + RangeRules.formatBoth(RangeRules.estimateDistance(mean, oneMeterDbm)) else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(if (near) R.string.live_near else R.string.live_not_near, thresholdDbm),
            style = MaterialTheme.typography.bodySmall,
            color = if (near) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (used && mean != null && mean < thresholdDbm - BeaconRules.TOO_STRICT_MARGIN_DB) {
            Text(
                stringResource(R.string.live_too_strict, mean.roundToInt(), thresholdDbm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("ID ${BeaconRules.shortId(key)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How long one listen lasts. */
private const val LISTEN_MS = 60_000L
