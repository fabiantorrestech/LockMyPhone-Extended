package com.fabiantorrestech.lockmyphone_extended.core.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Schedules the alarm that ends a lock. If exact alarms aren't allowed, it falls back to an
 * inexact alarm. That's still safe, because every unlock also checks whether the lock has
 * expired.
 */
class LockEndScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(endsAtMs: Long) {
        val pendingIntent = endPendingIntent()
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMs, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMs, pendingIntent)
        }
    }

    fun cancel() {
        alarmManager.cancel(endPendingIntent())
    }

    /** False only on Android 12+ when the user revoked "Alarms & reminders" for this app. */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun endPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE_END,
        Intent(context, LockEndReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val REQUEST_CODE_END = 1001
    }
}
