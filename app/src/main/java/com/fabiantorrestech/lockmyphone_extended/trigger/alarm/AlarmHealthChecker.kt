package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.notify.TriggerWarningNotifier
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [AlarmTriggerHealth]'s rules over all alarm triggers. It notifies new problems, clears the
 * notifications of solved ones, and arms a daily check.
 */
class AlarmHealthChecker(
    private val context: Context,
    private val database: AppDatabase,
    private val health: AlarmTriggerHealth,
    private val timeStore: AlarmTimeStore,
    private val notifier: TriggerWarningNotifier,
) {

    /** The learned alarm's time, if known (for matching a re-created alarm and for display). */
    fun timeOf(trigger: AlarmTrigger): String? =
        trigger.notificationId?.let { timeStore.get(trigger.packageName, it) }

    suspend fun check() {
        val triggers = withContext(Dispatchers.IO) { database.alarmTriggerDao().getAll() }
        val newProblems = health.evaluate(triggers, AlarmWatchListener.isEnabled(context), ::timeOf)
        val byId = triggers.associateBy { it.id }
        newProblems.forEach { problem ->
            val trigger = byId[problem.triggerId] ?: return@forEach
            notifier.show(trigger.id, trigger.name, reasonText(context, problem))
        }
        val withProblems = health.problems.value.keys
        triggers.filter { it.id !in withProblems }.forEach { notifier.cancel(it.id) }
    }

    /** A daily check, so a silent alarm is noticed even if the app is never opened. */
    fun scheduleDaily() {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AlarmTriggerHealthReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_DAY,
            AlarmManager.INTERVAL_DAY,
            pendingIntent,
        )
    }

    companion object {
        private const val REQUEST_CODE = 1003

        /** One line explaining the problem, for the banner and the notification. */
        fun reasonText(context: Context, problem: AlarmTriggerProblem): String {
            val base = when (problem) {
                is AlarmTriggerProblem.Recreated -> context.getString(R.string.trigger_problem_recreated)
                is AlarmTriggerProblem.Silent -> context.getString(R.string.trigger_problem_silent, problem.days)
            }
            val suggestion = problem.suggestion ?: return base
            val time = suggestion.time ?: context.getString(R.string.alarm_trigger_unknown_time)
            return base + " " + context.getString(R.string.trigger_problem_suggestion, suggestion.alarmId, time)
        }
    }
}
