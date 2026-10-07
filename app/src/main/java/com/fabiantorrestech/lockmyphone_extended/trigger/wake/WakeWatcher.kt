package com.fabiantorrestech.lockmyphone_extended.trigger.wake

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ChargerSignal

/**
 * Hears every unlock and every plug / unplug, for Morning lock. USER_PRESENT can only be heard by
 * a receiver registered at runtime, so this lives as long as the accessibility service (which the
 * system keeps bound), not only during a lock like [com.fabiantorrestech.lockmyphone_extended.core.service.LockForegroundService].
 *
 * @param onUnlock called after an unlock with when the one before it was, how long the phone
 *   charged in between, when the screen came on in between, and now.
 */
class WakeWatcher(
    private val context: Context,
    private val store: WakeSignalStore,
    private val onUnlock: (previousUnlockMs: Long?, chargedMs: Long, screenOnsMs: List<Long>, nowMs: Long) -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nowMs = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> unlocked(nowMs)
                // With the lock screen set to "None" there's no USER_PRESENT; the screen coming
                // on without a lock screen is the unlock.
                Intent.ACTION_SCREEN_ON -> {
                    store.onScreenOn(nowMs)
                    if (!keyguardShowing()) unlocked(nowMs)
                }
                Intent.ACTION_POWER_CONNECTED -> store.onPowerConnected(nowMs)
                Intent.ACTION_POWER_DISCONNECTED -> store.onPowerDisconnected(nowMs)
            }
        }
    }

    fun register() {
        if (registered) return
        val nowMs = System.currentTimeMillis()
        val bootMs = nowMs - SystemClock.elapsedRealtime()
        val lastUnlockMs = store.lastUnlockMs
        // Plugged in now: it has been charging at least since boot (or the last unlock, if later).
        store.syncCharging(ChargerSignal.read(context).pluggedIn, sinceMs = maxOf(bootMs, lastUnlockMs ?: bootMs))
        // Exported: USER_PRESENT comes from SystemUI's own UID. All four are protected broadcasts
        // that only the system can send.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
        // After a reboot the system binds the accessibility service only once the user has
        // unlocked, so that first unlock came before this receiver existed: count it now.
        if ((lastUnlockMs ?: Long.MIN_VALUE) < bootMs && !keyguardShowing()) {
            Log.i(TAG, "First unlock since boot (before the watcher started)")
            unlocked(nowMs)
        }
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun unlocked(nowMs: Long) {
        val previous = store.lastUnlockMs
        // One physical unlock can send both SCREEN_ON and USER_PRESENT.
        if (previous != null && nowMs - previous < DUPLICATE_MS) return
        val charged = store.chargedMsSinceUnlock(nowMs)
        val screenOns = store.screenOnsSinceUnlock()
        store.onUnlock(nowMs)
        Log.i(TAG, "Unlock (previous ${previous?.let { (nowMs - it) / 60_000 }} min ago, charged ${charged / 60_000} min)")
        onUnlock(previous, charged, screenOns, nowMs)
    }

    private fun keyguardShowing(): Boolean =
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private companion object {
        const val TAG = "WakeWatcher"
        const val DUPLICATE_MS = 5_000L
    }
}
