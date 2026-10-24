package com.fabiantorrestech.lockmyphone_extended.core.lock

import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel

/** What happens when the user unlocks again after the screen turned off inside an allowed app. */
enum class UnlockResumeMode {
    /** Straight back into the app. */
    RESUME_APP,

    /** The countdown screen, which locks again after a few seconds unless the user picks an app. */
    SHOW_LOCK_SCREEN,
}

/** What Home (or the app switcher) does during a pass. */
enum class HomeAction {
    /** Opens the allowed-apps list again. */
    ALLOWED_APPS,

    /** Opens the countdown screen. */
    COUNTDOWN,

    /** Locks the phone (the behaviour before M24). */
    LOCK,
}

/**
 * The global "During a lock" settings, saved into each lock when it starts. Before the first
 * unlock after a boot the settings can't be read, so the defaults apply.
 */
data class GlobalLockOptions(
    val assistantPopupAllowed: Boolean = false,
    val homeAction: HomeAction = HomeAction.ALLOWED_APPS,
    val unlockIntoAllowedApp: Boolean = true,
)

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
 * @param source where this lock came from: its kind (which decides what may replace it), the
 *   profile or Quick lock it uses, and the automation or tag that started it.
 * @param hiddenPackages allowed apps not shown in the lock-screen list or the notification (e.g.
 *   the camera). They're still in [allowedPackages], so enforcement treats them the same.
 * @param assistantPopupAllowed the Gemini / Assistant popup may open during a pass.
 * @param homeAction what Home does during a pass. Locks saved before M24 read [HomeAction.LOCK].
 * @param unlockIntoAllowedApp an unlock that lands on an allowed app (or this app's screens) grants
 *   a pass instead of relocking, e.g. after tapping an allowed app's notification on the lock screen.
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
    val source: LockSource = LockSource.UNKNOWN,
    val hiddenPackages: Set<String> = emptySet(),
    val assistantPopupAllowed: Boolean = false,
    val homeAction: HomeAction = HomeAction.LOCK,
    val unlockIntoAllowedApp: Boolean = true,
) {
    val bypassAllowed: Boolean get() = bypassDelayMs != null
    val bypassPending: Boolean get() = bypassEndsAtMs != null

    /** When the lock actually ends: the scheduled end, or a pending early unlock if sooner. */
    val effectiveEndMs: Long get() = minOf(endsAtMs, bypassEndsAtMs ?: Long.MAX_VALUE)

    companion object {
        const val DEFAULT_UNLOCK_CHOICE_SECONDS = 3
    }
}

/** The lock ID of Quick lock, next to profile IDs (as text) in [LockSource.lockId] and NFC tag links. */
const val QUICK_LOCK_ID = "quick"

/**
 * What started a lock. Only one lock runs at a time: a new lock replaces the running one only if
 * its kind weighs more; otherwise it's refused (ADR 0005). Emergency mode will weigh 4.
 */
enum class LockKind(val weight: Int) {
    /** Quick lock or a profile's Start button. */
    MANUAL(1),

    /** An NFC tag (only tags set to take over can start one during a lock). */
    NFC(2),

    /** The intent API (MacroDroid, Tasker…). */
    INTENT(2),

    /** Automatic commitments, set up in advance. */
    SCHEDULE(3),
    CALENDAR(3),
    PLACE(3),
    ALARM(3),

    /** Morning lock: the first unlock after sleeping, or a dismissed alarm (M22). */
    WAKE(3),
}

/**
 * Where a lock came from.
 *
 * @param lockId the profile it uses (its ID as text), or [QUICK_LOCK_ID].
 * @param ownerId the schedule, calendar trigger, place, alarm trigger or NFC tag that started it;
 *   null for manual and intent locks.
 * @param name the lock's name for messages (the profile's name; empty for Quick lock).
 */
data class LockSource(
    val kind: LockKind,
    val lockId: String,
    val ownerId: Long? = null,
    val name: String = "",
) {
    companion object {
        /** A lock saved before sources existed. */
        val UNKNOWN = LockSource(LockKind.MANUAL, QUICK_LOCK_ID)
    }
}

sealed interface LockState {
    data object Idle : LockState
    data class Locked(val session: LockSession) : LockState
}
