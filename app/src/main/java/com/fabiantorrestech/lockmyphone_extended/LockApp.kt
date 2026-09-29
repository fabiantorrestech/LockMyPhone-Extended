package com.fabiantorrestech.lockmyphone_extended

import android.app.Application
import android.content.Context
import android.os.UserManager
import kotlinx.coroutines.launch

class LockApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
        container.triggerWarningNotifier.createChannel()
        container.alarmHealthChecker.scheduleDaily()
        // If the process was killed during a lock, bring enforcement back.
        container.lockController.resume()
        container.appScope.launch { container.scheduleScheduler.sync() }
        container.appScope.launch { container.alarmHealthChecker.check() }
        // Location triggers read credential-protected settings, so only once the user has unlocked.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            container.appScope.launch { container.locationTriggerManager.sync() }
        }
    }

    companion object {
        fun container(context: Context): AppContainer =
            (context.applicationContext as LockApp).container
    }
}
