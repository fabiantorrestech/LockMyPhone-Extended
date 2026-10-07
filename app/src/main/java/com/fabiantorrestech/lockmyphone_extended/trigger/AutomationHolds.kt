package com.fabiantorrestech.lockmyphone_extended.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One automation: a schedule, alarm trigger, place, calendar trigger or NFC tag, by its kind and ID. */
data class AutomationKey(val kind: LockKind, val id: Long) {
    fun encode(): String = "${kind.name}:$id"

    companion object {
        fun decode(text: String): AutomationKey? {
            val kind = LockKind.entries.firstOrNull { it.name == text.substringBefore(':') } ?: return null
            val id = text.substringAfter(':').toLongOrNull() ?: return null
            return AutomationKey(kind, id)
        }
    }
}

/** Whether an automation may start its lock right now. Pure logic. */
object HoldRules {

    /** How long a changed automation waits before it's active again. */
    const val COOLDOWN_MS = 60_000L

    fun isHeld(key: AutomationKey, editing: AutomationKey?, cooldowns: Map<AutomationKey, Long>, nowMs: Long): Boolean =
        key == editing || (cooldowns[key] ?: 0L) > nowMs

    /** The cooldowns still running at [nowMs]. */
    fun running(cooldowns: Map<AutomationKey, Long>, nowMs: Long): Map<AutomationKey, Long> =
        cooldowns.filterValues { it > nowMs }

    /** The cooldowns that have ended by [nowMs]. */
    fun ended(cooldowns: Map<AutomationKey, Long>, nowMs: Long): Set<AutomationKey> =
        cooldowns.filterValues { it <= nowMs }.keys
}

/**
 * Keeps an automation from starting its lock while its editor is open, and for a minute after
 * it's saved or switched on (Settings → "Wait 1 minute after changes"), so a change can be
 * checked before it's live. When the wait ends, [onHoldEnded] catches up on anything still
 * going on (a schedule window, being at a place).
 *
 * The editor hold is in memory (the editor lives in this process). Cooldowns are kept in
 * device-protected storage, because schedules sync before the first unlock too.
 */
class AutomationHolds(
    private val context: Context,
    /** Runs the catch-up when an editor closes without saving. */
    private val scope: CoroutineScope,
    private val cooldownEnabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    @Volatile private var editing: AutomationKey? = null

    private val _cooldowns = MutableStateFlow(readCooldowns())

    /** The running cooldowns: when each one ends (epoch ms). For the list rows' countdown. */
    val cooldowns: StateFlow<Map<AutomationKey, Long>> = _cooldowns.asStateFlow()

    /** Catches up on [key] once it's active again. Set by the app container. */
    var onHoldEnded: (suspend (AutomationKey) -> Unit)? = null

    /** The automation whose editor is open. Only one editor is open at a time. */
    fun beginEdit(key: AutomationKey) {
        editing = key
        Log.i(TAG, "Editing ${key.encode()}; held")
    }

    /**
     * The editor closed (saved, discarded or the app left).
     *
     * @param catchUp catch up on what came during the edit now. False when saving, because the
     *   save's own cooldown (or its immediate catch-up) does it.
     */
    fun endEdit(catchUp: Boolean = true) {
        val key = editing ?: return
        Log.i(TAG, "Done editing ${key.encode()}")
        editing = null
        // Still in its after-change wait: that wait's end catches up instead.
        if (catchUp && !isHeld(key)) scope.launch { onHoldEnded?.invoke(key) }
    }

    fun isHeld(key: AutomationKey): Boolean = HoldRules.isHeld(key, editing, _cooldowns.value, clock())

    /** When [key]'s cooldown ends, or null when it's held because it's being edited (or isn't held). */
    fun cooldownEnd(key: AutomationKey): Long? = _cooldowns.value[key]?.takeIf { it > clock() }

    /**
     * [key] was just saved or switched on. With the wait on, it's held for a minute; otherwise
     * it catches up right away.
     */
    suspend fun startCooldown(key: AutomationKey) {
        if (!cooldownEnabled()) {
            onHoldEnded?.invoke(key)
            return
        }
        val untilMs = clock() + HoldRules.COOLDOWN_MS
        save(HoldRules.running(_cooldowns.value, clock()) + (key to untilMs))
        Log.i(TAG, "${key.encode()} changed; active at $untilMs")
        armAlarm()
    }

    /** The automation was deleted: nothing to wait for. */
    fun forget(key: AutomationKey) {
        if (key !in _cooldowns.value) return
        save(_cooldowns.value - key)
        armAlarm()
    }

    /** A cooldown alarm went off (or the app checked after a restart): catch up on the ended ones. */
    suspend fun onAlarm() {
        val now = clock()
        val ended = HoldRules.ended(_cooldowns.value, now)
        save(HoldRules.running(_cooldowns.value, now))
        armAlarm()
        ended.forEach { key ->
            Log.i(TAG, "${key.encode()} is active again")
            onHoldEnded?.invoke(key)
        }
    }

    private fun armAlarm() {
        val intent = alarmIntent()
        val next = _cooldowns.value.values.minOrNull()
        if (next == null) {
            alarmManager.cancel(intent)
        } else if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, intent)
        }
    }

    private fun save(cooldowns: Map<AutomationKey, Long>) {
        prefs.edit {
            clear()
            cooldowns.forEach { (key, untilMs) -> putLong(key.encode(), untilMs) }
        }
        _cooldowns.value = cooldowns
    }

    private fun readCooldowns(): Map<AutomationKey, Long> =
        prefs.all.mapNotNull { (name, value) ->
            val key = AutomationKey.decode(name) ?: return@mapNotNull null
            (value as? Long)?.let { key to it }
        }.toMap()

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HoldEndReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "AutomationHolds"
        const val PREFS_NAME = "automation_holds"
        const val REQUEST_CODE = 4101
    }
}

/** A changed automation's one-minute wait is over. */
class HoldEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        launchAsync(container.appScope) { container.automationHolds.onAlarm() }
    }
}
