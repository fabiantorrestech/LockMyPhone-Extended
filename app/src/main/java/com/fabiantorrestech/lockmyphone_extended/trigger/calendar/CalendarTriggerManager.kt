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
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.trigger.ConflictRules
import com.fabiantorrestech.lockmyphone_extended.trigger.TimeSpan
import com.fabiantorrestech.lockmyphone_extended.trigger.TimedCommitment
import java.time.ZonedDateTime


/** A calendar trigger's lock that clashes with [otherName] in the coming week. */
data class CalendarClash(val window: CalendarWindow, val otherName: String)

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
    /** Whether a calendar trigger is being edited or just changed; its event waits, unhandled, until it isn't. */
    private val isHeld: (Long) -> Boolean,
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
            // A held trigger's event stays unhandled, so the catch-up when the wait ends starts it.
            .filterNot { window -> isHeld(window.trigger.id) }
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

    /**
     * For each of [targets], its first lock in the coming week that overlaps a schedule, a
     * place's time slot or another calendar trigger's lock. Only one lock runs at a time, so the
     * one that starts first runs and the other is skipped (ADR 0005).
     *
     * @param others all saved calendar triggers (a target isn't compared with itself).
     * @param commitments the schedules and time-slot places.
     */
    suspend fun clashes(
        targets: List<CalendarTrigger>,
        others: List<CalendarTrigger>,
        commitments: List<TimedCommitment>,
    ): Map<Long, CalendarClash> = withContext(Dispatchers.IO) {
        if (!reader.hasPermission() || targets.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        val events = reader.occurrences(now - LOOK_BACK_MS, now + CLASH_LOOK_AHEAD_MS)
        val timed = commitments.map { it.name to ConflictRules.spansBetween(it, ZonedDateTime.now(), CLASH_LOOK_AHEAD_DAYS) }
        val otherWindows = CalendarRules.windows(others.filter { it.enabled }, events)
        targets.mapNotNull { target ->
            val rivals = timed + otherWindows.filter { it.trigger.id != target.id }
                .groupBy { it.trigger.name }
                .map { (name, windows) -> name to windows.map { TimeSpan(it.startMs, it.endMs) } }
            CalendarRules.windows(listOf(target.copy(enabled = true)), events)
                .filter { it.endMs > now }
                .firstNotNullOfOrNull { window ->
                    ConflictRules.firstSpanClash(TimeSpan(window.startMs, window.endMs), rivals)?.let { CalendarClash(window, it) }
                }
                ?.let { target.id to it }
        }.toMap()
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
        // A stronger or equal lock running means this event is skipped (ADR 0005).
        val result = if (profileId == null) {
            lockStarter.startQuick(durationMs = durationMs, label = label, kind = LockKind.CALENDAR, ownerId = window.trigger.id)
        } else {
            val profile = withContext(Dispatchers.IO) { database.profileDao().getById(profileId) } ?: return
            lockStarter.start(profile, durationMs = durationMs, label = label, kind = LockKind.CALENDAR, ownerId = window.trigger.id)
        }
        Log.i(TAG, "'${window.trigger.name}' for '${window.event.title}': $result")
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

        /** How far ahead clashes with other commitments are predicted. */
        const val CLASH_LOOK_AHEAD_DAYS = 7
        const val CLASH_LOOK_AHEAD_MS = CLASH_LOOK_AHEAD_DAYS * 24L * 60 * 60 * 1_000
        const val CHANGE_SETTLE_MS = 5_000L
        const val CHANGE_MAX_DELAY_MS = 60_000L
    }
}
