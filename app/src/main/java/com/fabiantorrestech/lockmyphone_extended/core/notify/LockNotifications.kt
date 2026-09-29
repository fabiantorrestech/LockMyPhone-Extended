package com.fabiantorrestech.lockmyphone_extended.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fabiantorrestech.lockmyphone_extended.MainActivity
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.bypass.BypassActionReceiver
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppCatalog
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.pass.PassActivity
import java.text.DateFormat
import java.util.Date

/**
 * Builds the single lock notification: countdown, Phone, Allowed apps and early unlock. It's
 * the foreground service's notification.
 */
class LockNotifications(
    private val context: Context,
    private val appCatalog: AppCatalog,
) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    /**
     * The channel is IMPORTANCE_DEFAULT with no sound or vibration. By default Android hides
     * silent (IMPORTANCE_LOW) notifications on the lock screen, and the lock screen is exactly
     * where this one must be seen.
     */
    fun createChannels() {
        OBSOLETE_CHANNELS.forEach(notificationManager::deleteNotificationChannel)
        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_status_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(status)
    }

    /**
     * The ongoing lock notification. The system updates its countdown timer by itself.
     * - It's colorized, which Android ranks at the top for a foreground service.
     * - Buttons (at most three): Phone, Allowed apps… and the early-unlock button.
     * - Tapping it opens the allowed-apps picker.
     *
     * @param passesAvailable false while the accessibility service isn't bound. Allowed apps
     *   can't be enforced then, so the notification says so instead of offering them.
     */
    fun buildStatus(session: LockSession, passesAvailable: Boolean): Notification {
        val endMs = session.effectiveEndMs
        val endTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endMs))
        val title = if (session.bypassPending) {
            context.getString(R.string.notif_bypass_pending_title, endTime)
        } else {
            context.getString(R.string.notif_status_title, endTime)
        }
        val text = if (passesAvailable) session.label else context.getString(R.string.notif_passes_unavailable)
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(endMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColorized(true)
            .setColor(ContextCompat.getColor(context, R.color.lock_accent))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .setContentIntent(
                if (passesAvailable) activityIntent(REQUEST_PICKER, PassActivity.pickerIntent(context)) else openAppIntent()
            )

        if (passesAvailable) {
            builder.addAction(
                0,
                context.getString(R.string.notif_action_phone),
                activityIntent(REQUEST_DIAL, PassActivity.dialIntent(context)),
            )
            val dialer = appCatalog.defaultDialerPackage()
            if (session.allowedPackages.any { it != dialer }) {
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_allowed_apps),
                    activityIntent(REQUEST_PICKER, PassActivity.pickerIntent(context)),
                )
            }
        }

        val delayMs = session.bypassDelayMs
        when {
            session.bypassPending -> builder
                .addAction(
                    lockScreenAction(
                        context.getString(R.string.notif_action_cancel_bypass),
                        BypassActionReceiver.cancelIntent(context),
                    )
                )
                .setShortCriticalText(context.getString(R.string.notif_bypass_chip))
            delayMs != null -> builder.addAction(
                lockScreenAction(
                    context.getString(R.string.notif_action_request_bypass, formatWait(delayMs)),
                    BypassActionReceiver.requestIntent(context),
                )
            )
        }
        return builder.build()
    }

    /** Re-posts the lock notification (same ID as the foreground service's) after a change. */
    fun updateStatus(session: LockSession, passesAvailable: Boolean) {
        notificationManager.notify(ID_STATUS, buildStatus(session, passesAvailable))
    }

    /** A button that runs straight from the lock screen, without asking to unlock (API 31+). */
    private fun lockScreenAction(title: String, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, title, intent)
            .setAuthenticationRequired(false)
            .build()

    /** "10 min" or "1 min 30 s". Whole minutes are the common case. */
    private fun formatWait(delayMs: Long): String {
        val totalSeconds = delayMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (seconds == 0L) {
            context.getString(R.string.wait_minutes, minutes)
        } else {
            context.getString(R.string.wait_minutes_seconds, minutes, seconds)
        }
    }

    private fun activityIntent(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppIntent(): PendingIntent = activityIntent(REQUEST_OPEN_APP, Intent(context, MainActivity::class.java))

    companion object {
        // Channel importance can't be raised after creation, so the ID was bumped.
        const val CHANNEL_STATUS = "lock_status_v2"
        const val ID_STATUS = 1

        /** Earlier channels, deleted on start: the pre-v2 ones and the merged-away quick-launch channel. */
        private val OBSOLETE_CHANNELS = listOf("lock_status", "quick_launch", "quick_launch_v2")
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_PICKER = 100
        private const val REQUEST_DIAL = 101
    }
}
