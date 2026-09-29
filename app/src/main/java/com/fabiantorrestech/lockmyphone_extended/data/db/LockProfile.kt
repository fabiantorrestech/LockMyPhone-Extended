package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode

/**
 * A named lock configuration that triggers (manual, schedule, intent, NFC, alarm) start.
 *
 * @param defaultDurationSec used when the trigger doesn't give its own duration.
 * @param allowedPackages apps usable during the lock. The default dialer is always added on top.
 * @param nfcUnlockEnabled reserved for M7 (NFC gate screen).
 * @param dndEnabled turn Do Not Disturb on during the lock. Added in DB version 2.
 * @param dndLevel how strict that Do Not Disturb is. Added in DB version 6.
 * @param icon an emoji shown beside the profile, or null for its first letter. Added in DB version 7.
 * @param sortOrder position in the profile list, set by dragging. Added in DB version 8.
 * @param grayscale turn the screen grayscale when the lock starts (Android 15+). Added in DB version 9.
 * @param unlockResumeMode what happens when the user unlocks again after the screen turned off
 *   in an allowed app. Added in DB version 5.
 * @param unlockChoiceSeconds how long the countdown screen waits for a pick with
 *   [UnlockResumeMode.SHOW_LOCK_SCREEN]. Added in DB version 5.
 */
@Entity(tableName = "profiles", indices = [Index(value = ["name"], unique = true)])
data class LockProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // NOCASE so the unique index rejects "night" next to "Night", matching the intent API's lookup.
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val defaultDurationSec: Long,
    val allowedPackages: Set<String>,
    val bypassEnabled: Boolean,
    val bypassWaitMinutes: Int,
    val nfcUnlockEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val dndEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "RESUME_APP") val unlockResumeMode: UnlockResumeMode = UnlockResumeMode.RESUME_APP,
    @ColumnInfo(defaultValue = "3") val unlockChoiceSeconds: Int = LockSession.DEFAULT_UNLOCK_CHOICE_SECONDS,
    @ColumnInfo(defaultValue = "PRIORITY") val dndLevel: DndLevel = DndLevel.PRIORITY,
    val icon: String? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val grayscale: Boolean = false,
) {
    val bypassDelayMs: Long? get() = if (bypassEnabled) bypassWaitMinutes * 60_000L else null
}
