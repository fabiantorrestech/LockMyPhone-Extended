package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync

/** The daily check for alarm triggers whose alarm may have been deleted. */
class AlarmTriggerHealthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        launchAsync(container.appScope) { container.alarmHealthChecker.check() }
    }
}
