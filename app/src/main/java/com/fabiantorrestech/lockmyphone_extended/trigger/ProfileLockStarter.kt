package com.fabiantorrestech.lockmyphone_extended.trigger

import com.fabiantorrestech.lockmyphone_extended.core.apps.AppCatalog
import com.fabiantorrestech.lockmyphone_extended.core.lock.GlobalLockOptions
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSource
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult
import com.fabiantorrestech.lockmyphone_extended.data.SettingsStore
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile

/**
 * Starts a lock from a profile or from the Quick lock settings. Every trigger (manual,
 * schedule, intent, NFC and alarm) goes through here, so they all apply settings
 * the same way. An automation that's being edited or just changed is held ([AutomationHolds]).
 * Main thread only.
 */
class ProfileLockStarter(
    private val lockController: LockController,
    private val appCatalog: AppCatalog,
    private val holds: AutomationHolds,
    private val settingsStore: () -> SettingsStore,
    private val globalOptions: () -> GlobalLockOptions,
) {

    /**
     * @param durationMs the lock length, or null for the profile's default.
     * @param kind what's starting it, which decides whether it may replace a running lock.
     * @param ownerId the automation or tag starting it, if any.
     */
    fun start(profile: LockProfile, durationMs: Long?, label: String, kind: LockKind, ownerId: Long? = null): StartResult {
        heldResult(kind, ownerId)?.let { return it }
        return lockController.startLock(
            durationMs = durationMs ?: (profile.defaultDurationSec * 1_000),
            label = label,
            allowedPackages = profile.allowedPackages + setOfNotNull(appCatalog.defaultDialerPackage()),
            bypassDelayMs = profile.bypassDelayMs,
            dndEnabled = profile.dndEnabled,
            dndLevel = profile.dndLevel,
            grayscale = profile.grayscale,
            resumeMode = profile.unlockResumeMode,
            unlockChoiceSeconds = profile.unlockChoiceSeconds,
            source = LockSource(kind, profile.id.toString(), ownerId, profile.name),
            hiddenPackages = profile.hiddenPackages,
            options = globalOptions(),
        )
    }

    /**
     * Starts a Quick lock: the allowed apps and early-unlock setting from "Start a lock" in
     * Quick lock mode. It reads credential-protected settings, so it needs the user to have
     * unlocked since boot.
     *
     * @param durationMs the lock length, or null for Quick lock's last-used length.
     * @param kind what's starting it, which decides whether it may replace a running lock.
     * @param ownerId the automation or tag starting it, if any.
     */
    fun startQuick(durationMs: Long?, label: String, kind: LockKind, ownerId: Long? = null): StartResult {
        heldResult(kind, ownerId)?.let { return it }
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
            source = LockSource(kind, QUICK_LOCK_ID, ownerId),
            hiddenPackages = settings.quickHiddenPackages.value,
            options = globalOptions(),
        )
    }

    /** [StartResult.Held] when the automation [ownerId] may not start a lock right now. */
    private fun heldResult(kind: LockKind, ownerId: Long?): StartResult? {
        val key = AutomationKey(kind, ownerId ?: return null)
        return if (holds.isHeld(key)) StartResult.Held(holds.cooldownEnd(key)) else null
    }
}
