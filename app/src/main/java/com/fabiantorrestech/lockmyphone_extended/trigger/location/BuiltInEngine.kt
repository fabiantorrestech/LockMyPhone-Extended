package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The alternative engine: Android's own location updates, delivered to [LocationReceiver]
 * every few minutes. No Play Services needed, but it costs more battery and Android throttles
 * background updates, so it's slower to notice arriving and leaving.
 */
class BuiltInEngine(private val context: Context) : PresenceEngine {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val pendingIntent by lazy { locationPendingIntent(context, LocationReceiver.ACTION_LOCATION, REQUEST_CODE) }
    private val executor by lazy { Executors.newSingleThreadExecutor() }

    override fun isAvailable(): Boolean = true

    @SuppressLint("MissingPermission") // The manager checks the permission first.
    override fun start(triggers: List<LocationTrigger>) {
        locationManager.removeUpdates(pendingIntent)
        // Wi-Fi-only places are handled by the Wi-Fi callback.
        if (triggers.none { it.usesGps || it.hasGpsZone() }) return
        locationManager.requestLocationUpdates(provider(), UPDATE_INTERVAL_MS, UPDATE_DISTANCE_METERS, pendingIntent)
        Log.i(TAG, "Requesting location updates from ${provider()}")
    }

    override fun stop() {
        locationManager.removeUpdates(pendingIntent)
    }

    @SuppressLint("MissingPermission")
    override suspend fun currentFix(): Fix? = withTimeoutOrNull(FIX_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            locationManager.getCurrentLocation(provider(), cancel, executor) { location ->
                continuation.resume(location?.toFix())
            }
        }
    }

    /** The fused provider (GPS + Wi-Fi + cell) where there is one, else GPS. */
    private fun provider(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            LocationManager.FUSED_PROVIDER
        } else {
            LocationManager.GPS_PROVIDER
        }

    private companion object {
        const val TAG = "BuiltInEngine"
        const val REQUEST_CODE = 4002
        const val UPDATE_INTERVAL_MS = 2L * 60 * 1_000
        const val UPDATE_DISTANCE_METERS = 10f
        const val FIX_TIMEOUT_MS = 30_000L
    }
}
