package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockEndScheduler
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult

/**
 * Starts the lock for a dismissed alarm: right away, or after the trigger's delay using an
 * exact alarm.
 */
class AlarmTriggerFirer(
    private val context: Context,
    private val database: AppDatabase,
    private val lockStarter: ProfileLockStarter,
    private val endScheduler: LockEndScheduler,
    /** When a "day" starts for the trigger's day rules, in minutes past midnight. */
    private val dayStartMinutes: () -> Int,
    /** A dismissal that counts (not a snooze, an active day): Morning lock stands down. */
    private val onFired: suspend (triggerId: Long) -> Unit = {},
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Called on the main thread once a dismissal is confirmed (not a snooze). */
    suspend fun onDismissed(dismissed: AlarmTrigger) {
        // Re-read it: the listener's copy may predate today's first dismissal.
        val dao = database.alarmTriggerDao()
        val trigger = withContext(Dispatchers.IO) { dao.getById(dismissed.id) } ?: return
        val day = AlarmDayRules.logicalDay(System.currentTimeMillis(), dayStartMinutes(), ZoneId.systemDefault())
        if (!AlarmDayRules.shouldFire(trigger, day)) {
            Log.i(TAG, "'${trigger.name}' dismissed, but not active on $day (or already fired today); no lock")
            return
        }
        if (trigger.kind == AlarmTriggerKind.FIRST_ALARM) {
            withContext(Dispatchers.IO) { dao.update(trigger.copy(lastFiredDay = day.toString())) }
        }
        onFired(trigger.id)
        if (trigger.delayMinutes <= 0) {
            fire(trigger.id)
            return
        }
        val atMs = System.currentTimeMillis() + trigger.delayMinutes * 60_000L
        val pendingIntent = delayedIntent(trigger.id)
        if (endScheduler.canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pendingIntent)
        }
        Log.i(TAG, "'${trigger.name}' dismissed; locking in ${trigger.delayMinutes} min")
    }

    /** Starts the trigger's lock now. Main thread. */
    suspend fun fire(triggerId: Long) {
        val trigger = withContext(Dispatchers.IO) { database.alarmTriggerDao().getById(triggerId) }
        if (trigger == null || !trigger.enabled) return
        val label = context.getString(R.string.alarm_lock_label, trigger.name)
        val profileId = trigger.profileId
        // A stronger or equal lock running means this one is skipped (ADR 0005).
        val result = if (profileId == null) {
            lockStarter.startQuick(durationMs = null, label = label, kind = LockKind.ALARM, ownerId = trigger.id)
        } else {
            val profile = withContext(Dispatchers.IO) { database.profileDao().getById(profileId) } ?: return
            lockStarter.start(profile, durationMs = null, label = label, kind = LockKind.ALARM, ownerId = trigger.id)
        }
        Log.i(TAG, "'${trigger.name}' dismissed: $result")
        // A one-time trigger stays on until it has really locked (not refused, not held for an edit).
        if (trigger.activeRule == AlarmActiveRule.ONCE && result !is StartResult.Refused && result !is StartResult.Held) {
            // Re-read, so the first-alarm day saved at dismissal isn't overwritten.
            withContext(Dispatchers.IO) {
                val dao = database.alarmTriggerDao()
                dao.getById(triggerId)?.let { dao.update(it.copy(enabled = false)) }
            }
            Log.i(TAG, "'${trigger.name}' was a one-time trigger; turned off")
        }
    }

    private fun delayedIntent(triggerId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE_BASE + triggerId.toInt(),
        Intent(context, AlarmTriggerReceiver::class.java).putExtra(AlarmTriggerReceiver.EXTRA_TRIGGER_ID, triggerId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "AlarmTrigger"
        const val REQUEST_CODE_BASE = 2000
    }
}
