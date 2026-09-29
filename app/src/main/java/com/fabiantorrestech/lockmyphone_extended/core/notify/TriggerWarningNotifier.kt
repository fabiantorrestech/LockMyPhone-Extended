package com.fabiantorrestech.lockmyphone_extended.core.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.fabiantorrestech.lockmyphone_extended.MainActivity
import com.fabiantorrestech.lockmyphone_extended.R

/**
 * "'WORK - Alarm 1' may need re-linking": one notification per alarm trigger with a problem.
 * Tapping it opens the Automations tab.
 */
class TriggerWarningNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_trigger_warnings_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_trigger_warnings_description) }
        )
    }

    fun show(triggerId: Long, triggerName: String, reason: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(context.getString(R.string.trigger_warning_title, triggerName))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setContentIntent(openSchedulesIntent(triggerId))
            .build()
        notificationManager.notify(notificationId(triggerId), notification)
    }

    fun cancel(triggerId: Long) {
        notificationManager.cancel(notificationId(triggerId))
    }

    private fun openSchedulesIntent(triggerId: Long): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId(triggerId),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_AUTOMATIONS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationId(triggerId: Long) = ID_BASE + triggerId.toInt()

    private companion object {
        const val CHANNEL = "trigger_warnings"
        const val ID_BASE = 3000
    }
}
