# Beacon setup redesign (M21) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the eight-row beacon section with a guided 5-step setup, a 3-row summary (Beacon / Range / Timing + Check signal now), plain names, a too-strict warning, and a sanity check on announced 1 m signals.

**Architecture:**
- The logic stays where it is; this is mostly UI restructuring.
- New pure pieces: `BeaconRules.plausibleOneMeter`, `BeaconRules.rangeTooStrict`, `BeaconStatus` / `BeaconTracker.status`, and the `BeaconSetupState` flow state.
- New UI:
  - `BeaconSetupFlow.kt`: a full-screen 5-step flow.
  - `BeaconRangeDialogs.kt`: the Range screen and the measure dialog.
- Today's picker list, Match by options and paired-device list are extracted as reusable content. The room-size calculator, number field, ⓘ guide, live meter and "Listen harder" dialog are reused as they are.
- `LocationEditorScreen` shrinks to the summary rows.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Android BLE scanning through the existing `BeaconScanner`. No new dependencies, no DB change (stays at v23).

**Spec:** `doc/plans/2026-10-02-beacon-setup-redesign.md`

## Global Constraints

- No new dependencies. No Room schema change (DB stays at version 23).
- **No automated tests** (project CLAUDE.md: "No tests unless asked"). Each task's verification is `assembleDebug` succeeding, plus the device checks in Task 6.
- **Don't commit** (the owner commits). Stage new source files with `git add`. Never commit `handoffContext/`.
- Build: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug -q 2>&1 | grep -E "^e:|error|FAIL"`. No output means success.
- **String resources:** escape apostrophes and quotes as `\'` and `\"`. Shell heredocs have dropped these escapes before, so add strings with exact edits.
- A plausible announced 1 m signal: **−30 to −100 dBm**. Too-strict warning: heard within **30 min**, not near, 20 s average more than **5 dB** weaker than the range.
- Setup measuring: **20 s** (seat → `BeaconRules.calibratedThreshold`, area → `BeaconRules.edgeThreshold`). Check: **10 s**.
- **Simple vs Pro:** Pro items ("Recognise it by", "Type a value (dBm)", Advanced timing, "Get ready early") sit behind "Advanced" toggles.
- **Names** (spec §4): Beacon, Recognise it by, Range ("Here within ≈ …"), Measure where I sit, Measure at the edge of the area, Enter the room size, What the numbers mean, Timing, Get ready early, Check signal now.

## Review Focus

1. **Cancel mid-setup** must leave the place's beacon fields exactly as they were (no partial writes). `BeaconSetupFlow` only calls `onDone` from the final step (Task 2).
2. **A beacon with no plausible 1 m signal** (including the owner's saved −3): Range shows the dBm with no "≈ m", and estimates use −59. Covered by `knownOneMeterDbm` (Task 1) and `rangeSummary` (Task 3).
3. **Nothing heard while measuring:** the result says "not heard" and Next stays disabled; it must never save a null or extreme threshold. Thresholds are clamped to −100…−30 (Task 2).
4. **Bluetooth off or scan permission missing:** the setup's Pick step and the measure options must not start scans. The Beacon / Check rows stay disabled as today (Task 4).
5. **Paired device in "connected" mode:** no Range / Timing / Check rows and no setup flow; Change opens the plain device picker as today (Task 4).

---

### Task 1: Pure rules, beacon status, 1 m sanity check

**Files:**
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/trigger/location/BeaconRules.kt`
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/trigger/location/PresenceSignals.kt`
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/trigger/location/LocationTriggerManager.kt`
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/LocationEditorScreen.kt` (`BeaconTools` only)
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/MainActivity.kt` (BeaconTools wiring)
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/trigger/AutomationTester.kt`

**Interfaces:**
- Produces:
  - `data class BeaconStatus(val lastHeardMs: Long, val averageDbm: Double?, val near: Boolean)` (BeaconRules.kt)
  - `BeaconRules.PLAUSIBLE_ONE_METER_DBM: IntRange`
  - `BeaconRules.plausibleOneMeter(dbm: Int?): Int?`
  - `BeaconRules.TOO_STRICT_MARGIN_DB: Int` (= 5)
  - `BeaconRules.rangeTooStrict(status: BeaconStatus?, thresholdDbm: Int, nowMs: Long): Boolean`
  - `BeaconTracker.status(place: LocationTrigger, device: Boolean): BeaconStatus?`
  - `LocationTriggerManager.beaconStatus(place: LocationTrigger, device: Boolean): BeaconStatus?`
  - `val LocationTrigger.knownOneMeterDbm: Int?` (PresenceSignals.kt)
  - `BeaconTools.status: (place: LocationTrigger, device: Boolean) -> BeaconStatus?`

- [ ] **Step 1: Add `BeaconStatus` and the rules to `BeaconRules.kt`**

Below `data class Reading(...)`, add:

```kotlin
/**
 * What the background check knows about a place's beacon (or nearby device) right now.
 *
 * @param averageDbm the 20 s trimmed mean, or the last one when nothing was heard lately.
 */
data class BeaconStatus(val lastHeardMs: Long, val averageDbm: Double?, val near: Boolean)
```

Inside `object BeaconRules`, after `CALIBRATION_MARGIN_DB`, add:

```kotlin
    /**
     * A beacon's announced signal at 1 m is only believable in this range. Some beacons put their
     * transmit-power setting there instead (e.g. -3 dBm), which would wreck distance estimates.
     */
    val PLAUSIBLE_ONE_METER_DBM = -100..-30

    fun plausibleOneMeter(dbm: Int?): Int? = dbm?.takeIf { it in PLAUSIBLE_ONE_METER_DBM }

    /** The too-strict warning only uses hearings this recent. */
    const val TOO_STRICT_RECENT_MS = 30L * 60 * 1_000

    /** ...and only when the average is more than this much weaker than the range. */
    const val TOO_STRICT_MARGIN_DB = 5

    /**
     * The range is stricter than what the phone actually hears: the beacon was heard lately, you
     * don't count as here, and its average is clearly weaker than the range. You may never
     * count as here.
     */
    fun rangeTooStrict(status: BeaconStatus?, thresholdDbm: Int, nowMs: Long): Boolean {
        if (status == null || status.near) return false
        val average = status.averageDbm ?: return false
        val heardLately = status.lastHeardMs > 0 && nowMs - status.lastHeardMs < TOO_STRICT_RECENT_MS
        return heardLately && average < thresholdDbm - TOO_STRICT_MARGIN_DB
    }
```

Replace the three announced-power readers so implausible values become null:

```kotlin
    /** The iBeacon's "measured power": its signal 1 m away (byte 22, signed). Null if missing or implausible. */
    fun iBeaconMeasuredPower(manufacturerData: ByteArray): Int? =
        plausibleOneMeter(manufacturerData.getOrNull(22)?.toInt()?.takeIf { it != 0 })

    /** An Eddystone-UID's signal 1 m away: its TX power at 0 m (byte 1, signed) minus 41 dB. Null if implausible. */
    fun eddystoneOneMeterPower(serviceData: ByteArray): Int? =
        plausibleOneMeter(serviceData.getOrNull(1)?.toInt()?.takeIf { it != 0 }?.let { it - EDDYSTONE_0M_TO_1M_DB })
```

```kotlin
    fun altBeaconOneMeterPower(manufacturerData: ByteArray): Int? =
        plausibleOneMeter(manufacturerData.getOrNull(22)?.toInt()?.takeIf { it != 0 })
```

Also clamp the seat calibration like the edge one:

```kotlin
    /** A threshold from calibration readings taken at your spot. */
    fun calibratedThreshold(readings: List<Int>): Int? {
        if (readings.isEmpty()) return null
        val sorted = readings.sorted()
        return (sorted[sorted.size / 2] - CALIBRATION_MARGIN_DB).coerceIn(-100, -30)
    }
```

- [ ] **Step 2: Expose a track's status (`PresenceSignals.kt`)**

In `class BeaconTracker`, after `fun devicePresence(...)`, add:

```kotlin
    /** The latest state of [place]'s beacon (or, with [device], its nearby paired device); null if never heard. */
    fun status(place: LocationTrigger, device: Boolean): BeaconStatus? {
        val track = load(if (device) deviceTrackKey(place.id) else beaconTrackKey(place.id))
        if (track.lastSeenMs == 0L) return null
        return BeaconStatus(track.lastSeenMs, track.smoothedRssi, track.near)
    }
```

Near the other `LocationTrigger` extensions (above `fun LocationTrigger.beaconTiming`), add:

```kotlin
/** [this] place's beacon 1 m signal, only when plausible (a saved -3 counts as unknown). */
val LocationTrigger.knownOneMeterDbm: Int?
    get() = BeaconRules.plausibleOneMeter(beaconOneMeterDbm)
```

- [ ] **Step 3: Manager accessor (`LocationTriggerManager.kt`)**

Above `/** Whether you're at the place [placeId] as last worked out`, add:

```kotlin
    /** What the background check knows about [place]'s beacon (or nearby device): for the editor's warning. */
    fun beaconStatus(place: LocationTrigger, device: Boolean): BeaconStatus? = beaconTracker.status(place, device)
```

- [ ] **Step 4: `BeaconTools.status` + wiring**

In `LocationEditorScreen.kt` `class BeaconTools(...)`, after `val defaultTiming: BeaconTiming = BeaconTiming.BALANCED,`, add:

```kotlin
    /** The background check's view of a place's beacon (device = its nearby paired device), for the too-strict warning. */
    val status: (place: LocationTrigger, device: Boolean) -> BeaconStatus? = { _, _ -> null },
```

Add the import `com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconStatus`.

In `MainActivity.kt`, in the `BeaconTools(` call after `defaultTiming = …,`, add:

```kotlin
                        status = { place, device -> locationManager.beaconStatus(place, device) },
```

- [ ] **Step 5: Use only plausible saved values**

In `AutomationTester.kt`, change `PlaceSignal.BEACON -> rssiDetail(heard, draft.beaconKey, draft.beaconRssiThreshold, draft.beaconOneMeterDbm)` to `… , draft.knownOneMeterDbm)`, and add the import `com.fabiantorrestech.lockmyphone_extended.trigger.location.knownOneMeterDbm`.

In `LocationEditorScreen.kt`, replace the three **reads** of `draft.beaconOneMeterDbm` (the NEAR_INFO `oneMeterDbm =`, the BEACON_LIVE `oneMeterDbm =`, and both RangeCalculatorDialog `savedOneMeter =`) with `draft.knownOneMeterDbm`. Add the import `com.fabiantorrestech.lockmyphone_extended.trigger.location.knownOneMeterDbm`. Writes stay as they are; Task 4 rewrites this area anyway.

- [ ] **Step 6: Build**

Run the build command from Global Constraints. Expected: no output.

---

### Task 2: Reusable picker content + the guided setup flow

**Files:**
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/BeaconPickerDialogs.kt` (extract `BeaconGroupList`, `MatchByOptions`, `PairedDeviceList`)
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/LocationEditorScreen.kt` (move `pairedDeviceIcon` / `pairedDeviceKindLabel` out; `PairedDevicePickerDialog` uses `PairedDeviceList`)
- Create: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/BeaconSetupFlow.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes (Task 1): `BeaconRules.calibratedThreshold`, `BeaconRules.edgeThreshold`, `BeaconRules.trimmedMean`, `RangeRules.estimateDistance`, `RangeRules.formatBoth`, `RangeRules.DEFAULT_ONE_METER_DBM`, `BeaconTools`, `PairedDeviceTools`, `RangeCalculatorDialog(savedOneMeter, readAdvertised, measureOneMeter, onUse: (Int, Int?) -> Unit, onDismiss)`, `NumberDialog(title, label, initial, range, hint, onConfirm, onDismiss)`.
- Produces:
  - `@Composable fun BeaconGroupList(groups: List<BeaconGroup>, nameOf: (BeaconGroup) -> String?, selectedAddress: String?, onPick: (BeaconGroup) -> Unit)`
  - `@Composable fun MatchByOptions(group: BeaconGroup, selected: String?, onSelect: (key: String, oneMeterPower: Int?) -> Unit)`
  - `@Composable fun PairedDeviceList(devices: List<PairedDevice>, seen: Map<String, Int>?, scanning: Boolean, selectedAddress: String?, onPick: (PairedDevice) -> Unit)`
  - `fun pairedDeviceIcon(kind: PairedDeviceKind): ImageVector` and `fun pairedDeviceKindLabel(kind: PairedDeviceKind): Int` (now public, in BeaconPickerDialogs.kt)
  - `enum class SetupKind { BEACON, DEVICE }`, `enum class SetupStep { PICK, NAME, AREA, MEASURE, CHECK }`, `enum class AreaMethod { SEAT, EDGE, SIZE, VALUE }`
  - `data class BeaconSetupState(...)` (fields below), `data class BeaconSetupResult(val key: String, val address: String?, val label: String?, val oneMeterDbm: Int?, val name: String, val thresholdDbm: Int)`
  - `@Composable fun BeaconSetupFlow(kind: SetupKind, initial: BeaconSetupState, beaconTools: BeaconTools, pairedDeviceTools: PairedDeviceTools, onDone: (BeaconSetupResult) -> Unit, onCancel: () -> Unit)`
  - `@Composable fun MeasureContent(watch: suspend (Long, (List<BeaconSighting>) -> Unit) -> Unit, key: String, method: AreaMethod, oneMeterDbm: Int?, onResult: (Int?) -> Unit)`
  - `@Composable fun CheckContent(watch: suspend (Long, (List<BeaconSighting>) -> Unit) -> Unit, key: String, thresholdDbm: Int)`

- [ ] **Step 1: Extract `BeaconGroupList` and `MatchByOptions` in `BeaconPickerDialogs.kt`**

Replace the `else -> list.forEach { group -> … }` block inside `BeaconGroupPickerDialog` with:

```kotlin
                    else -> BeaconGroupList(list, nameOf, selectedAddress = null, onPick = onPick, onRename = onRename)
```

Add, below `BeaconGroupPickerDialog`:

```kotlin
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
```

Replace the body of `MatchByDialog`'s `text = { Column { … } }` with a call to a new extracted composable, and add that composable:

```kotlin
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MatchByOptions(group, selected) { key, _ -> selected = key }
            }
        },
```

```kotlin
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
```

Add the imports `androidx.compose.material.icons.filled.CheckCircle`, `androidx.compose.ui.graphics.vector.ImageVector`, `com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDevice`, `com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDeviceKind` and the device icons (`Headphones`, `Watch`, `Computer`, `Smartphone`, `Keyboard`).

- [ ] **Step 2: `PairedDeviceList` (move device helpers to `BeaconPickerDialogs.kt`)**

Cut `pairedDeviceIcon` and `pairedDeviceKindLabel` from `LocationEditorScreen.kt` and paste them into `BeaconPickerDialogs.kt` as **public** functions (drop `private`). Then add:

```kotlin
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
```

In `LocationEditorScreen.kt` `PairedDevicePickerDialog`, replace the `if (devices.isEmpty()) … devices.forEach { … }` part with:

```kotlin
                PairedDeviceList(
                    devices = devices,
                    seen = seen,
                    scanning = seenNearby != null && seen == null,
                    selectedAddress = null,
                    onPick = onPick,
                )
```

- [ ] **Step 3: Create `ui/BeaconSetupFlow.kt`**

```kotlin
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
```

- [ ] **Step 4: Strings (exact edits; keep the `\'` escapes)**

Add to `res/values/strings.xml`, before `</resources>`:

```xml
    <!-- M21: guided beacon setup -->
    <string name="setup_step_of">Step %1$d of %2$d</string>
    <string name="setup_step_pick">Pick your beacon</string>
    <string name="setup_step_name">Name it</string>
    <string name="setup_step_area">What should count as \"here\"?</string>
    <string name="setup_step_measure">Measure</string>
    <string name="setup_step_check">Check</string>
    <string name="setup_rescan">Scan again</string>
    <string name="setup_advanced_recognise">Advanced: recognise it by…</string>
    <string name="setup_advanced_options">Advanced options</string>
    <string name="setup_name_label">Name (e.g. Desk)</string>
    <string name="setup_name_hint">Used for this beacon on every place that uses it. You can leave it as it is.</string>
    <string name="setup_area_seat">Just my seat</string>
    <string name="setup_area_seat_desc">You\'ll sit where you usually do while it measures.</string>
    <string name="setup_area_edge">This room or area</string>
    <string name="setup_area_edge_desc">You\'ll stand at the farthest spot that should still count.</string>
    <string name="setup_area_size">I\'ll enter the size</string>
    <string name="setup_area_size_desc">Work it out from the room\'s size.</string>
    <string name="setup_area_value">Type a value (dBm)</string>
    <string name="setup_area_value_desc">For people who know signal strengths.</string>
    <string name="setup_measure_seat">Sit where you usually sit, holding or pocketing the phone as usual…</string>
    <string name="setup_measure_edge">Stand at the farthest spot that should still count…</string>
    <string name="setup_measure_waiting">Listening…</string>
    <string name="setup_measure_now">now %1$d dBm · average %2$d dBm (%3$d readings)</string>
    <string name="setup_measure_not_heard">The beacon wasn\'t heard. Check that it\'s on and close by, then measure again.</string>
    <string name="setup_measure_again">Measure again</string>
    <string name="setup_check_instruction">Stay where you\'d normally be: checking for 10 seconds…</string>
    <string name="setup_check_ok">✓ You\'d count as here (signal %1$d, needs %2$d)</string>
    <string name="setup_check_not_yet">Not yet: the signal here is %1$d, it needs %2$d or stronger. Measure again, or choose a bigger area.</string>
    <string name="setup_check_not_heard">The beacon wasn\'t heard during the check.</string>
    <string name="setup_check_again">Check again</string>
    <string name="setup_back">Back</string>
    <string name="setup_next">Next</string>
    <string name="setup_done">Done</string>
    <string name="range_summary">Here within ≈ %1$s (%2$d dBm)</string>
    <string name="range_summary_dbm">Here at %1$d dBm or stronger</string>
```

- [ ] **Step 5: Build, then stage the new file**

Run the build. Expected: no output. Then `git add app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/BeaconSetupFlow.kt`.

---

### Task 3: Range screen, measure dialog, Timing + Get ready early, live-meter warning

**Files:**
- Create: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/BeaconRangeDialogs.kt`
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/BeaconTimingDialog.kt`
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/LiveSignalDialog.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `MeasureContent`, `CheckContent`, `rangeSummary`, `AreaMethod` (Task 2); `BeaconRules.TOO_STRICT_MARGIN_DB` (Task 1).
- Produces:
  - `@Composable fun BeaconRangeDialog(thresholdDbm: Int, oneMeterDbm: Int?, canMeasure: Boolean, onMeasure: (AreaMethod) -> Unit, onRoomSize: () -> Unit, onTypeValue: () -> Unit, onGuide: () -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun BeaconMeasureDialog(watch, key: String, method: AreaMethod, oneMeterDbm: Int?, onUse: (Int) -> Unit, onDismiss: () -> Unit)`
  - `@Composable fun TooStrictWarning(averageDbm: Int, thresholdDbm: Int, onMeasureAgain: () -> Unit)`
  - `BeaconTimingDialog(..., boostSummary: String? = null, onOpenBoost: (() -> Unit)? = null)`

- [ ] **Step 1: Create `ui/BeaconRangeDialogs.kt`**

```kotlin
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
```

- [ ] **Step 2: Timing dialog gets "Get ready early" (`BeaconTimingDialog.kt`)**

Change the signature (add two optional parameters at the end, before `onDismiss` is fine too, but keep named use):

```kotlin
fun BeaconTimingDialog(
    preset: BeaconPreset?,
    custom: BeaconTiming,
    defaultTiming: BeaconTiming?,
    onConfirm: (BeaconPreset?, BeaconTiming) -> Unit,
    onDismiss: () -> Unit,
    boostSummary: String? = null,
    onOpenBoost: (() -> Unit)? = null,
) {
```

Add a state `var more by remember { mutableStateOf(false) }`. Inside the dialog's `Column`, just before the final `Text(stringResource(R.string.beacon_timing_note), …)`, add:

```kotlin
                if (onOpenBoost != null) {
                    TextButton(onClick = { more = !more }) { Text(stringResource(R.string.setup_advanced_options)) }
                    if (more) {
                        SettingsRow(
                            icon = Icons.Filled.Hearing,
                            title = stringResource(R.string.boost_title),
                            summary = boostSummary,
                            onClick = onOpenBoost,
                        )
                    }
                }
```

Add the imports `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.Hearing`.

- [ ] **Step 3: Live meter too-strict line (`LiveSignalDialog.kt`)**

In `LiveFrameRow`, after the `Text(stringResource(if (near) … ))` line, add:

```kotlin
        if (used && mean != null && mean < thresholdDbm - BeaconRules.TOO_STRICT_MARGIN_DB) {
            Text(
                stringResource(R.string.live_too_strict, mean.roundToInt(), thresholdDbm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
```

- [ ] **Step 4: Strings, new and renamed (exact edits)**

Add before `</resources>`:

```xml
    <string name="range_row">Range</string>
    <string name="range_set_by">Set it by</string>
    <string name="too_strict">Lately this beacon has been heard at about %1$d, weaker than the %2$d you set. You may never count as here.</string>
    <string name="live_too_strict">The signal here (about %1$d) is weaker than the range (%2$d): you wouldn\'t count as here. Measure the range again.</string>
    <string name="beacon_row">Beacon</string>
    <string name="beacon_not_set_up">Not set up: tap to set it up</string>
    <string name="device_row">Device</string>
```

Change these existing values (the names stay; spec §4):

| name | new value |
|---|---|
| `beacon_calibrate` | `Measure where I sit` |
| `beacon_calibrate_hint` | `Sit where you usually do; it listens for 20 s and sets the range.` |
| `beacon_calibrate_edge` | `Measure at the edge of the area` |
| `beacon_calibrate_edge_hint` | `Stand at the farthest spot that should still count; it listens for 20 s.` |
| `range_title` | `Enter the room size` |
| `near_info_title` | `What the numbers mean` |
| `beacon_timing_title` | `Timing` |
| `boost_title` | `Get ready early` |
| `live_row` | `Check signal now` |
| `live_title` | `Check signal now` |
| `match_by_title` | `Recognise it by` |

- [ ] **Step 5: Build and stage**

Build: no output expected. Then `git add app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/BeaconRangeDialogs.kt`.

---

### Task 4: The place editor uses the new flow and rows

**Files:**
- Modify: `app/src/main/java/com/fabiantorrestech/lockmyphone_extended/ui/LocationEditorScreen.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–3: `BeaconSetupFlow`, `BeaconSetupState`, `BeaconSetupResult`, `SetupKind`, `AreaMethod`, `BeaconRangeDialog`, `BeaconMeasureDialog`, `TooStrictWarning`, `rangeSummary`, `BeaconRules.rangeTooStrict`, `knownOneMeterDbm`, `BeaconTools.status`, and `BeaconTimingDialog`'s `boostSummary` / `onOpenBoost`.

- [ ] **Step 1: New editor state**

Next to `var matching …` / `var renaming …`, add:

```kotlin
    // The guided setup that is open (beacon or nearby device), if any.
    var settingUp by remember { mutableStateOf<SetupKind?>(null) }
    // A measuring dialog opened from the Range screen: for which signal, and how.
    var measuring by remember { mutableStateOf<Pair<SetupKind, AreaMethod>?>(null) }
```

Delete `var matching …`, `var renaming …`, `private data class RenameTarget(…)`, and the `matching?.let { … }` and `renaming?.let { … }` blocks at the bottom. The setup flow replaces them.

- [ ] **Step 2: Auto-start the setup when the beacon signal is turned on**

Replace `fun toggleSignal(signal: PlaceSignal) { … }` with:

```kotlin
    fun toggleSignal(signal: PlaceSignal) {
        val turningOn = signal !in signals
        val names = if (turningOn) draft.signals + signal.name else draft.signals - signal.name
        onDraftChange(draft.copy(signals = names))
        // A beacon that isn't set up yet: go straight to the guided setup (spec §2).
        if (turningOn && signal == PlaceSignal.BEACON && draft.beaconKey == null && beaconTools.hasPermission && beaconTools.bluetoothOn) {
            settingUp = SetupKind.BEACON
        }
    }
```

- [ ] **Step 3: Replace the whole `if (PlaceSignal.BEACON in signals) { … }` block**

```kotlin
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
```

- [ ] **Step 4: Paired-device section**

In the `if (PlaceSignal.BT_DEVICE in signals) { … }` block:
- The device row's title becomes `stringResource(R.string.device_row)`.
- Its `onClick` becomes `{ if (nearby) settingUp = SetupKind.DEVICE else editing = LocationDialog.DEVICE_PICK }`.
- Its summary becomes `draft.btAddress?.let { beaconTools.nameOf(BeaconRules.deviceKey(it), PairedDevices.normalize(it)) ?: draft.btName } ?: stringResource(R.string.bt_device_none)`.

Then replace everything inside `if (nearby) { … }` **after** the permission note with:

```kotlin
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
```

- [ ] **Step 5: Dialogs: replace the beacon/device entries of `when (editing)`**

Delete these branches: `BEACON_PICK`, `BEACON_CALIBRATE`, `BEACON_EDGE`, `DEVICE_CALIBRATE`, `DEVICE_EDGE`, and the old `BOOST` / `TIMING` / `NEAR_INFO` / `BEACON_RANGE` / `DEVICE_RANGE` branches. Add these (keep `BEACON_THRESHOLD`, `DEVICE_THRESHOLD`, `BEACON_LIVE`, `DEVICE_LIVE`, `DEVICE_PICK`):

```kotlin
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
        LocationDialog.NEAR_INFO -> NearMeansInfoDialog(
            oneMeterDbm = draft.knownOneMeterDbm.takeIf { PlaceSignal.BEACON in signals },
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
```

In `BEACON_LIVE`, use `oneMeterDbm = draft.knownOneMeterDbm`. In `DEVICE_PICK`'s `onPick`, set `editing = null`. Calibrating happens through Range now, so drop the `DEVICE_CALIBRATE` jump.

Update the enum:

```kotlin
private enum class LocationDialog {
    COORDINATES, RADIUS, BEACON_THRESHOLD, BEACON_RANGE, BEACON_LIVE, RANGE_BEACON, NEAR_INFO,
    DEVICE_PICK, DEVICE_THRESHOLD, DEVICE_RANGE, DEVICE_LIVE, RANGE_DEVICE, TIMING, BOOST, TARGET, DELETE,
}
```

- [ ] **Step 6: Setup flow + measure dialog at the bottom of `LocationEditorScreen`**

Where the `matching?.let` block was, add:

```kotlin
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
```

- [ ] **Step 7: Remove dead code and fix imports**

Delete:
- the private composables `NearMeansButtons`, `BeaconNameDialog`, `CalibrationDialog`
- `BEACON_NAME_MAX`
- any import that's now unused (`Icons.Filled.Hearing` / `Fence` / `NetworkCheck` / `Edit` / `Outlined.Info` / `SquareFoot` if unused)

Add the imports:
- `kotlin.math.roundToInt`
- `com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconStatus` (only if referenced)

Keep `NearMeansInfoDialog`.

- [ ] **Step 8: Build**

Build: no output expected. Fix any unresolved reference by re-checking names against the Interfaces blocks above.

---

### Task 5: Release build, install, checklist and handoff

**Files:**
- Modify: `handoffContext/test-checklist.md`, `handoffContext/handoff.md` (not committed)

- [ ] **Step 1: Release build**

`JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleRelease 2>&1 | grep -E "^e:|error|FAIL|BUILD"`. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install on the Pixel 7 and the AVD, launch, check for crashes**

```bash
P=com.fabiantorrestech.lockmyphone_extended
for D in 29071FDH200AFV emulator-5554; do
  adb -s $D install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s $D logcat -c
  adb -s $D shell monkey -p $P -c android.intent.category.LAUNCHER 1
  sleep 6; adb -s $D shell pidof $P; adb -s $D logcat -d -b crash | head
done
```

Expected: `Success`, a pid, an empty crash log.

- [ ] **Step 3: Checklist rows (before T38)**

```
| T67 | Guided beacon setup (M21) | New place → tick Bluetooth beacon → setup opens; Pick (Advanced: recognise it by) → Name → Just my seat → Measure (live readings, result "Here within ≈…") → Check ✓ → Done saves; Cancel at any step changes nothing |
| T68 | Range screen (M21) | Range row "Here within ≈ … (−N dBm)"; Adjust → Measure where I sit / at the edge (measure → check → Done), Enter the room size, Advanced → Type a value, What the numbers mean |
| T69 | Timing + Get ready early (M21) | Timing row opens presets; Advanced options → Get ready early opens the zone choices |
| T70 | Too-strict warning (M21) | With the desk range at −60 (signal ~−71): red warning in the beacon section + in Check signal now; Measure again → new range → warning gone |
| T71 | 1 m sanity (M21) | The desk place's saved −3 no longer shows silly distances (Range shows only dBm, or ≈ from a real value) |
| T72 | Paired device setup (M21) | Device detected nearby: Device row → setup with paired-device list; connected mode → plain picker, no Range rows |
```

- [ ] **Step 4: Handoff**

Update `handoff.md`:
- an M21 block: what was built and the new files
- phase `review`; clear `active_tasks` / `owned_files`
- Next: the owner tests T67–T72

Keep it under 1000 words.
