package com.fabiantorrestech.lockmyphone_extended.core.enforce

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.WakeWatcher

/**
 * Reports which app is in front, so an allowed-app pass can be enforced. It only listens for
 * window-state changes and never reads window content. While it's bound it also hosts Morning
 * lock's [WakeWatcher], since it's the app's one component the system keeps running.
 */
class LockAccessibilityService : AccessibilityService() {

    private var wakeWatcher: WakeWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        val container = LockApp.container(this)
        container.lockController.updateNotifications()
        wakeWatcher = WakeWatcher(this, container.wakeSignalStore, container::onUnlockHeard).also { it.register() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // TEMPORARY probe for the quick-reply design (remove before shipping).
        android.util.Log.d(
            "ReplyProbe",
            "window $packageName/${event.className} keyguardLocked=${getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked}",
        )
        LockApp.container(this).lockController.onForegroundApp(packageName, event.className?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        wakeWatcher?.unregister()
        wakeWatcher = null
        instance = null
        isRunning = false
        LockApp.container(this).lockController.updateNotifications()
        return super.onUnbind(intent)
    }

    companion object {
        /** True while the system has the service bound, i.e. passes can be enforced. */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: LockAccessibilityService? = null

        /** Settings → Permissions → Revoke: the service turns itself off. False if it isn't running. */
        fun turnOff(): Boolean {
            val service = instance ?: return false
            service.disableSelf()
            return true
        }

        /** Whether the user has enabled the service in Settings, even if it isn't bound yet. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val self = android.content.ComponentName(context, LockAccessibilityService::class.java)
            return enabled.split(':').any { it.equals(self.flattenToString(), ignoreCase = true) }
        }
    }
}
