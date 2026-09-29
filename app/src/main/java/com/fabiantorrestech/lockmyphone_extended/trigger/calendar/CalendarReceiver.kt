package com.fabiantorrestech.lockmyphone_extended.trigger.calendar

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync
import kotlinx.coroutines.launch

/** The exact alarm at a matching event's start. */
class CalendarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        launchAsync(container.appScope) { container.calendarTriggerManager.sync() }
    }
}

/** Runs when calendar data changes (an event added, moved or deleted), then re-arms itself. */
class CalendarChangeJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val container = LockApp.container(this)
        container.appScope.launch {
            try {
                // sync() also schedules this job again for the next change.
                container.calendarTriggerManager.sync()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val JOB_ID = 4102
    }
}
