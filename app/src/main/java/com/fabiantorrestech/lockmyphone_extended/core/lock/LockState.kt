package com.fabiantorrestech.lockmyphone_extended.core.lock

import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel

/** What happens when the user unlocks again after the screen turned off inside an allowed app. */
enum class UnlockResumeMode {
    /** Straight back into the app. */
    RESUME_APP,

    /** The countdown screen, which locks again after a few seconds unless the user picks an app. */
    SHOW_LOCK_SCREEN,
}

/**
 * One lock period. All times are wall-clock epoch millis, so a reboot can't reset them.
 *
 * @param allowedPackages apps that may be used during this lock. This is a snapshot taken at
 *   lock start, so changing the settings mid-lock has no effect.
 * @param passActive true while the user is inside an allowed app. It's granted from the
 *   lock-screen notification and ends on relock. Screen-off pauses it instead.
 * @param passPaused the screen turned off during a pass. Nothing is usable while it's paused;
 *   the next unlock resumes it only if an allowed app is still in front (see [resumeMode]).
 * @param bypassDelayMs how long an early unlock waits, or null if early unlock isn't allowed
 *   for this lock.
 * @param bypassEndsAtMs when a requested early unlock completes, or null if none is pending.
 * @param dndEnabled whether Do Not Disturb is on for this lock.
 * @param dndLevel how strict that Do Not Disturb is.
 * @param grayscale whether the lock turned the screen grayscale when it started.
 * @param resumeMode what a resumed pass does after an unlock.
 * @param unlockChoiceSeconds with [UnlockResumeMode.SHOW_LOCK_SCREEN], how long the countdown
 *   screen waits for the user to pick an app before locking again.
 */
data class LockSession(
    val label: String,
    val startedAtMs: Long,
    val endsAtMs: Long,
    val allowedPackages: Set<String> = emptySet(),
    val passActive: Boolean = false,
    val bypassDelayMs: Long? = null,
    val bypassEndsAtMs: Long? = null,
    val dndEnabled: Boolean = false,
    val dndLevel: DndLevel = DndLevel.PRIORITY,
    val grayscale: Boolean = false,
    val passPaused: Boolean = false,
    val resumeMode: UnlockResumeMode = UnlockResumeMode.RESUME_APP,
    val unlockChoiceSeconds: Int = DEFAULT_UNLOCK_CHOICE_SECONDS,
) {
    val bypassAllowed: Boolean get() = bypassDelayMs != null
    val bypassPending: Boolean get() = bypassEndsAtMs != null

    /** When the lock actually ends: the scheduled end, or a pending early unlock if sooner. */
    val effectiveEndMs: Long get() = minOf(endsAtMs, bypassEndsAtMs ?: Long.MAX_VALUE)

    companion object {
        const val DEFAULT_UNLOCK_CHOICE_SECONDS = 3
    }
}

sealed interface LockState {
    data object Idle : LockState
    data class Locked(val session: LockSession) : LockState
}
