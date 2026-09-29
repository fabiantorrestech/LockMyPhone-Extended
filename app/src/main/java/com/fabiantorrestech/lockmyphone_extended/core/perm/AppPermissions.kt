package com.fabiantorrestech.lockmyphone_extended.core.perm

import com.fabiantorrestech.lockmyphone_extended.data.db.BtDetection
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.PlaceSignal

/** Every permission or special access the app can use (Settings → Permissions). */
enum class AppPermission {
    DEVICE_ADMIN,
    NOTIFICATIONS,
    ACCESSIBILITY,
    BATTERY,
    EXACT_ALARMS,
    PRECISE_LOCATION,
    BACKGROUND_LOCATION,
    LOCATION_SERVICES,

    /** "Nearby devices": finding beacons (and paired devices nearby). */
    NEARBY_SCAN,

    /** "Nearby devices": listing paired devices and hearing them connect. */
    NEARBY_CONNECT,

    /** "Nearby devices": "Act as a beacon". */
    NEARBY_ADVERTISE,
    CALENDAR,
    NOTIFICATION_ACCESS,
    DND_ACCESS,
}

/** Whether something can work with the permissions granted right now. */
enum class PauseState {
    OK,

    /** Some of it works: e.g. a place that needs any one signal, and the charger still works. */
    PARTLY,

    /** Nothing happens until the missing permissions are allowed again. */
    PAUSED,
}

data class PermissionCheck(val state: PauseState, val missing: Set<AppPermission>)

/**
 * What each automation needs, and whether it can run with what's granted. Pure logic. Nothing
 * is switched off when a permission is missing; it simply pauses until it's allowed again.
 */
object PermissionNeeds {

    private val LOCATION = setOf(AppPermission.PRECISE_LOCATION, AppPermission.BACKGROUND_LOCATION, AppPermission.LOCATION_SERVICES)

    /** Scanning for Bluetooth LE also needs location on Android: scan results can reveal where you are. */
    private val SCAN = LOCATION + AppPermission.NEARBY_SCAN

    fun forSignal(place: LocationTrigger, signal: PlaceSignal): Set<AppPermission> = when (signal) {
        PlaceSignal.GPS, PlaceSignal.WIFI -> LOCATION
        PlaceSignal.BEACON -> SCAN
        PlaceSignal.BT_DEVICE -> if (place.btDetection == BtDetection.NEARBY) SCAN else setOf(AppPermission.NEARBY_CONNECT)
        PlaceSignal.CHARGER -> emptySet()
    }

    fun forPlace(place: LocationTrigger): Set<AppPermission> = place.signalSet.flatMap { forSignal(place, it) }.toSet()

    /** A place pauses when a signal it can't do without is missing: any signal with "all", every signal with "any". */
    fun checkPlace(place: LocationTrigger, granted: Set<AppPermission>): PermissionCheck {
        val signals = place.signalSet
        val broken = signals.filterNot { granted.containsAll(forSignal(place, it)) }
        val missing = forPlace(place) - granted
        val state = when {
            broken.isEmpty() -> PauseState.OK
            place.requireAll || broken.size == signals.size -> PauseState.PAUSED
            else -> PauseState.PARTLY
        }
        return PermissionCheck(state, missing)
    }

    val forCalendarTrigger: Set<AppPermission> = setOf(AppPermission.CALENDAR)

    val forAlarmTrigger: Set<AppPermission> = setOf(AppPermission.NOTIFICATION_ACCESS)

    fun forProfile(profile: LockProfile): Set<AppPermission> =
        if (profile.dndEnabled || profile.grayscale) setOf(AppPermission.DND_ACCESS) else emptySet()

    /**
     * @param partial true when the thing still works without them, only less (a profile's lock
     *   still runs without DND).
     */
    fun check(needed: Set<AppPermission>, granted: Set<AppPermission>, partial: Boolean = false): PermissionCheck {
        val missing = needed - granted
        val state = when {
            missing.isEmpty() -> PauseState.OK
            partial -> PauseState.PARTLY
            else -> PauseState.PAUSED
        }
        return PermissionCheck(state, missing)
    }
}
