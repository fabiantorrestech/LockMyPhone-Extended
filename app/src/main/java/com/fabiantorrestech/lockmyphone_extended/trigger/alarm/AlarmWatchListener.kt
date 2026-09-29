package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches other apps' notifications to spot the moment a learned alarm is dismissed.
 * - A ringing alarm = a notification with CATEGORY_ALARM or a full-screen intent that can't be
 *   swiped away (ongoing, foreground service or no-clear). Google Clock's has no ongoing flag.
 * - Dismissed = a matching ringing notification is removed, and the same app posts **no other**
 *   notification from [QUIET_BEFORE_MS] before to [QUIET_AFTER_MS] after the removal. Alarm apps
 *   post a "snoozed" / "upcoming" / "missed alarm" notification then, and that means no lock.
 *
 * Only alarm apps with a trigger (or the one being learned) are looked at. Nothing is stored.
 */
class AlarmWatchListener : NotificationListenerService() {

    private val container by lazy { LockApp.container(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    /** Enabled triggers, kept current from the database. */
    @Volatile
    private var triggers: List<AlarmTrigger> = emptyList()

    /** Ringing notifications we're tracking: notification key → the triggers it matched. */
    private val ringing = mutableMapOf<String, List<AlarmTrigger>>()

    /** When each watched app last posted a (non-ringing) notification. */
    private val lastPostMs = mutableMapOf<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Record the times of QRAlarm alarms that are already announced.
        activeQrAlarmUpcoming()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = newScope
        newScope.launch {
            container.database.alarmTriggerDao().observeEnabled().collect { triggers = it }
        }
        Log.i(TAG, "Connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        scope?.cancel()
        scope = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        recordQrAlarmTime(sbn)?.let { onQrAlarmUpcomingPosted(packageName, it) }
        val learningFor = container.alarmLearning.waitingFor()
        val watched = packageName == learningFor || triggers.any { it.watches(packageName) }
        if (!watched) return

        if (!isRingingAlarm(sbn.notification)) {
            // Only snooze-like posts: an "upcoming alarm" notice after a dismissal isn't a snooze.
            if (AlarmKinds.isSnoozeSignal(packageName, sbn.notification.channelId)) {
                lastPostMs[packageName] = System.currentTimeMillis()
            }
            return
        }
        val fingerprint = fingerprintOf(sbn)
        if (packageName == learningFor) {
            // QRAlarm's ringing notification has no time in it, so note when it rang.
            if (QrAlarmUpcoming.isQrAlarm(packageName) && container.alarmTimeStore.get(packageName, fingerprint.notificationId) == null) {
                container.alarmTimeStore.put(packageName, fingerprint.notificationId, nowAsTime())
            }
            container.alarmLearning.onRingingAlarm(fingerprint)
            Log.i(TAG, "Ringing notification from $packageName while learning (id ${fingerprint.notificationId}, alarm=${AlarmKinds.isAlarm(fingerprint)})")
        }
        val matched = triggers.filter { it.matchesRinging(fingerprint) }
        if (matched.isNotEmpty()) {
            ringing[sbn.key] = matched
            // It rang, so it clearly still exists.
            matched.filter { it.kind == AlarmTriggerKind.SPECIFIC_ALARM }
                .forEach { container.alarmTriggerHealth.onSeen(it.id) }
            checkHealth()
        }
    }

    /** A QRAlarm upcoming notice: a linked alarm is alive; an unlinked one may be a re-created alarm. */
    private fun onQrAlarmUpcomingPosted(packageName: String, upcoming: QrAlarmUpcoming.Upcoming) {
        val linked = triggers.filter { it.packageName == packageName && it.notificationId == upcoming.alarmId }
        if (linked.isNotEmpty()) {
            linked.forEach { container.alarmTriggerHealth.onSeen(it.id) }
        } else {
            container.alarmTriggerHealth.onUnlinkedAlarm(
                packageName, upcoming.alarmId, upcoming.time, triggers, container.alarmHealthChecker::timeOf,
            )
        }
        checkHealth()
    }

    /**
     * A linked alarm's upcoming notice was removed. If the alarm doesn't ring or get re-posted within
     * [AlarmHealthRules.VANISH_CONFIRM_MS], it vanished: deleted, turned off, or moved far away.
     * That alone never warns; it only enables the re-creation clue.
     */
    private fun onQrAlarmUpcomingRemoved(sbn: StatusBarNotification) {
        val upcoming = QrAlarmUpcoming.parse(
            packageName = sbn.packageName,
            channelId = sbn.notification.channelId,
            notificationId = sbn.id,
            text = null,
        ) ?: return
        val linked = triggers.filter { it.packageName == sbn.packageName && it.notificationId == upcoming.alarmId }
        if (linked.isEmpty()) return
        val removedAtMs = System.currentTimeMillis()
        handler.postDelayed({
            linked.forEach { trigger ->
                val seenSince = (container.alarmTriggerHealth.lastSeenMs(trigger.id) ?: 0L) >= removedAtMs
                if (!seenSince) container.alarmTriggerHealth.onVanished(trigger.id)
            }
        }, AlarmHealthRules.VANISH_CONFIRM_MS)
    }

    private fun checkHealth() {
        scope?.launch { container.alarmHealthChecker.check() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        onQrAlarmUpcomingRemoved(sbn)
        val matched = ringing.remove(sbn.key) ?: return
        val removedAtMs = System.currentTimeMillis()
        val packageName = sbn.packageName
        // Wait to see whether the app posts a snooze / upcoming / missed notification.
        handler.postDelayed({
            val postedNearby = lastPostMs[packageName]?.let { it >= removedAtMs - QUIET_BEFORE_MS } ?: false
            if (postedNearby) {
                Log.i(TAG, "Alarm from $packageName removed, but the app posted again (snooze/missed); no lock")
                return@postDelayed
            }
            scope?.launch { matched.forEach { container.alarmTriggerFirer.onDismissed(it) } }
        }, QUIET_AFTER_MS)
    }

    /**
     * QRAlarm's currently announced upcoming alarms: the ones due within about 2 hours. Also
     * records their times.
     */
    fun activeQrAlarmUpcoming(): List<QrAlarmUpcoming.Upcoming> {
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            emptyArray() // not connected any more
        }
        return active.mapNotNull { recordQrAlarmTime(it) }.distinctBy { it.alarmId }.sortedBy { it.time }
    }

    /** If [sbn] is a QRAlarm upcoming-alarm notification, remembers that alarm's time. */
    private fun recordQrAlarmTime(sbn: StatusBarNotification): QrAlarmUpcoming.Upcoming? {
        val upcoming = QrAlarmUpcoming.parse(
            packageName = sbn.packageName,
            channelId = sbn.notification.channelId,
            notificationId = sbn.id,
            text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        ) ?: return null
        upcoming.time?.let { container.alarmTimeStore.put(sbn.packageName, upcoming.alarmId, it) }
        return upcoming
    }

    private fun nowAsTime(): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())

    private fun AlarmTrigger.watches(packageName: String): Boolean = when (kind) {
        AlarmTriggerKind.SPECIFIC_ALARM -> this.packageName == packageName
        AlarmTriggerKind.FIRST_ALARM -> packageName in firstAlarmPackages
    }

    /** A specific-alarm trigger matches its learned alarm; a first-alarm trigger any real alarm. */
    private fun AlarmTrigger.matchesRinging(candidate: AlarmFingerprint): Boolean = when (kind) {
        AlarmTriggerKind.SPECIFIC_ALARM -> AlarmMatcher.matches(this, candidate) && AlarmKinds.isAlarm(candidate)
        AlarmTriggerKind.FIRST_ALARM -> candidate.packageName in firstAlarmPackages && AlarmKinds.isAlarm(candidate)
    }

    private fun isRingingAlarm(notification: Notification): Boolean {
        val stickyFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR
        val sticky = notification.flags and stickyFlags != 0
        val alarmLike = notification.category == Notification.CATEGORY_ALARM || notification.fullScreenIntent != null
        return sticky && alarmLike
    }

    private fun fingerprintOf(sbn: StatusBarNotification): AlarmFingerprint {
        val extras = sbn.notification.extras
        return AlarmFingerprint(
            packageName = sbn.packageName,
            channelId = sbn.notification.channelId,
            notificationId = sbn.id,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    companion object {
        private const val TAG = "AlarmWatch"
        private const val QUIET_BEFORE_MS = 3_000L
        private const val QUIET_AFTER_MS = 5_000L

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, AlarmWatchListener::class.java)

        /** The connected listener, if any, so the editor can read QRAlarm's upcoming alarms. */
        @Volatile
        var instance: AlarmWatchListener? = null
            private set
    }
}
