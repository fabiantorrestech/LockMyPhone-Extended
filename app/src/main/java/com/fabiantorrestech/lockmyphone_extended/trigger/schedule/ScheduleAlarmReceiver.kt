package com.fabiantorrestech.lockmyphone_extended.trigger.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync

/** Fires at the start of the next scheduled window. */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        launchAsync(container.appScope) { container.scheduleScheduler.sync() }
    }
}
