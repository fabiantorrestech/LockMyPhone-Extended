package com.fabiantorrestech.lockmyphone_extended.core.enforce

import android.content.Context
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemDetector
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfile
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfiles

/**
 * Decides which windows may be in front during an allowed-app pass without relocking:
 * core Android components, the device profile's system apps, the keyboards, accessibility
 * service overlays and this app. An accessibility app's own activities still relock.
 *
 * @param activeProfile the device profile in use (the user's choice or the detected one).
 */
class ForegroundPolicy(
    private val context: Context,
    private val oemDetector: OemDetector,
    private val activeProfile: () -> OemProfile,
) {

    /**
     * @param allowAssistantPopup the lock allows the Gemini / Assistant popup: its activity, and
     *   the Google app's non-activity windows (the popup's dialogs). The Google app's other
     *   activities (Search, Discover) still relock.
     */
    fun isAlwaysAllowed(packageName: String, className: String?, allowAssistantPopup: Boolean = false): Boolean {
        val profile = activeProfile()
        // An app switcher hosted inside an allowed package (e.g. SystemUI recents) still relocks.
        if (className != null && className in profile.recentsClassNames) return false
        return packageName == context.packageName ||
            packageName in OemProfiles.COMMON_ALLOWED ||
            packageName in profile.alwaysAllowedPackages ||
            packageName in oemDetector.inputMethodPackages() ||
            isAccessibilityOverlay(packageName, className) ||
            (allowAssistantPopup && isAssistantPopup(packageName, className))
    }

    private fun isAssistantPopup(packageName: String, className: String?): Boolean =
        OemProfiles.isAssistantPopupClass(packageName, className) ||
            (packageName == OemProfiles.ASSISTANT_PACKAGE && !oemDetector.isActivity(packageName, className))

    /**
     * A non-activity window from an enabled accessibility service, e.g. Grayscaler's filter drawn
     * over a chat. It sits on top of the real app, so the user hasn't left it.
     */
    private fun isAccessibilityOverlay(packageName: String, className: String?): Boolean =
        packageName in oemDetector.accessibilityServicePackages() &&
            !oemDetector.isActivity(packageName, className)

    /** Whether the window is one of the app's activities, as opposed to a dialog or popup. */
    fun isActivity(packageName: String, className: String?): Boolean =
        oemDetector.isActivity(packageName, className)

    fun isLauncher(packageName: String): Boolean =
        packageName in activeProfile().launcherPackages ||
            packageName == oemDetector.defaultLauncherPackage()
}
