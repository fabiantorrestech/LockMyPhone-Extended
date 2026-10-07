package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.R
import kotlinx.coroutines.launch
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmDayRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleCalculator
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleWindow
import java.time.ZonedDateTime
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSource
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult

/** Which [PresenceEngine] watches the places (Settings → Location engine). */
enum class LocationEngineChoice { PLAY_SERVICES, BUILT_IN }

/** What a Refresh found about the place that started the running lock. */
sealed interface RefreshResult {
    /** You're still there; the lock stays. */
    data class StillHere(val placeName: String) : RefreshResult

    /** You've left; its lock ended. */
    data class Left(val placeName: String) : RefreshResult

    /** No clear reading (no fix, Bluetooth off, permission missing); the lock stays. */
    data class Unknown(val placeName: String) : RefreshResult

    /** The running lock wasn't started by a place that unlocks when you leave. */
    data object NotRefreshable : RefreshResult
}

/**
 * One reading of a (maybe unsaved) place's signals, for Test now.
 *
 * @param heard every beacon and device key heard in a short scan, with its trimmed-mean signal;
 *   null when it couldn't scan (no permission, Bluetooth off) or the place needs no scan.
 */
data class PlaceTestReading(
    val readings: Readings,
    val heard: Map<String, Int>?,
)

/** The Wi-Fi network the phone is connected to right now. */
data class ConnectedWifi(val bssid: String, val ssid: String?)

/**
 * Runs the location triggers: keeps the chosen engine and the Wi-Fi callback registered, and
 * on every wake-up works out where you are, applies [LocationRules] and starts or ends locks.
 * Only runs after the first unlock (it reads credential-protected settings).
 */
class LocationTriggerManager(
    private val context: Context,
    private val database: AppDatabase,
    private val stateStore: LocationStateStore,
    private val lockController: LockController,
    private val lockStarter: ProfileLockStarter,
    private val engineChoice: () -> LocationEngineChoice,
    private val dayStartMinutes: () -> Int,
    /** The default beacon timing (Settings → Beacons); places can have their own. */
    private val defaultBeaconTiming: () -> BeaconTiming = { BeaconTiming.BALANCED },
) {
    val playServices = PlayServicesEngine(context)
    val beaconScanner = BeaconScanner(context)
    private val beaconTracker = BeaconTracker(context) { place -> place.beaconTiming(defaultBeaconTiming()) }
    private val btConnections = BtConnectionTracker(context)

    /** Called when you arrive at any place, so "Only while at…" schedules and calendar triggers can start. */
    var onPlaceEntered: (suspend () -> Unit)? = null

    /** The last precise fix, reused by wake-ups that don't need a new one (beacon, charger, Wi-Fi). */
    @Volatile private var lastFix: Fix? = null

    private var powerReceiverRegistered = false
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LockApp.container(context).appScope.launch { onWake(freshFix = false) }
        }
    }
    /** Networks this phone has connected to, for the network picker. */
    val wifiHistory = WifiHistory(context)

    private var scanResultsReceiverRegistered = false
    private val scanResultsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LockApp.container(context).appScope.launch {
                updateScanPlan(withContext(Dispatchers.IO) { database.locationTriggerDao().getEnabled() })
            }
        }
    }

    private val builtIn = BuiltInEngine(context)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val wifiIntent by lazy { locationPendingIntent(context, LocationReceiver.ACTION_WIFI, WIFI_REQUEST_CODE) }
    private val recheckIntent by lazy { locationPendingIntent(context, LocationReceiver.ACTION_RECHECK, RECHECK_REQUEST_CODE) }
    private val slotIntent by lazy { locationPendingIntent(context, LocationReceiver.ACTION_SLOT, SLOT_REQUEST_CODE) }

    /** The engine in use: the chosen one, or the built-in one when Play Services is missing. */
    fun activeEngine(): PresenceEngine =
        if (engineChoice() == LocationEngineChoice.PLAY_SERVICES && playServices.isAvailable()) playServices else builtIn

    fun hasPreciseLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(): Boolean = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun locationServicesOn(): Boolean = context.getSystemService(LocationManager::class.java).isLocationEnabled

    /** Registers (or removes) the engine and the Wi-Fi callback to match the enabled triggers. */
    suspend fun sync() {
        val triggers = withContext(Dispatchers.IO) { database.locationTriggerDao().getEnabled() }
        stateStore.setEndsOnLeave(triggers.filter(::endsOnLeave).map { it.id }.toSet())
        val engine = activeEngine()
        listOf(playServices, builtIn).filter { it !== engine }.forEach { runCatching { it.stop() } }
        // Beacons and the charger work without location; GPS and Wi-Fi need it.
        watchBeaconsAndCharger(triggers)
        if (triggers.isEmpty() || !hasPreciseLocation()) {
            runCatching { engine.stop() }
            runCatching { connectivity.unregisterNetworkCallback(wifiIntent) }
            alarmManager.cancel(recheckIntent)
            if (triggers.isEmpty()) alarmManager.cancel(slotIntent) else scheduleNextSlotStart(triggers)
            return
        }
        engine.start(triggers)
        scheduleNextSlotStart(triggers)
        runCatching { connectivity.unregisterNetworkCallback(wifiIntent) }
        connectedWifi()?.let(wifiHistory::record)
        // Wi-Fi as a signal or a "listen harder" zone, and to note networks for the network picker.
        if (triggers.isNotEmpty()) {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity.registerNetworkCallback(request, wifiIntent)
        }
    }

    private suspend fun watchBeaconsAndCharger(triggers: List<LocationTrigger>) {
        updateScanPlan(triggers)
        // Connections are reported by BtConnectionReceiver; catch up on ones made before it was watching.
        btConnections.refreshFromProfiles(triggers.filter { it.usesBtConnected }.mapNotNull { it.btAddress }.toSet())
        // A Wi-Fi zone also counts networks in range: re-check when the system's scan results change.
        val wifiZones = triggers.any { it.hasWifiZone() }
        if (wifiZones && !scanResultsReceiverRegistered) {
            ContextCompat.registerReceiver(
                context.applicationContext,
                scanResultsReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            scanResultsReceiverRegistered = true
        } else if (!wifiZones && scanResultsReceiverRegistered) {
            runCatching { context.applicationContext.unregisterReceiver(scanResultsReceiver) }
            scanResultsReceiverRegistered = false
        }
        // The charger as a signal, or as a beacon place's "listen harder" zone.
        val chargerPlaces = triggers.any { it.usesCharger || it.hasChargerZone() }
        ChargerSignal.syncJob(context, chargerPlaces)
        if (chargerPlaces && !powerReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            // Protected system broadcasts; only the system can send them.
            ContextCompat.registerReceiver(context.applicationContext, powerReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            powerReceiverRegistered = true
        }
    }

    /** New sightings from the background beacon scan. */
    suspend fun onBeaconSightings(sightings: List<BeaconSighting>) {
        val triggers = withContext(Dispatchers.IO) { database.locationTriggerDao().getEnabled() }
        beaconTracker.onSightings(triggers, sightings, System.currentTimeMillis())
        onWake(freshFix = false)
    }

    /** A place was saved or edited: forget its old state and take a first reading without firing. */
    suspend fun onTriggerSaved(triggerId: Long) {
        stateStore.forget(triggerId)
        beaconTracker.forget(triggerId)
        sync()
        evaluate(fix = null, fire = false)
    }

    /**
     * Something woke us up (geofence, location update, Wi-Fi change, re-check, or a time slot
     * starting: [slotStarted]).
     */
    suspend fun onWake(fix: Fix? = null, slotStarted: Boolean = false, freshFix: Boolean = true) {
        evaluate(fix, fire = true, slotStarted = slotStarted, freshFix = freshFix)
        scheduleRecheckIfNear()
        if (slotStarted) {
            scheduleNextSlotStart(withContext(Dispatchers.IO) { database.locationTriggerDao().getEnabled() })
        }
    }

    fun onGeofence(triggerIds: List<Long>, entered: Boolean) {
        triggerIds.forEach { stateStore.setNear(it, entered) }
    }

    /** A precise fix with its accuracy, for "Use my current location". */
    suspend fun currentFix(): Fix? = if (hasPreciseLocation()) withTimeoutOrNull(FIX_TIMEOUT_MS) { activeEngine().currentFix() } else null

    @Suppress("DEPRECATION") // WifiInfo from WifiManager still works, with location permission.
    fun connectedWifi(): ConnectedWifi? {
        val info = wifiManager.connectionInfo ?: return null
        val bssid = info.bssid?.takeUnless { it == HIDDEN_BSSID } ?: return null
        val ssid = info.ssid?.removeSurrounding("\"")?.takeUnless { it == UNKNOWN_SSID }
        return ConnectedWifi(bssid, ssid)
    }

    /** The networks in the system's latest Wi-Fi scan (no new scan), strongest first. Needs location. */
    @Suppress("DEPRECATION") // ScanResult.SSID still works; its replacement needs API 33.
    fun nearbyWifi(): List<NearbyWifi> {
        if (!hasPreciseLocation()) return emptyList()
        return runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
            .mapNotNull { result ->
                val bssid = result.BSSID?.takeUnless { it == HIDDEN_BSSID } ?: return@mapNotNull null
                NearbyWifi(bssid, result.SSID?.takeIf { it.isNotEmpty() }, result.level)
            }
            .sortedByDescending { it.rssi }
    }

    /** Asks for a fresh Wi-Fi scan. False when Android refuses (it allows about 4 per 2 minutes). */
    @Suppress("DEPRECATION") // Still the only way to ask for a scan.
    fun startWifiScan(): Boolean = hasPreciseLocation() && runCatching { wifiManager.startScan() }.getOrDefault(false)

    /**
     * @param freshFix take a new precise fix for GPS places; false reuses the last one (beacon,
     *   charger and Wi-Fi wake-ups don't need GPS, and a fix costs battery).
     */
    private suspend fun evaluate(fix: Fix?, fire: Boolean, slotStarted: Boolean = false, freshFix: Boolean = true) {
        val triggers = withContext(Dispatchers.IO) { database.locationTriggerDao().getEnabled() }
        if (triggers.isEmpty()) return
        val slotsByPlace = withContext(Dispatchers.IO) { database.locationSlotDao().getAll() }.groupBy { it.locationId }
        val needsFix = fix == null && freshFix && triggers.any { it.usesGps }
        val position = (fix ?: if (needsFix) currentFix() else null)?.also { lastFix = it } ?: lastFix
        val nowMs = System.currentTimeMillis()
        // A signal whose permission was revoked says nothing (unknown), so it never counts as leaving.
        val canScan = beaconScanner.hasPermission() && locationServicesOn()
        val canSeeConnections = PairedDevices.hasPermission(context)
        val wifiKnown = hasPreciseLocation() && locationServicesOn()
        val connected = if (wifiKnown) connectedWifi() else null
        connected?.let(wifiHistory::record)
        val readings = Readings(
            fix = position,
            wifi = connected,
            wifiKnown = wifiKnown,
            charger = if (triggers.any { it.usesCharger }) ChargerSignal.read(context) else null,
            beacon = { place -> if (canScan) beaconTracker.presence(place, nowMs) else Presence.UNKNOWN },
            btConnected = { address -> if (canSeeConnections) btConnections.presence(address) else Presence.UNKNOWN },
            btNearby = { place -> if (canScan) beaconTracker.devicePresence(place, nowMs) else Presence.UNKNOWN },
        )
        val now = ZonedDateTime.now()
        val day = AlarmDayRules.logicalDay(now.toInstant().toEpochMilli(), dayStartMinutes(), now.zone)
        var entered = false

        for (trigger in triggers) {
            val previous = stateStore.presence(trigger.id)
            val current = LocationRules.presence(trigger, readings)
            stateStore.setPresence(trigger.id, current)
            val transition = LocationRules.transition(previous, current)
            if (transition != null) Log.i(TAG, "'${trigger.name}': $transition (fix ±${position?.accuracyMeters} m)")
            if (transition == Transition.ENTER) entered = true
            if (!fire) continue

            if (trigger.mode == LocationMode.TIME_SLOTS) {
                val window = ScheduleCalculator.activeWindow(slotsByPlace[trigger.id].orEmpty().map { it.asSchedule() }, now)
                    ?: continue
                // No clear reading right now (e.g. no fix yet): go by where we last knew you were.
                val here = if (current == Presence.UNKNOWN) previous else current
                withContext(Dispatchers.Main) { actInSlot(trigger, transition, here, window, slotStarted) }
                continue
            }
            if (transition == null) continue
            if (!AlarmDayRules.isActiveOn(trigger.activeRule, trigger.daysMask, trigger.dates, day)) continue
            withContext(Dispatchers.Main) { act(trigger, transition) }
        }
        ChargerSignal.syncJob(context, triggers.any { it.usesCharger || it.hasChargerZone() })
        // A beacon heard (or gone), or a zone entered (or left), changes how hard the scan listens.
        updateScanPlan(triggers)
        // Arriving somewhere may satisfy an "Only while at…" schedule or calendar trigger.
        if (fire && entered) onPlaceEntered?.invoke()
    }

    /**
     * A changed place's one-minute wait is over (or there was none). If you're there now and it
     * would be locking, it locks now: "while here", or a time slot that's running. Arrive / leave
     * places only act on the moment itself, so they wait for the next one.
     */
    suspend fun catchUp(placeId: Long) {
        val trigger = withContext(Dispatchers.IO) { database.locationTriggerDao().getById(placeId) } ?: return
        if (!trigger.enabled || stateStore.presence(placeId) != Presence.INSIDE) return
        val now = ZonedDateTime.now()
        when (trigger.mode) {
            LocationMode.WHILE_HERE -> {
                val day = AlarmDayRules.logicalDay(now.toInstant().toEpochMilli(), dayStartMinutes(), now.zone)
                if (!AlarmDayRules.isActiveOn(trigger.activeRule, trigger.daysMask, trigger.dates, day)) return
                if (!ownsCurrentLock(trigger)) withContext(Dispatchers.Main) { act(trigger, Transition.ENTER) }
            }
            LocationMode.TIME_SLOTS -> {
                val slots = withContext(Dispatchers.IO) { database.locationSlotDao().getForLocation(placeId) }
                val window = ScheduleCalculator.activeWindow(slots.map { it.asSchedule() }, now) ?: return
                val untilSlotEndMs = window.end.toInstant().toEpochMilli() - System.currentTimeMillis()
                if (untilSlotEndMs > 0 && !ownsCurrentLock(trigger)) {
                    withContext(Dispatchers.Main) { startLock(trigger, untilSlotEndMs) }
                }
            }
            LocationMode.ON_ARRIVAL, LocationMode.ON_LEAVE, LocationMode.CONDITION_ONLY -> Unit
        }
    }

    /**
     * Adaptive beacon scanning (M19): the shared background scan listens lightly until a beacon
     * place is boosted (its beacon was heard lately, or one of its zones says you're close), then
     * as hard as the boosted places' Responsiveness asks. The scanner only restarts on a change.
     */
    private fun updateScanPlan(triggers: List<LocationTrigger>) {
        val scanPlaces = triggers.filter { scanKeys(it).isNotEmpty() }
        if (scanPlaces.isEmpty()) {
            beaconScanner.stopBackground()
            return
        }
        val nowMs = System.currentTimeMillis()
        val wifiReadable = hasPreciseLocation() && locationServicesOn()
        val connected = if (wifiReadable) connectedWifi() else null
        // In range counts here too (not for presence): the system's latest scan, no new scan.
        val inRange by lazy { if (wifiReadable) nearbyWifi() else emptyList() }
        val charging by lazy { ChargerSignal.read(context).pluggedIn }
        val fix = lastFix
        val boosted = scanPlaces.filter { place ->
            val zoneActive = place.boostZones().any { zone ->
                when (zone) {
                    BoostZone.WIFI -> place.wifiZoneNetworks().let { networks ->
                        WifiRules.matchesAny(networks, connected) || WifiRules.inRange(networks, inRange)
                    }
                    BoostZone.GPS -> place.hasCoordinates && (
                        stateStore.isNear(place.id) ||
                            fix?.let {
                                LocationRules.distanceMeters(it.latitude, it.longitude, place.latitude, place.longitude) <=
                                    place.radiusMeters + BoostRules.GPS_ZONE_MARGIN_METERS
                            } == true
                        )
                    BoostZone.CHARGER -> charging
                }
            }
            BoostRules.isBoosted(beaconTracker.heardRecently(place, nowMs), zoneActive)
        }
        val plan = BeaconTiming.scanPlan(boosted.map { it.beaconTiming(defaultBeaconTiming()) })
        if (boosted.isNotEmpty()) Log.d(TAG, "Listening harder for ${boosted.joinToString { "'${it.name}'" }}")
        beaconScanner.startBackground(scanPlaces, plan)
    }

    /** Whether the running lock was started by a place that unlocks when you leave, so Refresh can end it. */
    fun isRefreshable(source: LockSource): Boolean =
        source.kind == LockKind.PLACE && source.ownerId?.let(stateStore::endsOnLeave) == true

    /**
     * Refresh: checks right now whether you're still at the place that started the running lock,
     * and ends its lock if you've left. One short scan / fix decides, without the usual waits
     * (45 s to count as near, 2 min of silence to count as away). Main-safe; takes up to ~25 s.
     */
    suspend fun refreshNow(): RefreshResult {
        val source = (lockController.state.value as? LockState.Locked)?.session?.source
        if (source == null || !isRefreshable(source)) return RefreshResult.NotRefreshable
        val place = withContext(Dispatchers.IO) { database.locationTriggerDao().getById(source.ownerId!!) }
            ?: return RefreshResult.NotRefreshable
        val nowMs = System.currentTimeMillis()
        if (scanKeys(place).isNotEmpty() && beaconScanner.hasPermission() && beaconScanner.isBluetoothOn()) {
            beaconTracker.applySnapshot(place, beaconScanner.readingsByKey(REFRESH_SCAN_MS), nowMs)
        }
        place.btAddress?.takeIf { place.usesBtConnected }?.let { btConnections.refreshFromProfiles(setOf(it)) }
        val fix = if (place.usesGps) currentFix() else null
        evaluate(fix, fire = true, freshFix = false)
        scheduleRecheckIfNear()
        Log.i(TAG, "Refresh of '${place.name}': ${stateStore.presence(place.id)}")
        return when {
            !ownsCurrentLock(place) -> RefreshResult.Left(place.name)
            stateStore.presence(place.id) == Presence.INSIDE -> RefreshResult.StillHere(place.name)
            else -> RefreshResult.Unknown(place.name)
        }
    }

    /**
     * Test now: reads [draft]'s signals once, right now (a fresh fix, a short scan), without
     * changing what the place remembers or starting anything. Takes up to ~25 s.
     */
    suspend fun readNow(draft: LocationTrigger): PlaceTestReading {
        val heard = if (scanKeys(draft).isNotEmpty() && beaconScanner.hasPermission() && beaconScanner.isBluetoothOn()) {
            // Each key's trimmed mean, the same smoothing the place itself uses.
            beaconScanner.readingsByKey(REFRESH_SCAN_MS).mapValues { (_, readings) -> BeaconRules.trimmedMean(readings)!!.roundToInt() }
        } else {
            null
        }
        val canSeeConnections = PairedDevices.hasPermission(context)
        draft.btAddress?.takeIf { draft.usesBtConnected && canSeeConnections }?.let { btConnections.refreshFromProfiles(setOf(it)) }
        val wifiKnown = hasPreciseLocation() && locationServicesOn()
        fun scanned(key: String?, threshold: Int): Presence = when {
            heard == null || key == null -> Presence.UNKNOWN
            (heard[key] ?: Int.MIN_VALUE) >= threshold -> Presence.INSIDE
            else -> Presence.OUTSIDE
        }
        val readings = Readings(
            fix = if (draft.usesGps) currentFix() else null,
            wifi = if (wifiKnown) connectedWifi() else null,
            wifiKnown = wifiKnown,
            charger = if (draft.usesCharger) ChargerSignal.read(context) else null,
            beacon = { place -> scanned(place.beaconKey, place.beaconRssiThreshold) },
            btConnected = { address -> if (canSeeConnections) btConnections.presence(address) else Presence.UNKNOWN },
            btNearby = { place -> scanned(nearbyDeviceKey(place), place.btRssiThreshold) },
        )
        return PlaceTestReading(readings, heard)
    }

    /** What the background check knows about [place]'s beacon (or nearby device): for the editor's warning. */
    fun beaconStatus(place: LocationTrigger, device: Boolean): BeaconStatus? = beaconTracker.status(place, device)

    /** Whether you're at the place [placeId] as last worked out (for "Only while at…" conditions). */
    fun isAt(placeId: Long): Boolean = stateStore.presence(placeId) == Presence.INSIDE

    /**
     * A "during time slots" place while one of its slots is running. Only events act (a slot
     * starting, arriving, leaving), so unlocking early mid-slot isn't undone by the next check.
     */
    private suspend fun actInSlot(
        trigger: LocationTrigger,
        transition: Transition?,
        presence: Presence,
        window: ScheduleWindow,
        slotStarted: Boolean,
    ) {
        val untilSlotEndMs = window.end.toInstant().toEpochMilli() - System.currentTimeMillis()
        if (untilSlotEndMs <= 0) return
        val startedJustNow = slotStarted &&
            System.currentTimeMillis() - window.start.toInstant().toEpochMilli() < SLOT_START_GRACE_MS
        when {
            // The slot began while you're here.
            startedJustNow && presence == Presence.INSIDE && !ownsCurrentLock(trigger) ->
                startLock(trigger, untilSlotEndMs)
            // You arrived or came back during the slot.
            transition == Transition.ENTER && trigger.lockOnReturn && !ownsCurrentLock(trigger) ->
                startLock(trigger, untilSlotEndMs)
            // You left during the slot: unlock, or keep the lock (it already ends with the slot).
            transition == Transition.EXIT && !trigger.stayLockedAfterLeaving -> endLockIfOwned(trigger)
        }
    }

    /** The running lock is the one this place started. */
    private fun ownsCurrentLock(trigger: LocationTrigger): Boolean {
        val source = (lockController.state.value as? LockState.Locked)?.session?.source ?: return false
        return source.kind == LockKind.PLACE && source.ownerId == trigger.id && lockController.isLockActive()
    }

    /** One exact alarm at the next slot start of any "during time slots" place. */
    private suspend fun scheduleNextSlotStart(triggers: List<LocationTrigger>) {
        val slotPlaces = triggers.filter { it.mode == LocationMode.TIME_SLOTS }.map { it.id }.toSet()
        val slots = if (slotPlaces.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { database.locationSlotDao().getAll() }.filter { it.locationId in slotPlaces }
        }
        val next = ScheduleCalculator.nextStart(slots.map { it.asSchedule() }, ZonedDateTime.now())
        if (next == null) {
            alarmManager.cancel(slotIntent)
            return
        }
        val atMs = next.start.toInstant().toEpochMilli()
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, slotIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, slotIntent)
        }
    }

    /** Main thread: [LockController] requires it. */
    private suspend fun act(trigger: LocationTrigger, transition: Transition) {
        val fired = when (trigger.mode) {
            LocationMode.WHILE_HERE -> when (transition) {
                Transition.ENTER -> startLock(trigger, WHILE_HERE_CAP_MS)
                Transition.EXIT -> {
                    endLockIfOwned(trigger)
                    false
                }
            }
            // Handled by actInSlot.
            LocationMode.TIME_SLOTS -> false
            // Only used by "Only while at…" conditions.
            LocationMode.CONDITION_ONLY -> false
            LocationMode.ON_ARRIVAL -> transition == Transition.ENTER && startLock(trigger, durationMs = null)
            LocationMode.ON_LEAVE -> transition == Transition.EXIT && startLock(trigger, durationMs = null)
        }
        if (fired && trigger.activeRule == AlarmActiveRule.ONCE) {
            withContext(Dispatchers.IO) { database.locationTriggerDao().update(trigger.copy(enabled = false)) }
            sync()
            Log.i(TAG, "'${trigger.name}' was a one-time trigger; turned off")
        }
    }

    /**
     * Returns true if it locked. A stronger or equal lock running means it's skipped (ADR 0005).
     *
     * @param durationMs null = the lock's own length.
     */
    private suspend fun startLock(trigger: LocationTrigger, durationMs: Long?): Boolean {
        val label = context.getString(R.string.location_lock_label, trigger.name)
        val profileId = trigger.profileId
        val result = if (profileId == null) {
            lockStarter.startQuick(durationMs = durationMs, label = label, kind = LockKind.PLACE, ownerId = trigger.id)
        } else {
            val profile = withContext(Dispatchers.IO) { database.profileDao().getById(profileId) } ?: return false
            lockStarter.start(profile, durationMs = durationMs, label = label, kind = LockKind.PLACE, ownerId = trigger.id)
        }
        Log.i(TAG, "'${trigger.name}': $result")
        return result is StartResult.Started || result is StartResult.Replaced
    }

    /** Leaving a "while here" place ends the lock, but only one that this place started. */
    private fun endLockIfOwned(trigger: LocationTrigger) {
        if (!ownsCurrentLock(trigger)) return
        Log.i(TAG, "Left '${trigger.name}'; ending its lock")
        lockController.endLockRemotely()
    }

    /**
     * Geofences are at least 100 m wide, so crossing a smaller circle inside one doesn't wake us.
     * While near a place, check again every couple of minutes.
     */
    private suspend fun scheduleRecheckIfNear() {
        val triggers = withContext(Dispatchers.IO) { database.locationTriggerDao().getEnabled() }
        val nowMs = System.currentTimeMillis()
        val nearGeofence = activeEngine() === playServices && stateStore.isNearAny()
        // "Away" is noticed by silence, and unplugging may happen while the app isn't running.
        val chargerPlugged = triggers.any { it.usesCharger } && ChargerSignal.read(context).pluggedIn
        val beaconMs = beaconTracker.recheckInterval(triggers, nowMs)
        val intervalMs = listOfNotNull(
            RECHECK_MS.takeIf { nearGeofence || chargerPlugged },
            beaconMs,
        ).minOrNull()
        if (intervalMs == null) {
            alarmManager.cancel(recheckIntent)
            return
        }
        val atMs = System.currentTimeMillis() + intervalMs
        // Short beacon re-checks (Fast / Advanced) need an exact alarm to be on time; Android may still stretch it while idle.
        if (intervalMs < RECHECK_MS && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, recheckIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, recheckIntent)
        }
    }

    /** Leaving ends this place's lock: "while here", or time slots set to unlock on leaving. */
    private fun endsOnLeave(place: LocationTrigger): Boolean =
        place.mode == LocationMode.WHILE_HERE || (place.mode == LocationMode.TIME_SLOTS && !place.stayLockedAfterLeaving)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "LocationTriggers"
        const val WIFI_REQUEST_CODE = 4003
        const val RECHECK_REQUEST_CODE = 4004
        const val SLOT_REQUEST_CODE = 4005

        /** A slot-start alarm can arrive a little late; within this, it still counts as "just started". */
        const val SLOT_START_GRACE_MS = 5L * 60 * 1_000
        const val RECHECK_MS = 2L * 60 * 1_000

        /** Long enough to hear a beacon that advertises every second or so a few times. */
        const val REFRESH_SCAN_MS = 8_000L
        // Within the time Android gives a background broadcast.
        const val FIX_TIMEOUT_MS = 20_000L

        /** A "while here" lock lasts at most a day; if you stay longer, it ends on time. */
        const val WHILE_HERE_CAP_MS = 24L * 60 * 60 * 1_000
        const val HIDDEN_BSSID = "02:00:00:00:00:00"
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
