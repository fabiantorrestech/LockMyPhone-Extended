package com.fabiantorrestech.lockmyphone_extended.trigger.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the calendar triggers: starts a lock for a matching event that's happening now, arms an
 * exact alarm for the next one, and re-checks whenever the calendar changes (a content-URI job).
 * Each event occurrence starts a lock at most once, so ending it early doesn't restart it.
 * Only runs after the first unlock.
 */
class CalendarTriggerManager(
    private val context: Context,
    private val database: AppDatabase,
    private val lockStarter: ProfileLockStarter,
    /** Whether you're at a place, for calendar triggers set to "Only while at…". */
    private val isAtPlace: (Long) -> Boolean,
) {
    val reader = CalendarReader(context)
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val jobScheduler = context.getSystemService(JobScheduler::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alarmIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, CalendarReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Starts what's due now, arms the next start, and watches the calendar for changes. */
    suspend fun sync() {
        val triggers = withContext(Dispatchers.IO) { database.calendarTriggerDao().getEnabled() }
        if (triggers.isEmpty() || !reader.hasPermission()) {
            alarmManager.cancel(alarmIntent)
            jobScheduler.cancel(CalendarChangeJob.JOB_ID)
            return
        }
        val now = System.currentTimeMillis()
        val events = withContext(Dispatchers.IO) { reader.occurrences(now - LOOK_BACK_MS, now + LOOK_AHEAD_MS) }
        val windows = CalendarRules.windows(triggers, events)

        // An "Only while at…" window that isn't satisfied yet stays unhandled, so arriving later starts it.
        windows.filter { now >= it.startMs && now < it.endMs && !isHandled(it) }
            .filter { window -> window.trigger.requiredPlaceId?.let(isAtPlace) ?: true }
            .forEach { window ->
            markHandled(window)
            withContext(Dispatchers.Main) { startLock(window, window.endMs - now) }
        }
        pruneHandled(now)

        val next = windows.firstOrNull { it.startMs > now }
        if (next == null) {
            alarmManager.cancel(alarmIntent)
        } else if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.startMs, alarmIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.startMs, alarmIntent)
        }
        watchCalendarChanges()
    }

    /** The next event [trigger] would lock for, for the editor's preview. */
    suspend fun nextMatch(trigger: CalendarTrigger): CalendarWindow? = withContext(Dispatchers.IO) {
        if (!reader.hasPermission()) return@withContext null
        val now = System.currentTimeMillis()
        CalendarRules.windows(listOf(trigger.copy(enabled = true)), reader.occurrences(now - LOOK_BACK_MS, now + LOOK_AHEAD_MS))
            .firstOrNull { it.endMs > now }
    }

    /** Main thread: [ProfileLockStarter] needs it. */
    private suspend fun startLock(window: CalendarWindow, durationMs: Long) {
        val label = context.getString(R.string.calendar_lock_label, window.event.title.ifBlank { window.trigger.name })
        val profileId = window.trigger.profileId
        if (profileId == null) {
            lockStarter.startQuick(durationMs = durationMs, label = label)
        } else {
            val profile = withContext(Dispatchers.IO) { database.profileDao().getById(profileId) } ?: return
            lockStarter.start(profile, durationMs = durationMs, label = label)
        }
        Log.i(TAG, "'${window.trigger.name}' locked for '${window.event.title}'")
    }

    /** A one-shot job that runs when any calendar data changes; it's re-armed after each run. */
    fun watchCalendarChanges() {
        val job = JobInfo.Builder(CalendarChangeJob.JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)
            )
            .setTriggerContentUpdateDelay(CHANGE_SETTLE_MS)
            .setTriggerContentMaxDelay(CHANGE_MAX_DELAY_MS)
            .build()
        jobScheduler.schedule(job)
    }

    private fun handledKey(window: CalendarWindow) = "$HANDLED_PREFIX${window.trigger.id}_${window.event.instanceKey}"

    private fun isHandled(window: CalendarWindow) = prefs.contains(handledKey(window))

    /** Remember the occurrence until its window ends, so it only ever starts one lock. */
    private fun markHandled(window: CalendarWindow) = prefs.edit { putLong(handledKey(window), window.endMs) }

    private fun pruneHandled(now: Long) {
        val stale = prefs.all.filter { (key, value) -> key.startsWith(HANDLED_PREFIX) && (value as? Long ?: 0L) < now - LOOK_BACK_MS }
        if (stale.isNotEmpty()) prefs.edit { stale.keys.forEach(::remove) }
    }

    private companion object {
        const val TAG = "CalendarTriggers"
        const val PREFS_NAME = "calendar_triggers"
        const val HANDLED_PREFIX = "handled_"
        const val ALARM_REQUEST_CODE = 4101
        const val LOOK_BACK_MS = 24L * 60 * 60 * 1_000
        const val LOOK_AHEAD_MS = 8L * 24 * 60 * 60 * 1_000
        const val CHANGE_SETTLE_MS = 5_000L
        const val CHANGE_MAX_DELAY_MS = 60_000L
    }
}
