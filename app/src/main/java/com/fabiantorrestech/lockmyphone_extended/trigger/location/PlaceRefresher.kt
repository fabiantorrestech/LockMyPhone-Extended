package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.core.notify.LockNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What the Refresh button shows. */
sealed interface RefreshUi {
    data object Idle : RefreshUi
    data object Checking : RefreshUi

    /** The last check's result, e.g. "Still at Office · checked 10:42", for the lock started at [lockStartedAtMs]. */
    data class Done(val text: String, val lockStartedAtMs: Long?) : RefreshUi

    /** What to show for the lock that started at [lockStartedAtMs]: an older lock's result is hidden. */
    fun forLock(lockStartedAtMs: Long): RefreshUi =
        if (this is Done && this.lockStartedAtMs != lockStartedAtMs) Idle else this
}

/**
 * Runs a Refresh ([LocationTriggerManager.refreshNow]) for the lock screen, the allowed-apps list
 * and the lock notification, one at a time, and shows its progress and result in all three.
 */
class PlaceRefresher(
    private val context: Context,
    private val locationManager: () -> LocationTriggerManager,
    private val lockController: LockController,
    private val notifications: LockNotifications,
) {
    private val _state = MutableStateFlow<RefreshUi>(RefreshUi.Idle)
    val state: StateFlow<RefreshUi> = _state.asStateFlow()

    /** Checks now; does nothing if a check is already running. */
    suspend fun refresh() {
        if (_state.value == RefreshUi.Checking) return
        val lockStart = runningLockStart()
        _state.value = RefreshUi.Checking
        showInNotification(context.getString(R.string.refresh_checking))
        val result = runCatching { locationManager().refreshNow() }.getOrElse { RefreshResult.NotRefreshable }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        val text = when (result) {
            is RefreshResult.StillHere -> context.getString(R.string.refresh_still_here, result.placeName, time)
            is RefreshResult.Left -> context.getString(R.string.refresh_left, result.placeName)
            is RefreshResult.Unknown -> context.getString(R.string.refresh_unknown, result.placeName, time)
            RefreshResult.NotRefreshable -> context.getString(R.string.refresh_not_place)
        }
        _state.value = RefreshUi.Done(text, lockStart)
        showInNotification(text)
        // The lock (and its screen and notification) is gone, so say so here.
        if (result is RefreshResult.Left) Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    private fun runningLockStart(): Long? = (lockController.state.value as? LockState.Locked)?.session?.startedAtMs

    /** Shows [text] in the lock notification of the running lock (a new lock doesn't show it). */
    private fun showInNotification(text: String) {
        val startedAt = runningLockStart() ?: return
        notifications.refreshNote = LockNotifications.RefreshNote(startedAt, text)
        lockController.updateNotifications()
    }

    companion object {
        private const val REQUEST_CODE = 102

        /** The notification's Refresh button. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, PlaceRefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * The lock notification's Refresh button. The check takes a few seconds; the lock's foreground
 * service keeps the process alive meanwhile, and the result shows in the notification.
 */
class PlaceRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        container.appScope.launch { container.placeRefresher.refresh() }
    }
}
