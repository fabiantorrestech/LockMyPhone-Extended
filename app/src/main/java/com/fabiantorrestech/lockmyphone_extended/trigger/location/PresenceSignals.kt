package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiNetwork
import kotlinx.coroutines.launch

/**
 * The beacon near/away state of each beacon place, kept between wake-ups in device-protected
 * prefs. The rule itself is [BeaconRules.update].
 */
class BeaconTracker(
    context: Context,
    /** Each place's [BeaconTiming] (its own, or the default). */
    private val timingOf: (LocationTrigger) -> BeaconTiming = { BeaconTiming.BALANCED },
) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Folds the latest sightings (strongest per key) into every place's tracks: its beacon, and
     * its paired device when that's detected by being nearby.
     */
    fun onSightings(places: List<LocationTrigger>, sightings: List<BeaconSighting>, nowMs: Long) {
        // Every reading in the batch counts towards the trimmed mean, not just the strongest.
        val readings = sightings.groupBy { it.key }.mapValues { (_, list) -> list.map { it.rssi } }
        places.forEach { place ->
            if (place.usesBeacon) {
                val key = beaconTrackKey(place.id)
                save(key, BeaconRules.update(load(key), readings[place.beaconKey].orEmpty(), nowMs, place.beaconRssiThreshold, timingOf(place)))
            }
            nearbyDeviceKey(place)?.let { deviceKey ->
                val key = deviceTrackKey(place.id)
                save(key, BeaconRules.update(load(key), readings[deviceKey].orEmpty(), nowMs, place.btRssiThreshold, timingOf(place)))
            }
        }
    }

    /**
     * A Refresh: [readings] (key → its readings) from one short scan decide [place]'s beacon and
     * nearby-device tracks right away ([BeaconRules.snapshot]).
     */
    fun applySnapshot(place: LocationTrigger, readings: Map<String, List<Int>>, nowMs: Long) {
        if (place.usesBeacon) {
            save(beaconTrackKey(place.id), BeaconRules.snapshot(readings[place.beaconKey].orEmpty(), nowMs, place.beaconRssiThreshold, timingOf(place)))
        }
        nearbyDeviceKey(place)?.let { deviceKey ->
            save(deviceTrackKey(place.id), BeaconRules.snapshot(readings[deviceKey].orEmpty(), nowMs, place.btRssiThreshold, timingOf(place)))
        }
    }

    /** Beacon near or away right now; also lets silence turn a place "away" without new sightings. */
    fun presence(place: LocationTrigger, nowMs: Long): Presence =
        current(beaconTrackKey(place.id), nowMs, place.beaconRssiThreshold, timingOf(place))

    /** The same for a paired device detected by being nearby. */
    fun devicePresence(place: LocationTrigger, nowMs: Long): Presence =
        current(deviceTrackKey(place.id), nowMs, place.btRssiThreshold, timingOf(place))

    /** The latest state of [place]'s beacon (or, with [device], its nearby paired device); null if never heard. */
    fun status(place: LocationTrigger, device: Boolean): BeaconStatus? {
        val track = load(if (device) deviceTrackKey(place.id) else beaconTrackKey(place.id))
        if (track.lastSeenMs == 0L) return null
        return BeaconStatus(track.lastSeenMs, track.smoothedRssi, track.near)
    }

    /** [place]'s beacon (or nearby device) is near, or was heard lately: it should be listened to harder. */
    fun heardRecently(place: LocationTrigger, nowMs: Long): Boolean {
        val leaveMs = timingOf(place).leaveMs
        return listOfNotNull(
            beaconTrackKey(place.id).takeIf { place.usesBeacon },
            deviceTrackKey(place.id).takeIf { place.usesBtNearby },
        ).any { key ->
            val track = load(key)
            track.near || (track.lastSeenMs > 0 && nowMs - track.lastSeenMs < leaveMs * 2)
        }
    }

    /**
     * How soon "away" must be re-checked: the shortest re-check interval among places whose beacon
     * is near or was heard lately, or null when none needs it.
     */
    fun recheckInterval(places: List<LocationTrigger>, nowMs: Long): Long? = places.filter { place ->
        val leaveMs = timingOf(place).leaveMs
        listOfNotNull(
            beaconTrackKey(place.id).takeIf { place.usesBeacon },
            deviceTrackKey(place.id).takeIf { place.usesBtNearby },
        ).any { key ->
            val track = load(key)
            track.near || (track.lastSeenMs > 0 && nowMs - track.lastSeenMs < leaveMs * 2)
        }
    }.minOfOrNull { timingOf(it).recheckMs }

    fun forget(placeId: Long) = prefs.edit {
        remove(beaconTrackKey(placeId))
        remove(deviceTrackKey(placeId))
    }

    private fun current(key: String, nowMs: Long, threshold: Int, timing: BeaconTiming): Presence {
        val track = BeaconRules.update(load(key), emptyList(), nowMs, threshold, timing)
        save(key, track)
        return if (track.near) Presence.INSIDE else Presence.OUTSIDE
    }

    private fun load(key: String): BeaconTrack {
        val raw = prefs.getString(key, null) ?: return BeaconTrack()
        val parts = raw.split('|')
        // 5 parts before the trimmed mean (no recent readings), 6 after.
        if (parts.size != 5 && parts.size != 6) return BeaconTrack()
        return BeaconTrack(
            smoothedRssi = parts[0].toDoubleOrNull(),
            lastSeenMs = parts[1].toLongOrNull() ?: 0,
            aboveSinceMs = parts[2].toLongOrNull(),
            belowSinceMs = parts[3].toLongOrNull(),
            near = parts[4] == "1",
            recent = parts.getOrNull(5).orEmpty().split(';').mapNotNull { reading ->
                val time = reading.substringBefore(':').toLongOrNull()
                val rssi = reading.substringAfter(':').toIntOrNull()
                if (time != null && rssi != null) Reading(time, rssi) else null
            },
        )
    }

    private fun save(key: String, track: BeaconTrack) = prefs.edit {
        putString(
            key,
            listOf(
                track.smoothedRssi?.toString().orEmpty(),
                track.lastSeenMs.toString(),
                track.aboveSinceMs?.toString().orEmpty(),
                track.belowSinceMs?.toString().orEmpty(),
                if (track.near) "1" else "0",
                track.recent.joinToString(";") { "${it.timeMs}:${it.rssi}" },
            ).joinToString("|"),
        )
    }

    private fun beaconTrackKey(placeId: Long) = "track_$placeId"

    private fun deviceTrackKey(placeId: Long) = "track_${placeId}_device"

    private companion object {
        const val PREFS_NAME = "beacon_tracks"
    }
}

/** When [this] place listens harder before its beacon is heard; null = AUTO. */
val LocationTrigger.boost: BeaconBoost
    get() = BeaconBoost.entries.firstOrNull { it.name == beaconBoost } ?: BeaconBoost.AUTO

/** The zones that make [this] place listen harder (see [BoostRules.zonesFor]). */
fun LocationTrigger.boostZones(): Set<BoostZone> = BoostRules.zonesFor(
    boost = boost,
    chosen = beaconBoostZones.mapNotNull { name -> BoostZone.entries.firstOrNull { it.name == name } }.toSet(),
    usesWifi = usesWifi,
    usesGps = usesGps,
    usesCharger = usesCharger,
)

/** The networks of [this] place's Wi-Fi zone: its own Wi-Fi signal's with AUTO, else the chosen ones. */
fun LocationTrigger.wifiZoneNetworks(): List<WifiNetwork> = if (boost == BeaconBoost.AUTO) wifiNetworks else beaconBoostWifiNetworks

/** [this] place has coordinates (a place without GPS keeps 0, 0). */
val LocationTrigger.hasCoordinates: Boolean
    get() = !latitude.isNaN() && !longitude.isNaN() && !(latitude == 0.0 && longitude == 0.0)

/** [this] beacon place listens harder near its GPS area, so it needs geofences / location updates. */
fun LocationTrigger.hasGpsZone(): Boolean = scanKeys(this).isNotEmpty() && BoostZone.GPS in boostZones() && hasCoordinates

/** [this] beacon place listens harder on its Wi-Fi zone, so the Wi-Fi callback must run. */
fun LocationTrigger.hasWifiZone(): Boolean = scanKeys(this).isNotEmpty() && BoostZone.WIFI in boostZones() && wifiZoneNetworks().isNotEmpty()

/** [this] beacon place listens harder while charging, so power changes must wake the app. */
fun LocationTrigger.hasChargerZone(): Boolean = scanKeys(this).isNotEmpty() && BoostZone.CHARGER in boostZones()

/** [this] place's beacon 1 m signal, only when plausible (a saved -3 counts as unknown). */
val LocationTrigger.knownOneMeterDbm: Int?
    get() = BeaconRules.plausibleOneMeter(beaconOneMeterDbm)

/** [place]'s beacon timing: its own preset (and Advanced numbers), or [default]. */
fun LocationTrigger.beaconTiming(default: BeaconTiming): BeaconTiming =
    BeaconTiming.resolve(beaconPreset, beaconArriveSec, beaconLeaveSec, beaconRecheckSec, beaconScanSpeed, default)

/** The scan key of a place's paired device, when it's detected by being nearby. */
fun nearbyDeviceKey(place: LocationTrigger): String? =
    place.btAddress?.takeIf { place.usesBtNearby }?.let(BeaconRules::deviceKey)

/** Everything a place needs the background scan to listen for. */
fun scanKeys(place: LocationTrigger): List<String> =
    listOfNotNull(place.beaconKey?.takeIf { place.usesBeacon }, nearbyDeviceKey(place))

/** The charger signal: read from the battery status; woken by a "starts charging" job. */
object ChargerSignal {

    fun read(context: Context): ChargerReading {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return ChargerReading(pluggedIn = plugged != 0, wireless = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS)
    }

    /**
     * While not charging, a job waits for charging to start (it runs even if the app isn't).
     * While charging, it's cancelled; unplugging is noticed by the re-check and, while the app
     * runs, by the power receiver.
     */
    fun syncJob(context: Context, wanted: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!wanted || read(context).pluggedIn) {
            scheduler.cancel(ChargingJob.JOB_ID)
            return
        }
        scheduler.schedule(
            JobInfo.Builder(ChargingJob.JOB_ID, ComponentName(context, ChargingJob::class.java))
                .setRequiresCharging(true)
                .build()
        )
    }
}

/** Runs when the phone starts charging (for charger places). */
class ChargingJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val container = LockApp.container(this)
        container.appScope.launch {
            try {
                container.locationTriggerManager.onWake(freshFix = false)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val JOB_ID = 4007
    }
}
