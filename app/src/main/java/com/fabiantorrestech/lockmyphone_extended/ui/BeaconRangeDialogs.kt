package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconSighting

/**
 * Range (spec §3): the current "here within ≈ …" and every way to set it: measure where you sit,
 * measure at the edge, the room size, and (Pro, behind Advanced) a typed value. Then the guide.
 *
 * @param canMeasure false while Bluetooth is off or scanning isn't allowed.
 */
@Composable
fun BeaconRangeDialog(
    thresholdDbm: Int,
    oneMeterDbm: Int?,
    canMeasure: Boolean,
    onMeasure: (AreaMethod) -> Unit,
    onRoomSize: () -> Unit,
    onTypeValue: () -> Unit,
    onGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.range_row)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(rangeSummary(thresholdDbm, oneMeterDbm), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.range_set_by), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SettingsRow(Icons.Filled.EventSeat, stringResource(R.string.beacon_calibrate), stringResource(R.string.beacon_calibrate_hint), onClick = { onMeasure(AreaMethod.SEAT) }, enabled = canMeasure)
                SettingsRow(Icons.Filled.Fence, stringResource(R.string.beacon_calibrate_edge), stringResource(R.string.beacon_calibrate_edge_hint), onClick = { onMeasure(AreaMethod.EDGE) }, enabled = canMeasure)
                SettingsRow(Icons.Filled.SquareFoot, stringResource(R.string.range_title), null, onClick = onRoomSize)
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.setup_advanced_options)) }
                if (advanced) {
                    SettingsRow(Icons.Filled.Edit, stringResource(R.string.setup_area_value), stringResource(R.string.beacon_threshold_value, thresholdDbm), onClick = onTypeValue)
                }
                SettingsRow(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.near_info_title), null, onClick = onGuide)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Measure where you sit or at the edge (setup steps 4–5 on their own), then Use the result. */
@Composable
fun BeaconMeasureDialog(
    watch: suspend (Long, (List<BeaconSighting>) -> Unit) -> Unit,
    key: String,
    method: AreaMethod,
    oneMeterDbm: Int?,
    onUse: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var threshold by remember { mutableStateOf<Int?>(null) }
    var checking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (method == AreaMethod.EDGE) R.string.beacon_calibrate_edge else R.string.beacon_calibrate)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val found = threshold
                if (checking && found != null) {
                    CheckContent(watch = watch, key = key, thresholdDbm = found)
                } else {
                    MeasureContent(watch = watch, key = key, method = method, oneMeterDbm = oneMeterDbm, onResult = { threshold = it })
                }
            }
        },
        confirmButton = {
            val found = threshold
            if (checking && found != null) {
                TextButton(onClick = { onUse(found) }) { Text(stringResource(R.string.setup_done)) }
            } else {
                TextButton(onClick = { checking = true }, enabled = found != null) { Text(stringResource(R.string.setup_next)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** The red too-strict warning (spec §5), with a way to fix it. */
@Composable
fun TooStrictWarning(averageDbm: Int, thresholdDbm: Int, onMeasureAgain: () -> Unit) {
    Column {
        DangerBanner(stringResource(R.string.too_strict, averageDbm, thresholdDbm))
        TextButton(onClick = onMeasureAgain, modifier = SectionContentPadding) { Text(stringResource(R.string.setup_measure_again)) }
    }
}
