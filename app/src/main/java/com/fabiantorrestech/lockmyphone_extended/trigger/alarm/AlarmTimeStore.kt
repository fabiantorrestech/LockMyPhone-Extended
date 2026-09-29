package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers each alarm's time for display, e.g. "QRAlarm alarm #3 · rings 6:30 AM". It's kept
 * current from QRAlarm's upcoming-alarm notifications, and only used for labels, never for
 * matching.
 */
class AlarmTimeStore(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(packageName: String, alarmId: Int): String? = prefs.getString(key(packageName, alarmId), null)

    fun put(packageName: String, alarmId: Int, time: String) {
        if (get(packageName, alarmId) != time) prefs.edit { putString(key(packageName, alarmId), time) }
    }

    private fun key(packageName: String, alarmId: Int) = "$packageName#$alarmId"

    private companion object {
        const val PREFS_NAME = "alarm_times"
    }
}
