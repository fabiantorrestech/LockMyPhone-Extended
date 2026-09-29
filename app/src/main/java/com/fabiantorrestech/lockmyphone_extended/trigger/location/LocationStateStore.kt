package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.content.Context
import androidx.core.content.edit

/**
 * What the location triggers remember between wake-ups: whether you were last inside each
 * place, which places the geofences say you're near, and which lock a "while here" place started.
 */
class LocationStateStore(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun presence(triggerId: Long): Presence =
        prefs.getString(presenceKey(triggerId), null)
            ?.let { saved -> Presence.entries.firstOrNull { it.name == saved } }
            ?: Presence.UNKNOWN

    /** UNKNOWN is never stored, so an unclear reading doesn't erase what was known. */
    fun setPresence(triggerId: Long, presence: Presence) {
        if (presence == Presence.UNKNOWN) return
        prefs.edit { putString(presenceKey(triggerId), presence.name) }
    }

    /** Forget a place, e.g. after it's edited, so the next reading sets it without firing. */
    fun forget(triggerId: Long) {
        prefs.edit {
            remove(presenceKey(triggerId))
            remove(nearKey(triggerId))
        }
    }

    /** The Play Services geofence (the wider wake-up circle) says we're near this place. */
    fun setNear(triggerId: Long, near: Boolean) {
        prefs.edit { if (near) putBoolean(nearKey(triggerId), true) else remove(nearKey(triggerId)) }
    }

    fun isNearAny(): Boolean = prefs.all.keys.any { it.startsWith(NEAR_PREFIX) }

    /** A "while here" place started the lock that began at [lockStartedAtMs]. */
    fun setLockOwner(triggerId: Long, lockStartedAtMs: Long) {
        prefs.edit {
            putLong(KEY_OWNER_TRIGGER, triggerId)
            putLong(KEY_OWNER_STARTED_AT, lockStartedAtMs)
        }
    }

    fun ownsLock(triggerId: Long, lockStartedAtMs: Long): Boolean =
        prefs.getLong(KEY_OWNER_TRIGGER, -1) == triggerId && prefs.getLong(KEY_OWNER_STARTED_AT, -1) == lockStartedAtMs

    private fun presenceKey(id: Long) = "presence_$id"
    private fun nearKey(id: Long) = "$NEAR_PREFIX$id"

    private companion object {
        const val PREFS_NAME = "location_triggers"
        const val NEAR_PREFIX = "near_"
        const val KEY_OWNER_TRIGGER = "owner_trigger"
        const val KEY_OWNER_STARTED_AT = "owner_started_at"
    }
}
