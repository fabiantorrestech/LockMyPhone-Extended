package com.fabiantorrestech.lockmyphone_extended.bypass

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp

/**
 * Handles the status notification's "Unlock early" and "Cancel early unlock" buttons. They're
 * broadcasts (not activities), so they work from the lock screen without unlocking first.
 */
class BypassActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val controller = LockApp.container(context).lockController
        when (intent.action) {
            ACTION_REQUEST -> controller.requestBypass()
            ACTION_CANCEL -> controller.cancelBypass()
        }
    }

    companion object {
        private const val ACTION_REQUEST = "com.fabiantorrestech.lockmyphone_extended.bypass.REQUEST"
        private const val ACTION_CANCEL = "com.fabiantorrestech.lockmyphone_extended.bypass.CANCEL"

        fun requestIntent(context: Context): PendingIntent = pendingIntent(context, ACTION_REQUEST, 200)

        fun cancelIntent(context: Context): PendingIntent = pendingIntent(context, ACTION_CANCEL, 201)

        private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, BypassActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
