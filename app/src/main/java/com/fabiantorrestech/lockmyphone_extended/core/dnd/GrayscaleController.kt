package com.fabiantorrestech.lockmyphone_extended.core.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenDeviceEffects
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.MainActivity
import com.fabiantorrestech.lockmyphone_extended.R

/**
 * Turns the screen grayscale during a lock, using the app's own mode "LockMyPhone grayscale".
 * The mode lets every notification through (it isn't Do Not Disturb); its only effect is
 * grayscale. Apps can set mode effects only on Android 15+ (API 35), so on older versions every
 * call does nothing.
 *
 * It's switched on once when a lock starts and off when it ends. If the user turns the mode off
 * during the lock, it stays off. Like [DndController], it needs DND access.
 */
class GrayscaleController(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** Device-protected, because a lock can end before the first unlock after a boot. */
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether this Android version lets apps turn on grayscale. */
    fun isSupported(): Boolean = isGrayscaleSupported()

    fun enable() {
        if (!isSupported()) return
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "Grayscale requested but DND access isn't granted")
            return
        }
        val id = ensureRule() ?: return
        notificationManager.setAutomaticZenRuleState(id, condition(Condition.STATE_TRUE))
        Log.i(TAG, "Grayscale on")
    }

    /** Turns grayscale off if it's still on; harmless when the user already turned it off. */
    fun disable() {
        if (!isSupported() || !notificationManager.isNotificationPolicyAccessGranted) return
        val id = prefs.getString(KEY_RULE_ID, null) ?: return
        if (notificationManager.getAutomaticZenRule(id) == null) return
        notificationManager.setAutomaticZenRuleState(id, condition(Condition.STATE_FALSE))
        Log.i(TAG, "Grayscale off")
    }

    /** The saved rule, or a new one if it's missing (e.g. the user deleted the mode). */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun ensureRule(): String? {
        prefs.getString(KEY_RULE_ID, null)
            ?.takeIf { notificationManager.getAutomaticZenRule(it) != null }
            ?.let { return it }

        val rule = AutomaticZenRule.Builder(context.getString(R.string.grayscale_rule_name), CONDITION_ID)
            .setConfigurationActivity(ComponentName(context, MainActivity::class.java))
            // Not Do Not Disturb: everything gets through, only the screen turns gray.
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            .setDeviceEffects(ZenDeviceEffects.Builder().setShouldDisplayGrayscale(true).build())
            .build()
        return try {
            notificationManager.addAutomaticZenRule(rule).also { id ->
                prefs.edit { putString(KEY_RULE_ID, id) }
            }
        } catch (e: RuntimeException) {
            // Android refuses rules it considers invalid or over the per-app limit.
            Log.w(TAG, "Can't create the grayscale rule", e)
            null
        }
    }

    private fun condition(state: Int) =
        Condition(CONDITION_ID, context.getString(R.string.dnd_rule_condition), state)

    private companion object {
        const val TAG = "GrayscaleController"
        const val PREFS_NAME = "grayscale"
        const val KEY_RULE_ID = "rule_id"
        val CONDITION_ID: Uri = Uri.parse("condition://com.fabiantorrestech.lockmyphone_extended/grayscale")
    }
}

/** Whether this Android version lets apps turn on grayscale (mode device effects, Android 15+). */
fun isGrayscaleSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
