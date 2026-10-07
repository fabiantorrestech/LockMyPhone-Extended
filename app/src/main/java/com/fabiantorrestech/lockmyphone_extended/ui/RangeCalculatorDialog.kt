package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconSpot
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RangeRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RangeSuggestion
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Walls
import kotlinx.coroutines.launch
import java.util.Locale

/** How the space is described. */
private enum class SpaceInput { ROOM, AREA, VOLUME }

/**
 * 📐 next to "Near means": the size of the space to cover (metric or imperial) → a suggested
 * value, from [RangeRules]. The beacon's 1 m signal is read from the beacon when it says it,
 * measured on request, or the typical −59 dBm.
 *
 * @param savedOneMeter the place's saved 1 m signal (read or measured earlier), if any.
 * @param readAdvertised an ~8 s scan for the 1 m signal the beacon broadcasts, or null.
 * @param measureOneMeter ~15 s of readings with the phone 1 m from the beacon.
 * @param onUse the value to type (30–100), and the 1 m signal when it's the beacon's own
 *   (announced, measured or saved), to save with the place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RangeCalculatorDialog(
    savedOneMeter: Int?,
    readAdvertised: suspend () -> Int?,
    measureOneMeter: suspend () -> List<Int>,
    onUse: (value: Int, oneMeterDbm: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var imperial by remember { mutableStateOf(Locale.getDefault().country in IMPERIAL_COUNTRIES) }
    var input by remember { mutableStateOf(SpaceInput.ROOM) }
    var lengthMajor by remember { mutableStateOf("") }
    var lengthMinor by remember { mutableStateOf("") }
    var widthMajor by remember { mutableStateOf("") }
    var widthMinor by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var ceilingMajor by remember { mutableStateOf("") }
    var ceilingMinor by remember { mutableStateOf("") }
    var gapMajor by remember { mutableStateOf("") }
    var gapMinor by remember { mutableStateOf("") }
    var spot by remember { mutableStateOf(BeaconSpot.CENTER) }
    var walls by remember { mutableStateOf(Walls.SOME) }
    // The 1 m signal and where it came from.
    var oneMeter by remember { mutableStateOf(savedOneMeter) }
    var oneMeterSource by remember { mutableStateOf(if (savedOneMeter != null) OneMeterSource.SAVED else OneMeterSource.READING) }
    // The last ask (on open, or ↻) didn't hear the beacon's 1 m signal.
    var notHeard by remember { mutableStateOf(false) }

    // Asks the beacon for its 1 m signal. If it doesn't answer, the last value (and where it came from) stays.
    suspend fun askBeacon() {
        val previousSource = oneMeterSource.takeUnless { it == OneMeterSource.READING } ?: OneMeterSource.TYPICAL
        oneMeterSource = OneMeterSource.READING
        val advertised = readAdvertised()
        // "Measure at 1 m" started meanwhile: that wins.
        if (oneMeterSource != OneMeterSource.READING) return
        notHeard = advertised == null
        if (advertised != null) {
            oneMeter = advertised
            oneMeterSource = OneMeterSource.BEACON
        } else {
            oneMeterSource = if (oneMeter != null) previousSource else OneMeterSource.TYPICAL
        }
    }

    // A saved value is kept until ↻ asks the beacon again.
    LaunchedEffect(Unit) { if (savedOneMeter == null) askBeacon() }

    fun meters(major: String, minor: String): Double? {
        if (major.isBlank() && minor.isBlank()) return null
        val whole = major.toIntOrNull() ?: 0
        val part = minor.toIntOrNull() ?: 0
        return if (imperial) RangeRules.metersOfFeet(whole, part.coerceIn(0, 11)) else RangeRules.metersOf(whole, part.coerceIn(0, 99))
    }

    val farthest: Double? = run {
        val flat = when (input) {
            SpaceInput.ROOM -> {
                val length = meters(lengthMajor, lengthMinor) ?: return@run null
                val width = meters(widthMajor, widthMinor) ?: return@run null
                if (length <= 0 || width <= 0) return@run null
                RangeRules.farthestInRoom(length, width, spot)
            }
            SpaceInput.AREA -> {
                val value = amount.toDoubleOrNull()?.takeIf { it > 0 } ?: return@run null
                RangeRules.farthestInArea(if (imperial) RangeRules.squareMetersOfSquareFeet(value) else value, spot)
            }
            SpaceInput.VOLUME -> {
                val value = amount.toDoubleOrNull()?.takeIf { it > 0 } ?: return@run null
                val ceiling = meters(ceilingMajor, ceilingMinor)?.takeIf { it > 0 } ?: return@run null
                val cubicMeters = if (imperial) RangeRules.cubicMetersOfCubicFeet(value) else value
                RangeRules.farthestInArea(cubicMeters / ceiling, spot)
            }
        }
        RangeRules.withHeightGap(flat, meters(gapMajor, gapMinor) ?: 0.0)
    }
    val suggestion: RangeSuggestion? = farthest?.let {
        RangeRules.suggest(oneMeter ?: RangeRules.DEFAULT_ONE_METER_DBM, walls, it)
    }
    val (major, minor) = if (imperial) "ft" to "in" else "m" to "cm"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.range_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Chips(
                    options = listOf(false, true),
                    selected = imperial,
                    label = { stringResource(if (it) R.string.range_imperial else R.string.range_metric) },
                    onSelect = { imperial = it },
                )
                Chips(
                    options = SpaceInput.entries,
                    selected = input,
                    label = {
                        stringResource(
                            when (it) {
                                SpaceInput.ROOM -> R.string.range_input_room
                                SpaceInput.AREA -> R.string.range_input_area
                                SpaceInput.VOLUME -> R.string.range_input_volume
                            }
                        )
                    },
                    onSelect = { input = it },
                )
                when (input) {
                    SpaceInput.ROOM -> {
                        TwoUnitField(stringResource(R.string.range_length), lengthMajor, lengthMinor, major, minor, { lengthMajor = it }, { lengthMinor = it })
                        TwoUnitField(stringResource(R.string.range_width), widthMajor, widthMinor, major, minor, { widthMajor = it }, { widthMinor = it })
                    }
                    SpaceInput.AREA -> NumberField(
                        label = stringResource(R.string.range_area, if (imperial) "ft²" else "m²"),
                        value = amount,
                        decimal = true,
                        onChange = { amount = it },
                    )
                    SpaceInput.VOLUME -> {
                        NumberField(
                            label = stringResource(R.string.range_volume, if (imperial) "ft³" else "m³"),
                            value = amount,
                            decimal = true,
                            onChange = { amount = it },
                        )
                        TwoUnitField(stringResource(R.string.range_ceiling), ceilingMajor, ceilingMinor, major, minor, { ceilingMajor = it }, { ceilingMinor = it })
                    }
                }
                TwoUnitField(stringResource(R.string.range_height_gap), gapMajor, gapMinor, major, minor, { gapMajor = it }, { gapMinor = it })
                Text(stringResource(R.string.range_spot), style = MaterialTheme.typography.titleSmall)
                Chips(
                    options = BeaconSpot.entries,
                    selected = spot,
                    label = {
                        stringResource(
                            when (it) {
                                BeaconSpot.CENTER -> R.string.range_spot_center
                                BeaconSpot.WALL -> R.string.range_spot_wall
                                BeaconSpot.CORNER -> R.string.range_spot_corner
                            }
                        )
                    },
                    onSelect = { spot = it },
                )
                Text(stringResource(R.string.range_walls), style = MaterialTheme.typography.titleSmall)
                Chips(
                    options = Walls.entries,
                    selected = walls,
                    label = {
                        stringResource(
                            when (it) {
                                Walls.OPEN -> R.string.range_walls_open
                                Walls.SOME -> R.string.range_walls_some
                                Walls.MANY -> R.string.range_walls_many
                            }
                        )
                    },
                    onSelect = { walls = it },
                )
                Text(stringResource(R.string.range_one_meter), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val value = oneMeter ?: RangeRules.DEFAULT_ONE_METER_DBM
                    Text(
                        when (oneMeterSource) {
                            OneMeterSource.READING -> stringResource(R.string.range_one_meter_reading)
                            OneMeterSource.MEASURING -> stringResource(R.string.range_one_meter_measuring)
                            OneMeterSource.BEACON -> stringResource(R.string.range_one_meter_beacon, value)
                            OneMeterSource.MEASURED -> stringResource(R.string.range_one_meter_measured, value)
                            OneMeterSource.SAVED -> stringResource(R.string.range_one_meter_saved, value)
                            OneMeterSource.TYPICAL -> stringResource(R.string.range_one_meter_typical, value)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (oneMeterSource == OneMeterSource.READING || oneMeterSource == OneMeterSource.MEASURING) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { scope.launch { askBeacon() } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.range_one_meter_refresh))
                        }
                    }
                }
                if (notHeard && oneMeterSource != OneMeterSource.READING) {
                    Text(
                        stringResource(R.string.range_one_meter_not_heard),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        val before = oneMeterSource
                        oneMeterSource = OneMeterSource.MEASURING
                        scope.launch {
                            val readings = measureOneMeter()
                            val median = readings.sorted().getOrNull(readings.size / 2)
                            if (median != null) oneMeter = median
                            notHeard = median == null
                            // Not heard: keep the last value and where it came from.
                            oneMeterSource = when {
                                median != null -> OneMeterSource.MEASURED
                                oneMeter != null && before != OneMeterSource.READING -> before
                                else -> OneMeterSource.TYPICAL
                            }
                        }
                    },
                    enabled = oneMeterSource != OneMeterSource.MEASURING,
                ) { Text(stringResource(R.string.range_measure_one_meter)) }

                if (suggestion != null) {
                    Text(
                        stringResource(
                            R.string.range_result,
                            RangeRules.formatBoth(suggestion.farthestMeters),
                            suggestion.expectedRssi,
                            suggestion.typedValue,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (suggestion.clamped) {
                        Text(
                            stringResource(if (suggestion.typedValue == RangeRules.TYPED_RANGE.last) R.string.range_too_big else R.string.range_too_small),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(stringResource(R.string.range_enter_size), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    stringResource(R.string.range_caveat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    suggestion?.let {
                        val own = oneMeter.takeIf { oneMeterSource != OneMeterSource.TYPICAL }
                        onUse(it.typedValue, own)
                    }
                },
                enabled = suggestion != null,
            ) {
                Text(suggestion?.let { stringResource(R.string.range_use, it.typedValue) } ?: stringResource(R.string.range_use_none))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Where the 1 m signal shown came from. */
private enum class OneMeterSource { READING, MEASURING, BEACON, MEASURED, SAVED, TYPICAL }

/** Countries that measure rooms in feet; everyone else starts in metric (either can be picked). */
private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

/** A row of chips where one is picked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** A length as whole units plus a smaller unit: m + cm, or ft + in. */
@Composable
private fun TwoUnitField(
    label: String,
    major: String,
    minor: String,
    majorUnit: String,
    minorUnit: String,
    onMajor: (String) -> Unit,
    onMinor: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(majorUnit, major, decimal = false, onChange = onMajor, modifier = Modifier.width(96.dp))
            NumberField(minorUnit, minor, decimal = false, onChange = onMinor, modifier = Modifier.width(96.dp))
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, decimal: Boolean, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || (decimal && it == '.') }.take(MAX_DIGITS)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

private const val MAX_DIGITS = 7
