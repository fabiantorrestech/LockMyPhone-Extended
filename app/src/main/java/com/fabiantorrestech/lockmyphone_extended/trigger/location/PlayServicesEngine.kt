package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * The default engine: Google Play Services geofences. They're registered at least
 * [MIN_GEOFENCE_RADIUS_METERS] wide, as Android recommends, and only wake the app up; the
 * precise circle is then checked with a fresh high-accuracy fix.
 */
class PlayServicesEngine(private val context: Context) : PresenceEngine {

    private val geofencing by lazy { LocationServices.getGeofencingClient(context) }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val pendingIntent by lazy { locationPendingIntent(context, LocationReceiver.ACTION_GEOFENCE, REQUEST_CODE) }

    override fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    @SuppressLint("MissingPermission") // The manager checks the permission first.
    override fun start(triggers: List<LocationTrigger>) {
        // Wi-Fi-only places are handled by the Wi-Fi callback; they need no geofence.
        val fences = triggers.filter { it.usesGps }.map { trigger ->
            Geofence.Builder()
                .setRequestId(trigger.id.toString())
                .setCircularRegion(trigger.latitude, trigger.longitude, max(trigger.radiusMeters, MIN_GEOFENCE_RADIUS_METERS).toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        geofencing.removeGeofences(pendingIntent).addOnCompleteListener {
            if (fences.isEmpty()) return@addOnCompleteListener
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences)
                .build()
            geofencing.addGeofences(request, pendingIntent)
                .addOnSuccessListener { Log.i(TAG, "Watching ${fences.size} geofence(s)") }
                .addOnFailureListener { Log.w(TAG, "Couldn't add geofences", it) }
        }
    }

    override fun stop() {
        if (isAvailable()) geofencing.removeGeofences(pendingIntent)
    }

    @SuppressLint("MissingPermission")
    override suspend fun currentFix(): Fix? = suspendCancellableCoroutine { continuation ->
        val cancel = CancellationTokenSource()
        continuation.invokeOnCancellation { cancel.cancel() }
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token)
            .addOnSuccessListener { continuation.resume(it?.toFix()) }
            .addOnFailureListener {
                Log.w(TAG, "No current location", it)
                continuation.resume(null)
            }
    }

    private companion object {
        const val TAG = "PlayServicesEngine"
        const val REQUEST_CODE = 4001
        const val MIN_GEOFENCE_RADIUS_METERS = 100
    }
}
