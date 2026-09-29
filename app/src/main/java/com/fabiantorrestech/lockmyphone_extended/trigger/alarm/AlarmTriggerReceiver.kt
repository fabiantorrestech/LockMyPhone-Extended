package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync

/** Fires when an alarm trigger's delay after the dismissal is over. */
class AlarmTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val triggerId = intent.getLongExtra(EXTRA_TRIGGER_ID, -1L)
        if (triggerId < 0) return
        val container = LockApp.container(context)
        launchAsync(container.appScope) { container.alarmTriggerFirer.fire(triggerId) }
    }

    companion object {
        const val EXTRA_TRIGGER_ID = "trigger_id"
    }
}
