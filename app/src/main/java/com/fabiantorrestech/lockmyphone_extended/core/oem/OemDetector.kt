package com.fabiantorrestech.lockmyphone_extended.core.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/** What the device reports about itself, for picking a profile and for diagnostics. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val defaultLauncher: String?,
    val defaultDialer: String?,
    val keyboards: Set<String>,
)

/** Reads the device's make/model and the default launcher, dialer and keyboards. */
class OemDetector(private val context: Context) {

    fun detectedProfile(): OemProfile = OemProfiles.forManufacturer(Build.MANUFACTURER)

    fun deviceInfo(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        defaultLauncher = defaultLauncherPackage(),
        defaultDialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage,
        keyboards = inputMethodPackages(),
    )

    fun defaultLauncherPackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            // "android" means no default is set yet (the chooser would be shown).
            ?.takeIf { it != "android" }
    }

    /** Keyboards open their own windows inside allowed apps, so they must never relock. */
    fun inputMethodPackages(): Set<String> =
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.map { it.packageName }
            ?.toSet()
            ?: emptySet()

    /**
     * Packages with an accessibility service the user has turned on. Their overlays (e.g. a
     * grayscale filter) report window changes over the app in front.
     */
    fun accessibilityServicePackages(): Set<String> {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return emptySet()
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
            .toSet()
    }

    /** Whether [className] is an activity of [packageName], as opposed to an overlay or popup window. */
    fun isActivity(packageName: String, className: String?): Boolean {
        if (className == null) return false
        return try {
            context.packageManager.getActivityInfo(ComponentName(packageName, className), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
