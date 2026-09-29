package com.fabiantorrestech.lockmyphone_extended.core.enforce

import android.content.Context
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemDetector
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfile
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfiles

/**
 * Decides which windows may be in front during an allowed-app pass without relocking:
 * core Android components, the device profile's system apps, the keyboards and this app.
 *
 * @param activeProfile the device profile in use (the user's choice or the detected one).
 */
class ForegroundPolicy(
    private val context: Context,
    private val oemDetector: OemDetector,
    private val activeProfile: () -> OemProfile,
) {

    fun isAlwaysAllowed(packageName: String, className: String?): Boolean {
        val profile = activeProfile()
        // An app switcher hosted inside an allowed package (e.g. SystemUI recents) still relocks.
        if (className != null && className in profile.recentsClassNames) return false
        return packageName == context.packageName ||
            packageName in OemProfiles.COMMON_ALLOWED ||
            packageName in profile.alwaysAllowedPackages ||
            packageName in oemDetector.inputMethodPackages()
    }

    fun isLauncher(packageName: String): Boolean =
        packageName in activeProfile().launcherPackages ||
            packageName == oemDetector.defaultLauncherPackage()
}
