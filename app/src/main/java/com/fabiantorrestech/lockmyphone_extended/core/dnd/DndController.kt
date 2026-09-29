package com.fabiantorrestech.lockmyphone_extended.core.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.service.notification.Condition
import android.util.Log
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.MainActivity
import com.fabiantorrestech.lockmyphone_extended.R

/** The level's display name, e.g. "Alarms only". */
fun DndLevel.labelRes(): Int = when (this) {
    DndLevel.PRIORITY -> R.string.dnd_level_priority
    DndLevel.ALARMS -> R.string.dnd_level_alarms
    DndLevel.TOTAL_SILENCE -> R.string.dnd_level_silence
}

/**
 * Turns Do Not Disturb on for the length of a lock. It uses the app's own automatic rules, one
 * per [DndLevel], so it never overwrites the user's manual DND setting. Android 15+ lists each as
 * a separate mode that the user can fine-tune in Settings → Modes. Android doesn't let apps turn
 * on the user's own modes (Sleeping, Work…). Emergency calls are never blocked by DND.
 *
 * Every call does nothing if the user hasn't granted DND access.
 */
class DndController(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /** Device-protected, because enable/disable also run before the first unlock after a boot. */
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    /** Turns on [level]'s mode, and the other levels' modes off (only one is on at a time). */
    fun enable(level: DndLevel) {
        if (!hasAccess()) {
            Log.w(TAG, "DND requested but access isn't granted")
            return
        }
        DndLevel.entries.filter { it != level }.forEach(::turnOff)
        val id = ensureRule(level) ?: return
        notificationManager.setAutomaticZenRuleState(id, condition(level, Condition.STATE_TRUE))
        Log.i(TAG, "DND on ($level)")
    }

    fun disable() {
        if (!hasAccess()) return
        DndLevel.entries.forEach(::turnOff)
        Log.i(TAG, "DND off")
    }

    private fun turnOff(level: DndLevel) {
        val id = prefs.getString(ruleIdKey(level), null) ?: return
        if (notificationManager.getAutomaticZenRule(id) == null) return
        notificationManager.setAutomaticZenRuleState(id, condition(level, Condition.STATE_FALSE))
    }

    /** The saved rule for [level], or a new one if it's missing (e.g. the user deleted the mode). */
    private fun ensureRule(level: DndLevel): String? {
        prefs.getString(ruleIdKey(level), null)?.let { id ->
            val existing = notificationManager.getAutomaticZenRule(id)
            if (existing != null) {
                renameIfNeeded(id, existing, level)
                return id
            }
        }

        val rule = AutomaticZenRule(
            ruleName(level),
            null, // no condition provider service: the app sets the state directly
            ComponentName(context, MainActivity::class.java), // opened from the rule's settings
            conditionId(level),
            null,
            interruptionFilter(level),
            true,
        )
        return try {
            notificationManager.addAutomaticZenRule(rule).also { id ->
                prefs.edit { putString(ruleIdKey(level), id) }
            }
        } catch (e: RuntimeException) {
            // Android refuses rules it considers invalid or over the per-app limit.
            Log.w(TAG, "Can't create the DND rule for $level", e)
            null
        }
    }

    /** The priority rule predates the levels and was named "LockMyPhone lock"; give it its level's name. */
    private fun renameIfNeeded(id: String, rule: AutomaticZenRule, level: DndLevel) {
        val wanted = ruleName(level)
        if (rule.name == wanted) return
        try {
            rule.name = wanted
            notificationManager.updateAutomaticZenRule(id, rule)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't rename the DND rule for $level", e)
        }
    }

    private fun ruleName(level: DndLevel): String = context.getString(
        R.string.dnd_rule_name_level,
        context.getString(R.string.dnd_rule_name),
        context.getString(level.labelRes()).lowercase(),
    )

    private fun interruptionFilter(level: DndLevel): Int = when (level) {
        DndLevel.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        DndLevel.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        DndLevel.TOTAL_SILENCE -> NotificationManager.INTERRUPTION_FILTER_NONE
    }

    /** The priority rule keeps its original key and condition, so existing installs reuse it. */
    private fun ruleIdKey(level: DndLevel): String =
        if (level == DndLevel.PRIORITY) KEY_RULE_ID else "${KEY_RULE_ID}_${level.name.lowercase()}"

    private fun conditionId(level: DndLevel): Uri =
        if (level == DndLevel.PRIORITY) CONDITION_ID else Uri.parse("$CONDITION_ID/${level.name.lowercase()}")

    private fun condition(level: DndLevel, state: Int) =
        Condition(conditionId(level), context.getString(R.string.dnd_rule_condition), state)

    private companion object {
        const val TAG = "DndController"
        const val PREFS_NAME = "dnd"
        const val KEY_RULE_ID = "rule_id"
        val CONDITION_ID: Uri = Uri.parse("condition://com.fabiantorrestech.lockmyphone_extended/lock")
    }
}
