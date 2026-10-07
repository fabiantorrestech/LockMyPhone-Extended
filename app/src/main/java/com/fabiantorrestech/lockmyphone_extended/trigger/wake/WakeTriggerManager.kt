package com.fabiantorrestech.lockmyphone_extended.trigger.wake

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.data.db.WakeTrigger
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Runs Morning lock: decides on each unlock ([WakeWatcher]) and each dismissed alarm
 * ([com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmWatchListener]) and starts the
 * lock. Each one locks at most once a day: the day is marked as soon as the moment happens, even
 * if a running lock refuses it (ADR 0005), or when an alarm lock fires that day (it stands down).
 * A moment that came while it was held for an edit is caught up when the hold ends, for what's
 * left of the lock. Main thread.
 */
class WakeTriggerManager(
    private val context: Context,
    private val database: AppDatabase,
    private val lockStarter: ProfileLockStarter,
    private val placeChecker: SleepPlaceChecker,
    /** Runs the late check's countdown. */
    private val scope: CoroutineScope,
    /** An alarm is ringing, or its dismissal is still being confirmed (snooze or not?). */
    private val alarmBusy: () -> Boolean,
) {
    // Device-protected like the signal store; holds the moments that came during an edit hold.
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Running late checks ("Just waking up?"), by Morning lock ID. */
    private val lateChecks = mutableMapOf<Long, Job>()

    /** When each running late check locks; moved once, when its screen is actually shown. */
    private val lateDeadlines = mutableMapOf<Long, Long>()
    private val lateRestarted = mutableSetOf<Long>()

    /**
     * @param screenOnsMs when the screen came on between the previous unlock and this one.
     */
    suspend fun onUnlock(previousUnlockMs: Long?, chargedMs: Long, screenOnsMs: List<Long>, nowMs: Long) {
        // An alarm being dismissed right now may be an alarm lock, which comes first: let it settle.
        var waited = 0
        while (alarmBusy() && waited < MAX_ALARM_WAITS) {
            delay(ALARM_SETTLE_MS)
            waited++
        }
        val zone = ZoneId.systemDefault()
        enabledTriggers().forEach { trigger ->
            val decision = when (val first = WakeRules.decideUnlock(trigger, nowMs, zone, previousUnlockMs, chargedMs, screenOnsMs)) {
                WakeDecision.CheckPlace -> checkPlaces(trigger)
                else -> first
            }
            val durationMs = WakeRules.remainingLockMs(trigger, nowMs, zone, screenOnsMs)
            if (decision is WakeDecision.Lock && trigger.lateCheck && WakeRules.minuteOfDay(nowMs, zone) >= trigger.lateCheckAfterMin) {
                startLateCheck(trigger, decision, durationMs, nowMs, zone)
            } else {
                act(trigger, decision, nowMs, zone, durationMs)
            }
        }
    }

    suspend fun onAlarmDismissed(packageName: String) {
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        enabledTriggers().forEach { trigger ->
            act(trigger, WakeRules.decideAlarm(trigger, nowMs, zone, packageName), nowMs, zone, WakeRules.lockMs(trigger))
        }
    }

    /**
     * The alarm lock [alarmTriggerId] fired: Morning locks that stand down for it are done for
     * today (same calendar day, whatever the window).
     */
    suspend fun onAlarmLockFired(alarmTriggerId: Long) {
        val nowMs = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val day = WakeRules.day(nowMs, zone)
        enabledTriggers()
            .filter { it.yieldToAlarms && (it.yieldAlarmIds.isEmpty() || alarmTriggerId in it.yieldAlarmIds) }
            .filter { it.daysMask and LockSchedule.dayBit(day.dayOfWeek) != 0 && it.lastFiredDay != day.toString() }
            .forEach { trigger ->
                markFired(trigger.id, nowMs, zone)
                lateChecks.remove(trigger.id)?.cancel()
                Log.i(TAG, "'${trigger.name}': an alarm lock fired today; standing down")
            }
    }

    /** The alarm apps any enabled Morning lock follows, for the notification listener. */
    fun alarmPackages(triggers: List<WakeTrigger>): Set<String> =
        triggers.filter { it.alarmFirst }.flatMap { it.alarmPackages }.toSet()

    /** [triggerId]'s edit hold ended: start the lock it missed, for what's left of it. */
    suspend fun catchUp(triggerId: Long) {
        val untilMs = prefs.getLong(pendingKey(triggerId), 0L)
        prefs.edit { remove(pendingKey(triggerId)) }
        val remainingMs = untilMs - System.currentTimeMillis()
        if (remainingMs <= 0) return
        val trigger = withContext(Dispatchers.IO) { database.wakeTriggerDao().getById(triggerId) } ?: return
        if (!trigger.enabled) return
        val result = startLock(trigger, remainingMs)
        Log.i(TAG, "'${trigger.name}': catching up after the hold; $result")
        if (result is StartResult.Held) {
            prefs.edit { putLong(pendingKey(triggerId), untilMs) }
        } else {
            markFired(triggerId, System.currentTimeMillis(), ZoneId.systemDefault())
        }
    }

    /** Whether you're at one of [trigger]'s sleep places. */
    suspend fun checkPlaces(trigger: WakeTrigger): WakeDecision {
        val places = withContext(Dispatchers.IO) { database.locationTriggerDao().getAll() }
            .filter { it.id in trigger.sleepPlaceIds }
        val match = placeChecker.check(places)
        return if (match == null) WakeDecision.Skip(WakeSkip.NOT_SLEEPING) else WakeDecision.Lock(match.proof)
    }

    /**
     * The late check's screen is on show: its countdown starts now (once), so a slow start can't
     * eat the time needed to hold "been up a while". Returns when it will lock, or null if it's over.
     */
    fun onLateCheckShown(triggerId: Long): Long? {
        if (triggerId !in lateChecks) return null
        if (lateRestarted.add(triggerId)) lateDeadlines[triggerId] = System.currentTimeMillis() + LATE_CHECK_MS
        return lateDeadlines[triggerId]
    }

    /** "Been up a while" held on the late check: no lock today. */
    suspend fun skipLateCheck(triggerId: Long) {
        val job = lateChecks.remove(triggerId) ?: return
        lateDeadlines.remove(triggerId)
        lateRestarted.remove(triggerId)
        job.cancel()
        markFired(triggerId, System.currentTimeMillis(), ZoneId.systemDefault())
        Log.i(TAG, "Morning lock $triggerId: late check skipped (been up a while)")
    }

    /**
     * A late lock is due: ask "Just waking up?" for [LATE_CHECK_MS], then lock unless skipped.
     * The countdown runs here, so leaving the question still locks.
     */
    private fun startLateCheck(trigger: WakeTrigger, decision: WakeDecision.Lock, durationMs: Long, nowMs: Long, zone: ZoneId) {
        if (trigger.id in lateChecks) return
        lateDeadlines[trigger.id] = nowMs + LATE_CHECK_MS
        lateChecks[trigger.id] = scope.launch {
            // The deadline can move once, when the screen is shown (onLateCheckShown).
            while (System.currentTimeMillis() < (lateDeadlines[trigger.id] ?: 0L)) delay(250)
            lateChecks.remove(trigger.id)
            lateDeadlines.remove(trigger.id)
            lateRestarted.remove(trigger.id)
            act(trigger, decision, nowMs, zone, durationMs - (System.currentTimeMillis() - nowMs))
        }
        context.startActivity(
            Intent(context, WakeCheckActivity::class.java)
                .putExtra(WakeCheckActivity.EXTRA_TRIGGER_ID, trigger.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Log.i(TAG, "'${trigger.name}': late wake-up; asking first")
    }

    private suspend fun act(trigger: WakeTrigger, decision: WakeDecision, nowMs: Long, zone: ZoneId, durationMs: Long) {
        if (decision !is WakeDecision.Lock) {
            Log.i(TAG, "'${trigger.name}': no lock ($decision)")
            return
        }
        // It may have stood down for an alarm lock meanwhile (the late check's countdown).
        val fresh = withContext(Dispatchers.IO) { database.wakeTriggerDao().getById(trigger.id) } ?: return
        if (fresh.lastFiredDay == WakeRules.day(nowMs, zone).toString()) {
            Log.i(TAG, "'${trigger.name}': already done today")
            return
        }
        val result = startLock(trigger, durationMs)
        Log.i(TAG, "'${trigger.name}': woke up (${decision.proof}); $result")
        if (result is StartResult.Held) {
            // Held for an edit: today's lock starts when the hold ends, for what's left of it.
            prefs.edit { putLong(pendingKey(trigger.id), System.currentTimeMillis() + durationMs) }
        } else {
            // Once a day, even if a running lock refused this one (ADR 0005).
            markFired(trigger.id, nowMs, zone)
        }
    }

    private suspend fun markFired(triggerId: Long, nowMs: Long, zone: ZoneId) = withContext(Dispatchers.IO) {
        database.wakeTriggerDao().setLastFiredDay(triggerId, WakeRules.day(nowMs, zone).toString())
    }

    private suspend fun startLock(trigger: WakeTrigger, durationMs: Long): StartResult? {
        val profileId = trigger.profileId
        // A stronger or equal lock running means this one is skipped (ADR 0005).
        return if (profileId == null) {
            lockStarter.startQuick(durationMs = durationMs, label = trigger.name, kind = LockKind.WAKE, ownerId = trigger.id)
        } else {
            val profile = withContext(Dispatchers.IO) { database.profileDao().getById(profileId) } ?: return null
            lockStarter.start(profile, durationMs = durationMs, label = trigger.name, kind = LockKind.WAKE, ownerId = trigger.id)
        }
    }

    private suspend fun enabledTriggers(): List<WakeTrigger> =
        withContext(Dispatchers.IO) { database.wakeTriggerDao().getEnabled() }

    private fun pendingKey(id: Long) = "pending_until_$id"

    companion object {
        private const val TAG = "WakeTriggers"
        private const val PREFS_NAME = "wake_triggers"

        /** How long the late check waits before locking, from when its screen shows. */
        const val LATE_CHECK_MS = 15_000L

        /** How long "I've been up a while" must be held. */
        const val LATE_SKIP_HOLD_MS = 5_000L

        private const val ALARM_SETTLE_MS = 3_000L
        private const val MAX_ALARM_WAITS = 5
    }
}
