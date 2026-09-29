package com.fabiantorrestech.lockmyphone_extended.trigger.location

import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMatch
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Whether you're at a location trigger's place. */
enum class Presence { INSIDE, OUTSIDE, UNKNOWN }

/** Crossing into or out of a place. */
enum class Transition { ENTER, EXIT }

/** A location fix: where, and how sure (the radius of 68% confidence, in meters). */
data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Float)

/**
 * Decides whether you're at a place. Pure logic.
 *
 * Without a Wi-Fi rule, the GPS circle decides:
 * - inside: within the radius, with a fix accurate to max(radius, 50 m)
 * - outside: beyond radius + max(20 m, accuracy), so a wobbly fix at the edge doesn't flap
 * - otherwise unknown (no change)
 *
 * The Wi-Fi network, when set, is exact indoors where GPS drifts: inside while connected to that
 * router, outside otherwise. The place's [LocationMatch] says how the two combine.
 */
object LocationRules {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_ACCURACY_ALLOWANCE_METERS = 50f
    private const val EXIT_MARGIN_METERS = 20f

    fun presence(trigger: LocationTrigger, fix: Fix?, connectedBssid: String?): Presence {
        val bssid = trigger.wifiBssid ?: return gpsPresence(trigger, fix)
        // A router's BSSID is unique and its range is a building.
        val wifi = if (bssid.equals(connectedBssid, ignoreCase = true)) Presence.INSIDE else Presence.OUTSIDE
        return when (trigger.matchMode) {
            LocationMatch.GPS_ONLY -> gpsPresence(trigger, fix)
            LocationMatch.WIFI_ONLY -> wifi
            LocationMatch.GPS_AND_WIFI -> both(gpsPresence(trigger, fix), wifi)
            LocationMatch.GPS_OR_WIFI -> either(gpsPresence(trigger, fix), wifi)
        }
    }

    /** Inside only if both say so; outside as soon as either says so. */
    private fun both(a: Presence, b: Presence): Presence = when {
        a == Presence.INSIDE && b == Presence.INSIDE -> Presence.INSIDE
        a == Presence.OUTSIDE || b == Presence.OUTSIDE -> Presence.OUTSIDE
        else -> Presence.UNKNOWN
    }

    /** Inside if either says so; outside only if both say so. */
    private fun either(a: Presence, b: Presence): Presence = when {
        a == Presence.INSIDE || b == Presence.INSIDE -> Presence.INSIDE
        a == Presence.OUTSIDE && b == Presence.OUTSIDE -> Presence.OUTSIDE
        else -> Presence.UNKNOWN
    }

    /** The GPS circle alone, with the accuracy check and the exit margin. */
    private fun gpsPresence(trigger: LocationTrigger, fix: Fix?): Presence {
        if (fix == null) return Presence.UNKNOWN
        val distance = distanceMeters(fix.latitude, fix.longitude, trigger.latitude, trigger.longitude)
        val radius = trigger.radiusMeters.toDouble()
        return when {
            distance <= radius && fix.accuracyMeters <= max(radius.toFloat(), MIN_ACCURACY_ALLOWANCE_METERS) -> Presence.INSIDE
            distance > radius + max(EXIT_MARGIN_METERS, fix.accuracyMeters) -> Presence.OUTSIDE
            else -> Presence.UNKNOWN
        }
    }

    /**
     * A real crossing only. The first reading after a trigger is saved (UNKNOWN before) sets the
     * state without firing, so saving a place while standing in it doesn't start a lock.
     */
    fun transition(previous: Presence, current: Presence): Transition? = when {
        current == Presence.INSIDE && previous == Presence.OUTSIDE -> Transition.ENTER
        current == Presence.OUTSIDE && previous == Presence.INSIDE -> Transition.EXIT
        else -> null
    }

    /** Great-circle distance (haversine), in meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
    }

    /** Parses "lat, lng" (or "lat lng"); null if it isn't two valid coordinates. */
    fun parseCoordinates(text: String): Pair<Double, Double>? {
        val parts = text.trim().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }
}
