package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

/**
 * Tells real alarms apart from timers and stopwatches, and snoozes apart from other posts, per
 * alarm app. Pure logic: it only looks at a notification's package, channel and text.
 */
object AlarmKinds {

    const val GOOGLE_CLOCK = "com.google.android.deskclock"

    /** Google Clock rings alarms on this channel; running timers use "Timers v2", stopwatch "Stopwatch v2". */
    private const val GOOGLE_CLOCK_ALARM_CHANNEL = "Firing"

    /**
     * Google Clock's fixed notification IDs, seen on a Pixel 7: a ringing alarm and a ringing
     * (expired) timer. Both carry the alarm category, so the ID is what tells them apart.
     */
    private const val GOOGLE_CLOCK_ALARM_ID = 2147483645
    private const val GOOGLE_CLOCK_TIMER_ID = 2147483640

    private val TIMER_WORDS = listOf("timer", "time's up", "time’s up", "stopwatch")

    /**
     * Whether a ringing notification is an alarm (not a timer). Used by "first alarm of the day"
     * triggers, which accept any alarm from the chosen apps.
     */
    fun isAlarm(candidate: AlarmFingerprint): Boolean {
        if (looksLikeTimer(candidate)) return false
        return when {
            QrAlarmUpcoming.isQrAlarm(candidate.packageName) -> candidate.channelId == QrAlarmUpcoming.RINGING_CHANNEL
            candidate.packageName == GOOGLE_CLOCK -> when (candidate.notificationId) {
                GOOGLE_CLOCK_TIMER_ID -> false
                GOOGLE_CLOCK_ALARM_ID -> true
                else -> candidate.channelId == GOOGLE_CLOCK_ALARM_CHANNEL
            }
            else -> true
        }
    }

    /**
     * Whether a non-ringing post from an alarm app, right around a dismissal, means "snoozed".
     * Google Clock and QRAlarm also post an "upcoming alarm" notice after a dismissal when
     * another alarm is due soon; that must not be mistaken for a snooze.
     */
    fun isSnoozeSignal(packageName: String, channelId: String?): Boolean = when {
        packageName == GOOGLE_CLOCK -> channelId?.contains("Snooze", ignoreCase = true) == true
        QrAlarmUpcoming.isQrAlarm(packageName) -> channelId != QrAlarmUpcoming.UPCOMING_CHANNEL
        // Unknown apps: any post counts, which is the safe side (no lock rather than a wrong one).
        else -> true
    }

    private fun looksLikeTimer(candidate: AlarmFingerprint): Boolean {
        val haystack = listOfNotNull(candidate.channelId, candidate.title, candidate.text).joinToString(" ")
        return TIMER_WORDS.any { haystack.contains(it, ignoreCase = true) }
    }
}
