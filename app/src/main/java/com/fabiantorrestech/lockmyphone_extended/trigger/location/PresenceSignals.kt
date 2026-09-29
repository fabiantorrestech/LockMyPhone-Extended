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
import kotlinx.coroutines.launch

/**
 * The beacon near/away state of each beacon place, kept between wake-ups in device-protected
 * prefs. The rule itself is [BeaconRules.update].
 */
class BeaconTracker(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Folds the latest sightings (strongest per key) into every place's tracks: its beacon, and
     * its paired device when that's detected by being nearby.
     */
    fun onSightings(places: List<LocationTrigger>, sightings: List<BeaconSighting>, nowMs: Long) {
        val strongest = sightings.groupBy { it.key }.mapValues { (_, list) -> list.maxOf { it.rssi } }
        places.forEach { place ->
            if (place.usesBeacon) {
                val key = beaconTrackKey(place.id)
                save(key, BeaconRules.update(load(key), strongest[place.beaconKey], nowMs, place.beaconRssiThreshold))
            }
            nearbyDeviceKey(place)?.let { deviceKey ->
                val key = deviceTrackKey(place.id)
                save(key, BeaconRules.update(load(key), strongest[deviceKey], nowMs, place.btRssiThreshold))
            }
        }
    }

    /** Beacon near or away right now; also lets silence turn a place "away" without new sightings. */
    fun presence(place: LocationTrigger, nowMs: Long): Presence =
        current(beaconTrackKey(place.id), nowMs, place.beaconRssiThreshold)

    /** The same for a paired device detected by being nearby. */
    fun devicePresence(place: LocationTrigger, nowMs: Long): Presence =
        current(deviceTrackKey(place.id), nowMs, place.btRssiThreshold)

    /** Whether anything scanned for is near or was heard lately: then "away" must be re-checked. */
    fun needsRecheck(places: List<LocationTrigger>, nowMs: Long): Boolean = places.any { place ->
        listOfNotNull(
            beaconTrackKey(place.id).takeIf { place.usesBeacon },
            deviceTrackKey(place.id).takeIf { place.usesBtNearby },
        ).any { key ->
            val track = load(key)
            track.near || (track.lastSeenMs > 0 && nowMs - track.lastSeenMs < BeaconRules.LEAVE_MS * 2)
        }
    }

    fun forget(placeId: Long) = prefs.edit {
        remove(beaconTrackKey(placeId))
        remove(deviceTrackKey(placeId))
    }

    private fun current(key: String, nowMs: Long, threshold: Int): Presence {
        val track = BeaconRules.update(load(key), null, nowMs, threshold)
        save(key, track)
        return if (track.near) Presence.INSIDE else Presence.OUTSIDE
    }

    private fun load(key: String): BeaconTrack {
        val raw = prefs.getString(key, null) ?: return BeaconTrack()
        val parts = raw.split('|')
        if (parts.size != 5) return BeaconTrack()
        return BeaconTrack(
            smoothedRssi = parts[0].toDoubleOrNull(),
            lastSeenMs = parts[1].toLongOrNull() ?: 0,
            aboveSinceMs = parts[2].toLongOrNull(),
            belowSinceMs = parts[3].toLongOrNull(),
            near = parts[4] == "1",
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
            ).joinToString("|"),
        )
    }

    private fun beaconTrackKey(placeId: Long) = "track_$placeId"

    private fun deviceTrackKey(placeId: Long) = "track_${placeId}_device"

    private companion object {
        const val PREFS_NAME = "beacon_tracks"
    }
}

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
