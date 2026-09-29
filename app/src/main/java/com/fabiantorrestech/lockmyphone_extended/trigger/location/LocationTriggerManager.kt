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
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleCalculator
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleWindow
import java.time.ZonedDateTime

/** Which [PresenceEngine] watches the places (Settings → Location engine). */
enum class LocationEngineChoice { PLAY_SERVICES, BUILT_IN }

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
) {
    val playServices = PlayServicesEngine(context)
    val beaconScanner = BeaconScanner(context)
    private val beaconTracker = BeaconTracker(context)
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
        if (triggers.any { it.usesWifi }) {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity.registerNetworkCallback(request, wifiIntent)
        }
    }

    private suspend fun watchBeaconsAndCharger(triggers: List<LocationTrigger>) {
        val scanPlaces = triggers.filter { scanKeys(it).isNotEmpty() }
        if (scanPlaces.isEmpty()) beaconScanner.stopBackground() else beaconScanner.startBackground(scanPlaces)
        // Connections are reported by BtConnectionReceiver; catch up on ones made before it was watching.
        btConnections.refreshFromProfiles(triggers.filter { it.usesBtConnected }.mapNotNull { it.btAddress }.toSet())
        val chargerPlaces = triggers.any { it.usesCharger }
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
        val readings = Readings(
            fix = position,
            wifiBssid = if (wifiKnown) connectedWifi()?.bssid else null,
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
        ChargerSignal.syncJob(context, triggers.any { it.usesCharger })
        // Arriving somewhere may satisfy an "Only while at…" schedule or calendar trigger.
        if (fire && entered) onPlaceEntered?.invoke()
    }

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
                startLock(trigger, untilSlotEndMs, ownsLock = true)
            // You arrived or came back during the slot.
            transition == Transition.ENTER && trigger.lockOnReturn && !ownsCurrentLock(trigger) ->
                startLock(trigger, untilSlotEndMs, ownsLock = true)
            // You left during the slot: unlock, or keep the lock (it already ends with the slot).
            transition == Transition.EXIT && !trigger.stayLockedAfterLeaving -> endLockIfOwned(trigger)
        }
    }

    private fun ownsCurrentLock(trigger: LocationTrigger): Boolean {
        val session = (lockController.state.value as? LockState.Locked)?.session ?: return false
        return stateStore.ownsLock(trigger.id, session.startedAtMs) && lockController.isLockActive()
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
                Transition.ENTER -> startLock(trigger, WHILE_HERE_CAP_MS, ownsLock = true)
                Transition.EXIT -> {
                    endLockIfOwned(trigger)
                    false
                }
            }
            // Handled by actInSlot.
            LocationMode.TIME_SLOTS -> false
            // Only used by "Only while at…" conditions.
            LocationMode.CONDITION_ONLY -> false
            LocationMode.ON_ARRIVAL -> transition == Transition.ENTER && startLock(trigger, durationMs = null, ownsLock = false)
            LocationMode.ON_LEAVE -> transition == Transition.EXIT && startLock(trigger, durationMs = null, ownsLock = false)
        }
        if (fired && trigger.activeRule == AlarmActiveRule.ONCE) {
            withContext(Dispatchers.IO) { database.locationTriggerDao().update(trigger.copy(enabled = false)) }
            sync()
            Log.i(TAG, "'${trigger.name}' was a one-time trigger; turned off")
        }
    }

    /** @param durationMs null = the lock's own length. */
    private suspend fun startLock(trigger: LocationTrigger, durationMs: Long?, ownsLock: Boolean): Boolean {
        val label = context.getString(R.string.location_lock_label, trigger.name)
        val profileId = trigger.profileId
        if (profileId == null) {
            lockStarter.startQuick(durationMs = durationMs, label = label)
        } else {
            val profile = withContext(Dispatchers.IO) { database.profileDao().getById(profileId) } ?: return false
            lockStarter.start(profile, durationMs = durationMs, label = label)
        }
        if (ownsLock) {
            (lockController.state.value as? LockState.Locked)?.let { stateStore.setLockOwner(trigger.id, it.session.startedAtMs) }
        }
        Log.i(TAG, "'${trigger.name}' started a lock")
        return true
    }

    /** Leaving a "while here" place ends the lock, but only one that this place started. */
    private fun endLockIfOwned(trigger: LocationTrigger) {
        val session = (lockController.state.value as? LockState.Locked)?.session ?: return
        if (!stateStore.ownsLock(trigger.id, session.startedAtMs)) return
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
        val beaconOrCharger = beaconTracker.needsRecheck(triggers, nowMs) ||
            (triggers.any { it.usesCharger } && ChargerSignal.read(context).pluggedIn)
        if (nearGeofence || beaconOrCharger) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RECHECK_MS, recheckIntent)
        } else {
            alarmManager.cancel(recheckIntent)
        }
    }

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
        // Within the time Android gives a background broadcast.
        const val FIX_TIMEOUT_MS = 20_000L

        /** A "while here" lock lasts at most a day; if you stay longer, it ends on time. */
        const val WHILE_HERE_CAP_MS = 24L * 60 * 60 * 1_000
        const val HIDDEN_BSSID = "02:00:00:00:00:00"
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
