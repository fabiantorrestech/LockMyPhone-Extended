package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmMatchMode
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger

/** What identifies one ringing-alarm notification. */
data class AlarmFingerprint(
    val packageName: String,
    val channelId: String?,
    val notificationId: Int,
    val title: String?,
    val text: String?,
)

/** Pure matching rules for alarm triggers; no Android dependencies. */
object AlarmMatcher {

    /**
     * How a fingerprint learned from [learned] should be matched from now on.
     * - QRAlarm (any build) uses the alarm's own ID as a stable notification ID (see QRAlarm's
     *   AlarmService), so it's matched by ID.
     * - Other apps are matched by the notification title, the alarm's label. Google Clock, for
     *   example, uses one ID for every alarm, and its text contains the weekday.
     */
    fun chooseMatchMode(learned: AlarmFingerprint): AlarmMatchMode =
        if (QrAlarmUpcoming.isQrAlarm(learned.packageName) || learned.title.isNullOrBlank()) {
            AlarmMatchMode.NOTIFICATION_ID
        } else {
            AlarmMatchMode.TITLE
        }

    /**
     * The mode actually used. A QRAlarm trigger always matches by ID, even if it was saved by
     * title before QRAlarm Pro was recognised. Its notification title is the same for every alarm.
     */
    fun effectiveMatchMode(trigger: AlarmTrigger): AlarmMatchMode =
        if (QrAlarmUpcoming.isQrAlarm(trigger.packageName)) AlarmMatchMode.NOTIFICATION_ID else trigger.matchMode

    fun matches(trigger: AlarmTrigger, candidate: AlarmFingerprint): Boolean {
        if (trigger.packageName != candidate.packageName) return false
        if (trigger.channelId != null && trigger.channelId != candidate.channelId) return false
        return when (effectiveMatchMode(trigger)) {
            AlarmMatchMode.NOTIFICATION_ID -> trigger.notificationId == candidate.notificationId
            AlarmMatchMode.TITLE -> !trigger.title.isNullOrBlank() && trigger.title == candidate.title
        }
    }
}
