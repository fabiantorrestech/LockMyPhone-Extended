package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

/**
 * QRAlarm-specific knowledge, from its source (github.com/sweakpl/qralarm-android). It's pure,
 * with no Android dependencies.
 * - The ringing notification's ID is the alarm's ID.
 * - About 2 hours before an alarm (or right away if the alarm is set within 2 hours), QRAlarm
 *   posts an "upcoming alarm" notification. Its ID is `Int.MAX_VALUE - alarmId`, and its text
 *   contains the alarm's time.
 *
 * Together these let an alarm be picked, and shown with its time, without it ringing.
 */
object QrAlarmUpcoming {

    const val PACKAGE = "com.sweak.qralarm"

    /**
     * QRAlarm and its other builds, e.g. QRAlarm Pro (`com.sweak.qralarm.pro`). Pro was checked on
     * a Pixel 7 and uses the same channels and ID scheme.
     */
    fun isQrAlarm(packageName: String): Boolean =
        packageName == PACKAGE || packageName.startsWith("$PACKAGE.")
    const val RINGING_CHANNEL = "QRAlarmNotificationChannelId"
    const val UPCOMING_CHANNEL = "QRAlarmSetIndicationNotificationChannelId"

    /** QRAlarm's alarm IDs are small database row IDs; anything larger isn't an alarm notification. */
    private const val MAX_PLAUSIBLE_ALARM_ID = 10_000_000

    /**
     * Times like "6:30", "06:30 AM", "6:30 p.m." or "18:30". Android puts a (narrow) no-break
     * space before AM/PM, so those count as spaces.
     */
    private val TIME_REGEX = Regex("""\b\d{1,2}:\d{2}(?:[\s\u00A0\u202F]?[AaPp]\.?\s?[Mm]\.?)?""")

    data class Upcoming(val alarmId: Int, val time: String?)

    /**
     * Reads a QRAlarm "upcoming alarm" notification. It returns null for anything else,
     * e.g. the "missed alarm" notification, which uses the same channel with a small fixed ID.
     */
    fun parse(packageName: String, channelId: String?, notificationId: Int, text: String?): Upcoming? {
        if (!isQrAlarm(packageName) || channelId != UPCOMING_CHANNEL) return null
        val alarmId = Int.MAX_VALUE - notificationId
        if (alarmId <= 0 || alarmId > MAX_PLAUSIBLE_ALARM_ID) return null
        return Upcoming(alarmId, text?.let { TIME_REGEX.find(it)?.value?.trim() })
    }
}
