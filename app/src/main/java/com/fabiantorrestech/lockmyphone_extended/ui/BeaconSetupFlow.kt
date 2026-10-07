package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconGroup
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconSighting
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDevices
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RangeRules
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What is being set up: a beacon, or a paired device detected by being nearby. */
enum class SetupKind { BEACON, DEVICE }

/** The guided setup's five steps, in order. */
enum class SetupStep { PICK, NAME, AREA, MEASURE, CHECK }

/** How the range is set: measured at your seat or at the edge, from the room size, or typed (Pro). */
enum class AreaMethod { SEAT, EDGE, SIZE, VALUE }

/**
 * The guided setup's answers so far. Pure.
 *
 * @param key how the beacon is recognised (`device:<address>` = any signal, or one format's key).
 * @param thresholdDbm the range found by measuring, the room size or typing; null until then.
 */
data class BeaconSetupState(
    val step: SetupStep = SetupStep.PICK,
    val key: String? = null,
    val address: String? = null,
    val label: String? = null,
    val oneMeterDbm: Int? = null,
    val name: String = "",
    val method: AreaMethod = AreaMethod.SEAT,
    val thresholdDbm: Int? = null,
) {
    val canGoNext: Boolean
        get() = when (step) {
            SetupStep.PICK -> key != null
            SetupStep.NAME, SetupStep.AREA, SetupStep.CHECK -> true
            SetupStep.MEASURE -> thresholdDbm != null
        }

    fun next(): BeaconSetupState = copy(step = SetupStep.entries.getOrElse(step.ordinal + 1) { step })

    /** Back from Check skips Measure when the range came from the room size or a typed value. */
    fun back(): BeaconSetupState = when {
        step == SetupStep.CHECK && method in setOf(AreaMethod.SIZE, AreaMethod.VALUE) -> copy(step = SetupStep.AREA)
        else -> copy(step = SetupStep.entries.getOrElse(step.ordinal - 1) { step })
    }

    fun result(): BeaconSetupResult? {
        val chosenKey = key ?: return null
        val threshold = thresholdDbm ?: return null
        return BeaconSetupResult(chosenKey, address, label, oneMeterDbm, name.trim(), threshold)
    }
}

/** What the setup saves: only produced by Done, so Cancel changes nothing. */
data class BeaconSetupResult(
    val key: String,
    val address: String?,
    val label: String?,
    val oneMeterDbm: Int?,
    val name: String,
    val thresholdDbm: Int,
)

private const val MEASURE_MS = 20_000L
private const val CHECK_MS = 10_000L

/**
 * The guided beacon setup (spec §2): pick → name → what counts as here → measure → check.
 * Full screen over the place editor. Only Done reports a result; Cancel leaves everything as it was.
 */
@Composable
fun BeaconSetupFlow(
    kind: SetupKind,
    initial: BeaconSetupState,
    beaconTools: BeaconTools,
    pairedDeviceTools: PairedDeviceTools,
    onDone: (BeaconSetupResult) -> Unit,
    onCancel: () -> Unit,
) {
    var state by remember { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<BeaconGroup>?>(null) }
    var scanRun by remember { mutableIntStateOf(0) }
    var pickedGroup by remember { mutableStateOf<BeaconGroup?>(null) }
    val devices = remember { if (kind == SetupKind.DEVICE) pairedDeviceTools.list() else emptyList() }
    var seen by remember { mutableStateOf<Map<String, Int>?>(null) }
    var sizeOpen by remember { mutableStateOf(false) }
    var valueOpen by remember { mutableStateOf(false) }

    LaunchedEffect(scanRun) {
        if (kind == SetupKind.BEACON) {
            groups = null
            groups = beaconTools.discover()
        } else {
            seen = pairedDeviceTools.seenNearby?.invoke()
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setup_step_of, state.step.ordinal + 1, SetupStep.entries.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(stepTitle(state.step)), style = MaterialTheme.typography.headlineSmall)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (state.step) {
                        SetupStep.PICK -> if (kind == SetupKind.BEACON) {
                            val list = groups
                            if (list == null) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.beacon_scanning))
                            } else {
                                if (list.isEmpty()) Text(stringResource(R.string.beacon_none_found))
                                BeaconGroupList(
                                    groups = list,
                                    nameOf = { beaconTools.nameOf(it.frames.firstOrNull()?.key, it.address) },
                                    selectedAddress = state.address,
                                    onPick = { group ->
                                        pickedGroup = group
                                        val key = if (group.addressRotates) group.frames.firstOrNull()?.key ?: group.deviceKey else group.deviceKey
                                        state = state.copy(
                                            key = key,
                                            address = group.address,
                                            label = group.name ?: group.frames.firstOrNull()?.label,
                                            oneMeterDbm = group.oneMeterPower ?: state.oneMeterDbm.takeIf { state.address == group.address },
                                            name = beaconTools.nameOf(key, group.address) ?: state.name,
                                        )
                                    },
                                )
                                TextButton(onClick = { scanRun++ }) { Text(stringResource(R.string.setup_rescan)) }
                                Text(stringResource(R.string.beacon_pick_hint), style = MaterialTheme.typography.bodySmall)
                                pickedGroup?.let { group ->
                                    TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.setup_advanced_recognise)) }
                                    if (advanced) {
                                        MatchByOptions(group, state.key) { key, power ->
                                            state = state.copy(key = key, oneMeterDbm = power ?: state.oneMeterDbm)
                                        }
                                    }
                                }
                            }
                        } else {
                            PairedDeviceList(
                                devices = devices,
                                seen = seen,
                                scanning = pairedDeviceTools.seenNearby != null && seen == null,
                                selectedAddress = state.address,
                                onPick = { device ->
                                    val key = BeaconRules.deviceKey(device.address)
                                    state = state.copy(
                                        key = key,
                                        address = device.address,
                                        label = device.name,
                                        name = beaconTools.nameOf(key, PairedDevices.normalize(device.address)) ?: state.name,
                                    )
                                },
                            )
                        }
                        SetupStep.NAME -> {
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = { state = state.copy(name = it.take(40)) },
                                label = { Text(stringResource(R.string.setup_name_label)) },
                                placeholder = { Text(state.label.orEmpty()) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(stringResource(R.string.setup_name_hint), style = MaterialTheme.typography.bodySmall)
                        }
                        SetupStep.AREA -> {
                            AreaOption(AreaMethod.SEAT, state.method) { state = state.copy(method = it) }
                            AreaOption(AreaMethod.EDGE, state.method) { state = state.copy(method = it) }
                            AreaOption(AreaMethod.SIZE, state.method) { state = state.copy(method = it) }
                            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.setup_advanced_options)) }
                            if (advanced || state.method == AreaMethod.VALUE) {
                                AreaOption(AreaMethod.VALUE, state.method) { state = state.copy(method = it) }
                            }
                        }
                        SetupStep.MEASURE -> MeasureContent(
                            watch = beaconTools.watch,
                            key = state.key.orEmpty(),
                            method = state.method,
                            oneMeterDbm = state.oneMeterDbm,
                            onResult = { state = state.copy(thresholdDbm = it) },
                        )
                        SetupStep.CHECK -> state.thresholdDbm?.let { threshold ->
                            CheckContent(watch = beaconTools.watch, key = state.key.orEmpty(), thresholdDbm = threshold)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { state = state.back() }, enabled = state.step != SetupStep.PICK) {
                        Text(stringResource(R.string.setup_back))
                    }
                    Button(
                        onClick = {
                            when {
                                state.step == SetupStep.AREA && state.method == AreaMethod.SIZE -> sizeOpen = true
                                state.step == SetupStep.AREA && state.method == AreaMethod.VALUE -> valueOpen = true
                                state.step == SetupStep.CHECK -> state.result()?.let(onDone)
                                else -> state = state.next()
                            }
                        },
                        enabled = state.canGoNext,
                    ) {
                        Text(stringResource(if (state.step == SetupStep.CHECK) R.string.setup_done else R.string.setup_next))
                    }
                }
            }
        }
    }

    if (sizeOpen) {
        val key = state.key.orEmpty()
        RangeCalculatorDialog(
            savedOneMeter = state.oneMeterDbm,
            readAdvertised = { if (kind == SetupKind.BEACON && beaconTools.hasPermission && beaconTools.bluetoothOn) beaconTools.advertisedPower(key) else null },
            measureOneMeter = { beaconTools.sample(key) },
            onUse = { value, oneMeter ->
                state = state.copy(thresholdDbm = -value, oneMeterDbm = oneMeter ?: state.oneMeterDbm, step = SetupStep.CHECK)
                sizeOpen = false
            },
            onDismiss = { sizeOpen = false },
        )
    }
    if (valueOpen) {
        NumberDialog(
            title = stringResource(R.string.beacon_threshold),
            label = stringResource(R.string.beacon_threshold_label),
            initial = -(state.thresholdDbm ?: -75),
            range = 30..100,
            hint = stringResource(R.string.beacon_threshold_hint),
            onConfirm = {
                state = state.copy(thresholdDbm = -it, step = SetupStep.CHECK)
                valueOpen = false
            },
            onDismiss = { valueOpen = false },
        )
    }
}

private fun stepTitle(step: SetupStep): Int = when (step) {
    SetupStep.PICK -> R.string.setup_step_pick
    SetupStep.NAME -> R.string.setup_step_name
    SetupStep.AREA -> R.string.setup_step_area
    SetupStep.MEASURE -> R.string.setup_step_measure
    SetupStep.CHECK -> R.string.setup_step_check
}

@Composable
private fun AreaOption(method: AreaMethod, selected: AreaMethod, onSelect: (AreaMethod) -> Unit) {
    val (title, desc) = when (method) {
        AreaMethod.SEAT -> R.string.setup_area_seat to R.string.setup_area_seat_desc
        AreaMethod.EDGE -> R.string.setup_area_edge to R.string.setup_area_edge_desc
        AreaMethod.SIZE -> R.string.setup_area_size to R.string.setup_area_size_desc
        AreaMethod.VALUE -> R.string.setup_area_value to R.string.setup_area_value_desc
    }
    RadioSettingsRow(
        title = stringResource(title),
        summary = stringResource(desc),
        selected = method == selected,
        onSelect = { onSelect(method) },
    )
}

/**
 * Listens for [MEASURE_MS], showing readings live, then works out the range: at your seat a little
 * below the typical signal, at the edge just below the weakest. Reports null until done (or when
 * nothing was heard).
 */
@Composable
fun MeasureContent(
    watch: suspend (Long, (List<BeaconSighting>) -> Unit) -> Unit,
    key: String,
    method: AreaMethod,
    oneMeterDbm: Int?,
    onResult: (Int?) -> Unit,
) {
    var run by remember { mutableIntStateOf(0) }
    var readings by remember { mutableStateOf(listOf<Int>()) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var threshold by remember { mutableStateOf<Int?>(null) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(run) {
        readings = emptyList()
        threshold = null
        done = false
        onResult(null)
        val start = System.currentTimeMillis()
        coroutineScope {
            val ticker = launch {
                while (true) {
                    elapsedMs = System.currentTimeMillis() - start
                    delay(250)
                }
            }
            watch(MEASURE_MS) { sightings -> sightings.filter { it.key == key }.forEach { readings = readings + it.rssi } }
            ticker.cancel()
        }
        elapsedMs = MEASURE_MS
        val found = if (method == AreaMethod.EDGE) BeaconRules.edgeThreshold(readings) else BeaconRules.calibratedThreshold(readings)
        threshold = found
        done = true
        onResult(found)
    }

    Text(
        stringResource(if (method == AreaMethod.EDGE) R.string.setup_measure_edge else R.string.setup_measure_seat),
        style = MaterialTheme.typography.bodyLarge,
    )
    LinearProgressIndicator(progress = { (elapsedMs.toFloat() / MEASURE_MS).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    val mean = BeaconRules.trimmedMean(readings)
    Text(
        if (readings.isEmpty()) {
            stringResource(R.string.setup_measure_waiting)
        } else {
            stringResource(R.string.setup_measure_now, readings.last(), mean?.roundToInt() ?: 0, readings.size)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (done) {
        val found = threshold
        if (found == null) {
            Text(stringResource(R.string.setup_measure_not_heard), color = MaterialTheme.colorScheme.error)
        } else {
            Text(rangeSummary(found, oneMeterDbm), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { run++ }) { Text(stringResource(R.string.setup_measure_again)) }
    }
}

/** About [CHECK_MS] of live signal against [thresholdDbm]: would you count as here right now? */
@Composable
fun CheckContent(
    watch: suspend (Long, (List<BeaconSighting>) -> Unit) -> Unit,
    key: String,
    thresholdDbm: Int,
) {
    var run by remember { mutableIntStateOf(0) }
    var readings by remember { mutableStateOf(listOf<Int>()) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(run) {
        readings = emptyList()
        done = false
        watch(CHECK_MS) { sightings -> sightings.filter { it.key == key }.forEach { readings = readings + it.rssi } }
        done = true
    }

    Text(stringResource(R.string.setup_check_instruction), style = MaterialTheme.typography.bodyLarge)
    if (!done) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    val mean = BeaconRules.trimmedMean(readings)?.roundToInt()
    when {
        mean == null && done -> Text(stringResource(R.string.setup_check_not_heard), color = MaterialTheme.colorScheme.error)
        mean == null -> Text(stringResource(R.string.setup_measure_waiting))
        mean >= thresholdDbm -> Text(
            stringResource(R.string.setup_check_ok, mean, thresholdDbm),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> Text(
            stringResource(R.string.setup_check_not_yet, mean, thresholdDbm),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (done) TextButton(onClick = { run++ }) { Text(stringResource(R.string.setup_check_again)) }
}

/** "Here within ≈ 1.5 m (5 ft 0 in) (-78 dBm)"; just "-78 dBm" when no 1 m signal is known. */
@Composable
fun rangeSummary(thresholdDbm: Int, oneMeterDbm: Int?): String =
    if (oneMeterDbm == null) {
        stringResource(R.string.range_summary_dbm, thresholdDbm)
    } else {
        stringResource(
            R.string.range_summary,
            RangeRules.formatBoth(RangeRules.estimateDistance(thresholdDbm.toDouble(), oneMeterDbm)),
            thresholdDbm,
        )
    }
