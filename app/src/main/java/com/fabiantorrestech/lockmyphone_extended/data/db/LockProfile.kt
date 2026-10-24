package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.lock.LimitPolicy
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode

/**
 * A named lock configuration that triggers (manual, schedule, intent, NFC, alarm) start.
 *
 * @param defaultDurationSec used when the trigger doesn't give its own duration.
 * @param allowedPackages apps usable during the lock. The default dialer is always added on top.
 * @param nfcUnlockEnabled unused: NFC tags pick their own locks since DB version 17. Kept because
 *   SQLite on Android 11 can't drop a column without rebuilding the table.
 * @param nfcUnlockTagIds unused since DB version 17 (added in 16), for the same reason.
 * @param dndEnabled turn Do Not Disturb on during the lock. Added in DB version 2.
 * @param dndLevel how strict that Do Not Disturb is. Added in DB version 6.
 * @param icon an emoji shown beside the profile, or null for its first letter. Added in DB version 7.
 * @param sortOrder position in the profile list, set by dragging. Added in DB version 8.
 * @param grayscale turn the screen grayscale when the lock starts (Android 15+). Added in DB version 9.
 * @param unlockResumeMode what happens when the user unlocks again after the screen turned off
 *   in an allowed app. Added in DB version 5.
 * @param unlockChoiceSeconds how long the countdown screen waits for a pick with
 *   [UnlockResumeMode.SHOW_LOCK_SCREEN]. Added in DB version 5.
 * @param hiddenPackages allowed apps not shown in the lock-screen list (e.g. the camera); a subset
 *   of [allowedPackages]. Added in DB version 26.
 * @param managedBy the app that keeps this profile's apps in sync (ADR 0008), or null for the
 *   user's own. Its apps can't be edited here. Added in DB version 27.
 * @param lockStyle LOCKED (only allowed apps) or LIMITED (any app in short sessions with growing
 *   cooldowns; the allowed apps are exempt). Added with the limit* fields in DB version 28.
 * @param limitSessionMinutes a Limited session's length.
 * @param limitCooldownMinutes the first cooldown; each later one is [limitCooldownStepMinutes]
 *   longer, up to [limitMaxCooldownMinutes].
 * @param limitPeekSeconds a shorter session is a peek (no cooldown), [limitPeekCount] times a lock.
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
    @ColumnInfo(defaultValue = "") val nfcUnlockTagIds: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "") val hiddenPackages: Set<String> = emptySet(),
    val managedBy: String? = null,
    @ColumnInfo(defaultValue = "LOCKED") val lockStyle: LockStyle = LockStyle.LOCKED,
    @ColumnInfo(defaultValue = "5") val limitSessionMinutes: Int = 5,
    @ColumnInfo(defaultValue = "2") val limitCooldownMinutes: Int = 2,
    @ColumnInfo(defaultValue = "1") val limitCooldownStepMinutes: Int = 1,
    @ColumnInfo(defaultValue = "10") val limitMaxCooldownMinutes: Int = 10,
    @ColumnInfo(defaultValue = "30") val limitPeekSeconds: Int = 30,
    @ColumnInfo(defaultValue = "3") val limitPeekCount: Int = 3,
) {
    val bypassDelayMs: Long? get() = if (bypassEnabled) bypassWaitMinutes * 60_000L else null

    /** A Limited lock's sessions and cooldowns, or null for a normal one. */
    val limitPolicy: LimitPolicy?
        get() = if (lockStyle != LockStyle.LIMITED) null else LimitPolicy(
            sessionMs = limitSessionMinutes * 60_000L,
            firstCooldownMs = limitCooldownMinutes * 60_000L,
            cooldownStepMs = limitCooldownStepMinutes * 60_000L,
            maxCooldownMs = limitMaxCooldownMinutes * 60_000L,
            peekMs = limitPeekSeconds * 1_000L,
            peekCount = limitPeekCount,
        )
}

/** How strict a profile's lock is. */
enum class LockStyle {
    /** Only the allowed apps (and the phone) work. */
    LOCKED,

    /** Any app, in short sessions, each followed by a growing cooldown ([LimitPolicy]). */
    LIMITED,
}
