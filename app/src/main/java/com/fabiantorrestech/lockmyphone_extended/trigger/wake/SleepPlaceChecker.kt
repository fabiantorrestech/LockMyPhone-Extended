package com.fabiantorrestech.lockmyphone_extended.trigger.wake

import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.PlaceSignal
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationTriggerManager
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Presence
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Readings
import com.fabiantorrestech.lockmyphone_extended.trigger.location.WifiRules
import kotlinx.coroutines.withTimeoutOrNull

/** The sleep place you're at, and how that was found. */
data class SleepMatch(val place: LocationTrigger, val proof: SleepProof)

/**
 * Whether you're at one of Morning lock's sleep places. Wi-Fi first (instant, no battery), then
 * one GPS fix only if Wi-Fi didn't match. Any one of a place's signals is enough here, whatever
 * the place itself requires.
 */
class SleepPlaceChecker(private val locationManager: () -> LocationTriggerManager) {

    suspend fun check(places: List<LocationTrigger>): SleepMatch? {
        if (places.isEmpty()) return null
        val manager = locationManager()
        val wifi = manager.connectedWifi()
        places.firstOrNull { it.usesWifi && WifiRules.matchesAny(it.wifiNetworks, wifi) }
            ?.let { return SleepMatch(it, SleepProof.PLACE_WIFI) }

        val gpsPlaces = places.filter { it.usesGps }
        if (gpsPlaces.isEmpty()) return null
        val fix = withTimeoutOrNull(FIX_TIMEOUT_MS) { manager.currentFix() } ?: return null
        val readings = Readings(fix = fix, wifi = null, wifiKnown = false, charger = null, beacon = { Presence.UNKNOWN })
        return gpsPlaces.firstOrNull { place ->
            LocationRules.presence(place.copy(signals = setOf(PlaceSignal.GPS.name)), readings) == Presence.INSIDE
        }?.let { SleepMatch(it, SleepProof.PLACE_GPS) }
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 15_000L
    }
}
