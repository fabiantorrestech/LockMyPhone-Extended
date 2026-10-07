package com.fabiantorrestech.lockmyphone_extended.trigger.wake

import android.content.Context
import androidx.core.content.edit

/**
 * What Morning lock remembers between unlocks: when the phone was last unlocked, and how long it
 * has been charging since then. Device-protected, because the charger can be plugged in before
 * the first unlock after a reboot. Not backed up; it belongs to this phone.
 */
class WakeSignalStore(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** When the phone was last unlocked, or null if never (since install). */
    val lastUnlockMs: Long?
        get() = prefs.getLong(KEY_LAST_UNLOCK, NONE).takeIf { it != NONE }

    /** How long the phone has charged since the last unlock, counting a charge that's still going. */
    fun chargedMsSinceUnlock(nowMs: Long): Long {
        val finished = prefs.getLong(KEY_CHARGED, 0L)
        val since = prefs.getLong(KEY_CHARGING_SINCE, NONE)
        val ongoing = if (since == NONE) 0L else (nowMs - since).coerceAtLeast(0L)
        return finished + ongoing
    }

    /**
     * The last real wake-up: an unlock after at least [WAKE_GAP_MS] without one, with that gap
     * and how long the phone charged in it. For Test now, which can't use the live values (you
     * just unlocked to run it). Null if there was none yet.
     */
    val lastWake: LastWake?
        get() {
            val atMs = prefs.getLong(KEY_WAKE_AT, NONE).takeIf { it != NONE } ?: return null
            return LastWake(atMs, prefs.getLong(KEY_WAKE_GAP, 0L), prefs.getLong(KEY_WAKE_CHARGED, 0L))
        }

    /** When the screen came on since the last unlock (newest [MAX_SCREEN_ONS], at most one a minute). */
    fun screenOnsSinceUnlock(): List<Long> =
        prefs.getString(KEY_SCREEN_ONS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    fun onScreenOn(nowMs: Long) {
        val times = screenOnsSinceUnlock()
        if (times.isNotEmpty() && nowMs - times.last() < SCREEN_ON_DEDUPE_MS) return
        prefs.edit { putString(KEY_SCREEN_ONS, (times + nowMs).takeLast(MAX_SCREEN_ONS).joinToString(",")) }
    }

    /** An unlock: it becomes the new "last unlock", and the charging total starts again from now. */
    fun onUnlock(nowMs: Long) = prefs.edit {
        remove(KEY_SCREEN_ONS)
        val previous = lastUnlockMs
        if (previous != null && nowMs - previous >= WAKE_GAP_MS) {
            putLong(KEY_WAKE_AT, nowMs)
            putLong(KEY_WAKE_GAP, nowMs - previous)
            putLong(KEY_WAKE_CHARGED, chargedMsSinceUnlock(nowMs))
        }
        putLong(KEY_LAST_UNLOCK, nowMs)
        putLong(KEY_CHARGED, 0L)
        // A charge that's still going counts from this unlock on.
        if (prefs.getLong(KEY_CHARGING_SINCE, NONE) != NONE) putLong(KEY_CHARGING_SINCE, nowMs)
    }

    fun onPowerConnected(nowMs: Long) {
        if (prefs.getLong(KEY_CHARGING_SINCE, NONE) == NONE) prefs.edit { putLong(KEY_CHARGING_SINCE, nowMs) }
    }

    fun onPowerDisconnected(nowMs: Long) {
        val since = prefs.getLong(KEY_CHARGING_SINCE, NONE)
        if (since == NONE) return
        prefs.edit {
            putLong(KEY_CHARGED, prefs.getLong(KEY_CHARGED, 0L) + (nowMs - since).coerceAtLeast(0L))
            putLong(KEY_CHARGING_SINCE, NONE)
        }
    }

    /**
     * When the watcher starts (e.g. after a reboot) the plug may have changed unseen. Charging now
     * counts from [sinceMs] (the caller's best lower bound). A charge that ended unseen is
     * dropped, since when it ended is unknown; that errs toward not locking.
     */
    fun syncCharging(charging: Boolean, sinceMs: Long) {
        val since = prefs.getLong(KEY_CHARGING_SINCE, NONE)
        when {
            charging && since == NONE -> prefs.edit { putLong(KEY_CHARGING_SINCE, sinceMs) }
            !charging && since != NONE -> prefs.edit { putLong(KEY_CHARGING_SINCE, NONE) }
        }
    }

    /** A wake-up: when, after how long without an unlock, and how long it charged in that time. */
    data class LastWake(val atMs: Long, val gapMs: Long, val chargedMs: Long)

    companion object {
        /** The shortest gap remembered as a wake-up: the shortest "minimum sleep" a Morning lock allows. */
        const val WAKE_GAP_MS = 3 * 60 * 60_000L

        private const val PREFS_NAME = "wake_signals"
        private const val KEY_LAST_UNLOCK = "last_unlock_ms"
        private const val KEY_CHARGED = "charged_ms"
        private const val KEY_CHARGING_SINCE = "charging_since_ms"
        private const val KEY_WAKE_AT = "wake_at_ms"
        private const val KEY_WAKE_GAP = "wake_gap_ms"
        private const val KEY_WAKE_CHARGED = "wake_charged_ms"
        private const val KEY_SCREEN_ONS = "screen_ons"
        private const val MAX_SCREEN_ONS = 50
        private const val SCREEN_ON_DEDUPE_MS = 60_000L
        private const val NONE = -1L
    }
}
