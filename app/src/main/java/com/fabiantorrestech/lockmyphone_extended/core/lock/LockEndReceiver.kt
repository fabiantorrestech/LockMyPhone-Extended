package com.fabiantorrestech.lockmyphone_extended.core.lock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp

/** Fires when the lock's end alarm goes off. */
class LockEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LockApp.container(context).lockController.onEndAlarm()
    }
}
