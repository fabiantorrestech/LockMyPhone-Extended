package com.fabiantorrestech.lockmyphone_extended.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The public intent API used by MacroDroid, Tasker and similar apps.
 *
 * Start a lock:
 * - `ACTION_START_LOCK`
 * - optional extras: `profile` (a profile name; without it, the Quick lock settings are used),
 *   and `duration_min` or `duration_sec` (default: the profile's or Quick lock's length)
 *
 * Stop a lock:
 * - `ACTION_STOP_LOCK` with extra `token`
 * - It only works when "Allow stopping by intent" is on and the token matches the one shown in
 *   the app.
 *
 * Senders must target this package explicitly: Android doesn't deliver implicit broadcasts to
 * manifest receivers.
 */
class IntentApiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_LOCK -> startLock(context, intent)
            ACTION_STOP_LOCK -> stopLock(context, intent)
        }
    }

    private fun startLock(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        val profileName = intent.getStringExtra(EXTRA_PROFILE)?.trim()?.takeIf { it.isNotEmpty() }
        val durationMs = readDurationMs(intent)
        if (profileName == null) {
            startQuickLock(context, durationMs)
            return
        }
        launchAsync(container.appScope) {
            val profile = withContext(Dispatchers.IO) { container.database.profileDao().getByName(profileName) }
            if (profile == null) {
                Log.w(TAG, "START_LOCK ignored: no profile named '$profileName'")
                return@launchAsync
            }
            container.profileLockStarter.start(
                profile = profile,
                durationMs = durationMs,
                label = context.getString(R.string.intent_lock_label, profile.name),
            )
            Log.i(TAG, "START_LOCK: started '${profile.name}'")
        }
    }

    private fun startQuickLock(context: Context, durationMs: Long?) {
        // Quick lock's settings are credential-protected, so they're unreadable before the first unlock.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            Log.w(TAG, "START_LOCK (quick) ignored: device not unlocked since boot; pass a profile instead")
            return
        }
        LockApp.container(context).profileLockStarter.startQuick(
            durationMs = durationMs,
            label = context.getString(R.string.intent_lock_label, context.getString(R.string.quick_lock)),
        )
        Log.i(TAG, "START_LOCK: started Quick lock")
    }

    private fun stopLock(context: Context, intent: Intent) {
        // The token lives in credential-protected storage, so there's no stop before the first unlock.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            Log.w(TAG, "STOP_LOCK ignored: device not unlocked since boot")
            return
        }
        val container = LockApp.container(context)
        val settings = container.settingsStore
        val given = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        when {
            !settings.intentStopEnabled.value -> Log.w(TAG, "STOP_LOCK ignored: stopping by intent is off")
            !tokensMatch(given, settings.intentToken.value) -> Log.w(TAG, "STOP_LOCK ignored: wrong token")
            else -> {
                container.lockController.endLockRemotely()
                Log.i(TAG, "STOP_LOCK: lock ended by intent")
            }
        }
    }

    /** Constant-time comparison, so the token can't be guessed one character at a time. */
    private fun tokensMatch(given: String, expected: String): Boolean =
        given.isNotEmpty() && MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())

    /** Accepts numbers sent as Int, Long or String, since automation apps differ. */
    private fun readDurationMs(intent: Intent): Long? {
        readPositiveLong(intent, EXTRA_DURATION_SEC)?.let { return it * 1_000 }
        readPositiveLong(intent, EXTRA_DURATION_MIN)?.let { return it * 60_000 }
        return null
    }

    @Suppress("DEPRECATION") // Bundle.get is the only way to read an extra of unknown type.
    private fun readPositiveLong(intent: Intent, key: String): Long? {
        val value = when (val raw = intent.extras?.get(key)) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return value?.takeIf { it > 0 }
    }

    companion object {
        private const val TAG = "IntentApi"
        const val ACTION_START_LOCK = "com.fabiantorrestech.lockmyphone_extended.action.START_LOCK"
        const val ACTION_STOP_LOCK = "com.fabiantorrestech.lockmyphone_extended.action.STOP_LOCK"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_DURATION_MIN = "duration_min"
        const val EXTRA_DURATION_SEC = "duration_sec"
        const val EXTRA_TOKEN = "token"
    }
}
