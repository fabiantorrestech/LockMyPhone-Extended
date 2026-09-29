package com.fabiantorrestech.lockmyphone_extended.core.oem

/**
 * The built-in device profiles. Only the Pixel profile is verified so far. Confirm the others with
 * the diagnostics screen on a real device, then set `verified = true`.
 */
object OemProfiles {

    /** Allowed on every device: core Android and AOSP system components. */
    val COMMON_ALLOWED: Set<String> = setOf(
        "android", // system dialogs
        "com.android.systemui", // notification shade, volume, keyguard, power menu
        "com.android.server.telecom", // call handling
        "com.android.phone", // emergency dialer, SIM dialogs
        "com.android.emergency", // emergency information
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    val PIXEL = OemProfile(
        id = "pixel",
        displayName = "Google Pixel",
        manufacturers = setOf("google"),
        launcherPackages = setOf("com.google.android.apps.nexuslauncher"),
        alwaysAllowedPackages = setOf(
            "com.google.android.dialer", // Phone + in-call screen
            "com.google.android.apps.safetyhub", // Personal Safety: Emergency SOS
        ),
        verified = true,
    )

    val SAMSUNG = OemProfile(
        id = "samsung",
        displayName = "Samsung Galaxy (One UI)",
        manufacturers = setOf("samsung"),
        launcherPackages = setOf("com.sec.android.app.launcher"),
        alwaysAllowedPackages = setOf(
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.app.telephonyui",
            "com.samsung.android.emergency", // Emergency SOS
            "com.sec.android.app.safetyassurance",
        ),
    )

    val NOTHING = OemProfile(
        id = "nothing",
        displayName = "Nothing Phone (Nothing Launcher)",
        manufacturers = setOf("nothing"),
        launcherPackages = setOf("com.nothing.launcher"),
        alwaysAllowedPackages = setOf(
            "com.google.android.dialer",
            "com.google.android.apps.safetyhub",
        ),
    )

    val GENERIC = OemProfile(
        id = "generic",
        displayName = "Other Android (AOSP launcher)",
        manufacturers = emptySet(),
        launcherPackages = setOf("com.android.launcher3"),
        alwaysAllowedPackages = setOf(
            "com.android.dialer",
            "com.android.incallui",
        ),
        recentsClassNames = setOf("com.android.systemui.recents.RecentsActivity"),
    )

    val ALL: List<OemProfile> = listOf(PIXEL, SAMSUNG, NOTHING, GENERIC)

    fun byId(id: String?): OemProfile? = ALL.firstOrNull { it.id == id }

    /** The profile matching [manufacturer] (`Build.MANUFACTURER`), or [GENERIC]. */
    fun forManufacturer(manufacturer: String): OemProfile =
        ALL.firstOrNull { manufacturer.lowercase() in it.manufacturers } ?: GENERIC
}
