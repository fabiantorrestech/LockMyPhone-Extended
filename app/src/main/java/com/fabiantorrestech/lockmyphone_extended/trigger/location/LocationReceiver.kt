package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.IntentCompat
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Every wake-up for the location triggers: Play Services geofence crossings, built-in location
 * updates, Wi-Fi connects/disconnects, and the "near a place" re-check.
 */
class LocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val manager = LockApp.container(context).locationTriggerManager
        val scope = LockApp.container(context).appScope
        when (intent.action) {
            ACTION_GEOFENCE -> {
                val event = GeofencingEvent.fromIntent(intent)
                if (event == null || event.hasError()) {
                    Log.w(TAG, "Geofence error ${event?.errorCode}")
                    return
                }
                val ids = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toLongOrNull() }
                manager.onGeofence(ids, entered = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER)
                // The geofence is only a wake-up; the precise circle is checked with a fresh fix.
                launchAsync(scope) { manager.onWake() }
            }
            ACTION_LOCATION -> {
                val location = IntentCompat.getParcelableExtra(intent, LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
                launchAsync(scope) { manager.onWake(location?.toFix()) }
            }
            ACTION_WIFI, ACTION_RECHECK -> launchAsync(scope) { manager.onWake() }
            ACTION_SLOT -> launchAsync(scope) { manager.onWake(slotStarted = true) }
        }
    }

    companion object {
        private const val TAG = "LocationReceiver"
        const val ACTION_GEOFENCE = "com.fabiantorrestech.lockmyphone_extended.action.GEOFENCE"
        const val ACTION_LOCATION = "com.fabiantorrestech.lockmyphone_extended.action.LOCATION"
        const val ACTION_WIFI = "com.fabiantorrestech.lockmyphone_extended.action.WIFI_CHANGED"
        const val ACTION_RECHECK = "com.fabiantorrestech.lockmyphone_extended.action.LOCATION_RECHECK"
        const val ACTION_SLOT = "com.fabiantorrestech.lockmyphone_extended.action.LOCATION_SLOT_START"
    }
}
