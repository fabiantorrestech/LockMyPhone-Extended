package com.fabiantorrestech.lockmyphone_extended.trigger

import com.fabiantorrestech.lockmyphone_extended.core.apps.AppCatalog
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult
import com.fabiantorrestech.lockmyphone_extended.data.SettingsStore
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile

/**
 * Starts a lock from a profile or from the Quick lock settings. Every trigger (manual,
 * schedule, intent, and later NFC and alarm) goes through here, so they all apply settings
 * the same way. Main thread only.
 */
class ProfileLockStarter(
    private val lockController: LockController,
    private val appCatalog: AppCatalog,
    private val settingsStore: () -> SettingsStore,
) {

    /** @param durationMs the lock length, or null for the profile's default. */
    fun start(profile: LockProfile, durationMs: Long?, label: String): StartResult =
        lockController.startLock(
            durationMs = durationMs ?: (profile.defaultDurationSec * 1_000),
            label = label,
            allowedPackages = profile.allowedPackages + setOfNotNull(appCatalog.defaultDialerPackage()),
            bypassDelayMs = profile.bypassDelayMs,
            dndEnabled = profile.dndEnabled,
            dndLevel = profile.dndLevel,
            grayscale = profile.grayscale,
            resumeMode = profile.unlockResumeMode,
            unlockChoiceSeconds = profile.unlockChoiceSeconds,
        )

    /**
     * Starts a Quick lock: the allowed apps and early-unlock setting from "Start a lock" in
     * Quick lock mode. It reads credential-protected settings, so it needs the user to have
     * unlocked since boot.
     *
     * @param durationMs the lock length, or null for Quick lock's last-used length.
     */
    fun startQuick(durationMs: Long?, label: String): StartResult {
        val settings = settingsStore()
        val bypass = settings.bypassDefaults.value
        return lockController.startLock(
            durationMs = durationMs ?: (settings.quickDurationSec.value * 1_000),
            label = label,
            allowedPackages = settings.allowedPackages.value + setOfNotNull(appCatalog.defaultDialerPackage()),
            bypassDelayMs = if (bypass.enabled) bypass.waitMinutes * 60_000L else null,
            dndEnabled = settings.quickDndEnabled.value,
            dndLevel = settings.quickDndLevel.value,
            grayscale = settings.quickGrayscale.value,
            resumeMode = settings.quickResumeMode.value,
            unlockChoiceSeconds = settings.quickChoiceSeconds.value,
        )
    }
}
