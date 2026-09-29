package com.fabiantorrestech.lockmyphone_extended.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync

/**
 * Re-arms an active lock and the schedules after a reboot, an app update or a clock change.
 * LOCKED_BOOT_COMPLETED arrives before the first unlock, so the service is already listening
 * when the user unlocks.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> container.lockController.resume()
        }
        // Every action here can change which schedule window is next (or active).
        launchAsync(container.appScope) {
            container.scheduleScheduler.sync()
            // Geofences don't survive a reboot or an app update. Not before the first unlock:
            // the location triggers read credential-protected settings.
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                container.locationTriggerManager.sync()
            }
        }
    }
}
