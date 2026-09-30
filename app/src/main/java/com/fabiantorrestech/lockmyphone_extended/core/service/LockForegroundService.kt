package com.fabiantorrestech.lockmyphone_extended.core.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.enforce.LockAccessibilityService
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.core.notify.LockNotifications

/**
 * Runs while a lock is active. It keeps the status notification up and listens for
 * USER_PRESENT (the device was unlocked), which can only be received by a receiver
 * registered at runtime.
 */
class LockForegroundService : Service() {

    private val container by lazy { LockApp.container(this) }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> container.lockController.onUserUnlocked()
                // Backstop: if the screen turns on without a keyguard (lock screen set to
                // "None", or SystemUI still starting after a boot), there's no USER_PRESENT.
                Intent.ACTION_SCREEN_ON -> container.lockController.enforceIfUnlocked(allowGate = true)
                Intent.ACTION_SCREEN_OFF -> container.lockController.onScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Must be EXPORTED: USER_PRESENT is sent by SystemUI (its own app UID, not the system
        // UID), so a NOT_EXPORTED receiver never gets it. This is safe, because USER_PRESENT is
        // a protected broadcast that only system components can send.
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = container.lockController.state.value
        if (state !is LockState.Locked) {
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            LockNotifications.ID_STATUS,
            container.notifications.buildStatus(state.session, passesAvailable = LockAccessibilityService.isRunning),
            foregroundServiceType(),
        )
        container.lockController.enforceIfUnlocked()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(unlockReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
}
