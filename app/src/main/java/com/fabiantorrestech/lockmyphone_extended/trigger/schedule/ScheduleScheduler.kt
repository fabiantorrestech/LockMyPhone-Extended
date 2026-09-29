package com.fabiantorrestech.lockmyphone_extended.trigger.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockEndScheduler
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * Keeps schedules running:
 * - Exactly one exact alarm is set, for the next window start.
 * - [sync] also starts a lock if a window is active right now. This catches up after a
 *   reboot, a time change or a schedule edit, so a missed alarm can't skip a window.
 */
class ScheduleScheduler(
    private val context: Context,
    private val database: AppDatabase,
    private val lockController: LockController,
    private val lockStarter: ProfileLockStarter,
    private val endScheduler: LockEndScheduler,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Device-protected, like the database, because sync() runs before the first unlock too. */
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * After a lock ends early during a scheduled window, this stops [sync] from starting it
     * again until that window is over. The next window runs normally.
     */
    suspend fun suppressActiveWindow() {
        val schedules = withContext(Dispatchers.IO) { database.scheduleDao().getEnabled() }
        val window = ScheduleCalculator.activeWindow(schedules, clock()) ?: return
        val endMs = window.end.toInstant().toEpochMilli()
        prefs.edit { putLong(KEY_SUPPRESSED_UNTIL, endMs) }
        Log.i(TAG, "Lock ended early; schedule window suppressed until ${window.end}")
    }

    /** Catches up on an active window, then arms the alarm for the next one. Call from a coroutine. */
    suspend fun sync() {
        val schedules = withContext(Dispatchers.IO) { database.scheduleDao().getEnabled() }
        val now = clock()

        val suppressedUntilMs = prefs.getLong(KEY_SUPPRESSED_UNTIL, 0L)
        ScheduleCalculator.activeWindow(schedules, now)
            ?.takeIf { it.end.toInstant().toEpochMilli() > suppressedUntilMs }
            ?.let { window ->
            val profile = withContext(Dispatchers.IO) { database.profileDao().getById(window.schedule.profileId) }
            if (profile != null) {
                withContext(Dispatchers.Main) { startIfNeeded(window, profile, now) }
            }
        }

        val next = ScheduleCalculator.nextStart(schedules, now)
        if (next == null) {
            alarmManager.cancel(alarmIntent())
            Log.i(TAG, "No upcoming schedule")
        } else {
            setAlarm(next.start.toInstant().toEpochMilli())
            Log.i(TAG, "Next schedule starts at ${next.start}")
        }
    }

    private fun startIfNeeded(
        window: ScheduleWindow,
        profile: LockProfile,
        now: ZonedDateTime,
    ) {
        val windowEndMs = window.end.toInstant().toEpochMilli()
        val current = lockController.state.value
        // Already locked until at least the window's end: leave it alone, so an allowed-app
        // pass isn't interrupted by a needless relock.
        if (current is LockState.Locked && current.session.endsAtMs >= windowEndMs) return

        val remainingMs = windowEndMs - now.toInstant().toEpochMilli()
        if (remainingMs <= 0) return
        lockStarter.start(
            profile = profile,
            durationMs = remainingMs,
            label = context.getString(R.string.schedule_lock_label, profile.name),
        )
        Log.i(TAG, "Started scheduled lock '${profile.name}' until ${window.end}")
    }

    private fun setAlarm(atMs: Long) {
        if (endScheduler.canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarmIntent())
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarmIntent())
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ScheduleAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "ScheduleScheduler"
        const val REQUEST_CODE = 1002
        const val PREFS_NAME = "schedule"
        const val KEY_SUPPRESSED_UNTIL = "suppressed_until_ms"
    }
}
