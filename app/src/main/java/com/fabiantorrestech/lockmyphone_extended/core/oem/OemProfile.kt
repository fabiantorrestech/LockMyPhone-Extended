package com.fabiantorrestech.lockmyphone_extended.core.oem

/**
 * Manufacturer-specific packages the lock must know about.
 *
 * @param manufacturers lowercase `Build.MANUFACTURER` values this profile is picked for.
 * @param launcherPackages the home screen / app switcher. These are never allowed during a pass,
 *   and they're listed so the diagnostics screen can label them.
 * @param alwaysAllowedPackages system apps that may be in front during a pass without relocking:
 *   in-call screens, emergency / SOS apps, OEM system dialogs.
 * @param recentsClassNames app-switcher windows hosted inside an always-allowed package (e.g.
 *   SystemUI on some older ROMs). They relock even though their package is allowed.
 * @param verified true once its package names were confirmed on a real device.
 */
data class OemProfile(
    val id: String,
    val displayName: String,
    val manufacturers: Set<String>,
    val launcherPackages: Set<String>,
    val alwaysAllowedPackages: Set<String>,
    val recentsClassNames: Set<String> = emptySet(),
    val verified: Boolean = false,
)
