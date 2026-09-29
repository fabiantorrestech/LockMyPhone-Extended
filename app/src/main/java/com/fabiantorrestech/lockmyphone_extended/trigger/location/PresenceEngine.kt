package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger

/**
 * Wakes the app up when you may have arrived at or left a place, and gets a precise fix on
 * demand. The rules themselves live in [LocationRules]; see ADR 0004 for why there are two.
 */
interface PresenceEngine {
    /** Whether this engine can run on this phone. */
    fun isAvailable(): Boolean

    /** Starts (or restarts) watching [triggers]. Needs location permission. */
    fun start(triggers: List<LocationTrigger>)

    fun stop()

    /** A fresh, precise fix, or null if none could be had. */
    suspend fun currentFix(): Fix?
}

fun Location.toFix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else Float.MAX_VALUE)

/** A PendingIntent to [LocationReceiver] with [action]; mutable, because the system fills in the extras. */
fun locationPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, LocationReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
