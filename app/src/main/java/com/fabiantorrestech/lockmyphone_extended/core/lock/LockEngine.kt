package com.fabiantorrestech.lockmyphone_extended.core.lock

import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel

/** What to do when the user has just unlocked the device. */
enum class UnlockDecision {
    /** No lock is active, or an allowed-app pass is active, so nothing to do. */
    NONE,
    /** A lock is active with no pass, so lock the device again. */
    RELOCK,
    /** The lock period has passed, so end the lock. */
    END_LOCK,
}

/** What to do with a paused pass when the user unlocks again. */
enum class ResumeDecision {
    /** No paused pass, or the app in front isn't allowed: the normal unlock rules apply. */
    NONE,
    /** Resume the pass; the allowed app is already in front. */
    RESUME_APP,
    /** Resume the pass on the countdown screen, which locks again unless the user picks an app. */
    SHOW_LOCK_SCREEN,
}

/** The result of trying to start a lock (ADR 0005: one lock at a time). */
sealed interface StartDecision {
    /** No lock was running. */
    data class Started(val state: LockState.Locked) : StartDecision

    /** A weaker lock was running; [previous] is gone and this one runs instead. */
    data class Replaced(val state: LockState.Locked, val previous: LockSession) : StartDecision

    /** An equal or stronger lock is running, so nothing changes. */
    data class Refused(val running: LockSession) : StartDecision
}

/** What to do when a new app window comes to the front while the device is unlocked. */
enum class ForegroundDecision { IGNORE, RELOCK }

/**
 * The lock rules, in pure Kotlin with no Android dependencies.
 * Every function takes the current time as a parameter, so the rules are deterministic.
 */
class LockEngine {

    /**
     * Starts a lock of [durationMs]. Only one lock runs at a time: if one is running, the new
     * lock replaces it only when its kind weighs more ([LockKind.weight]); then it runs with its
     * own settings and length, even if that ends sooner. Otherwise it's refused, including when
     * it's the same automation's own running lock.
     *
     * @param bypassDelayMs the early-unlock wait, or null if early unlock isn't allowed.
     */
    fun start(
        current: LockState,
        nowMs: Long,
        durationMs: Long,
        label: String,
        allowedPackages: Set<String>,
        bypassDelayMs: Long?,
        dndEnabled: Boolean,
        dndLevel: DndLevel,
        grayscale: Boolean,
        resumeMode: UnlockResumeMode,
        unlockChoiceSeconds: Int,
        source: LockSource,
    ): StartDecision {
        require(durationMs > 0) { "durationMs must be positive" }
        require(bypassDelayMs == null || bypassDelayMs > 0) { "bypassDelayMs must be positive" }
        require(unlockChoiceSeconds > 0) { "unlockChoiceSeconds must be positive" }
        val new = LockState.Locked(
            LockSession(
                label = label,
                startedAtMs = nowMs,
                endsAtMs = nowMs + durationMs,
                allowedPackages = allowedPackages,
                bypassDelayMs = bypassDelayMs,
                dndEnabled = dndEnabled,
                dndLevel = dndLevel,
                grayscale = grayscale,
                resumeMode = resumeMode,
                unlockChoiceSeconds = unlockChoiceSeconds,
                source = source,
            )
        )
        // A lock whose time is up counts as over, even before its end alarm has run.
        val running = (current as? LockState.Locked)?.session?.takeIf { isActive(current, nowMs) }
        return when {
            running == null -> StartDecision.Started(new)
            source.kind.weight > running.source.kind.weight -> StartDecision.Replaced(new, running)
            else -> StartDecision.Refused(running)
        }
    }

    fun isActive(state: LockState, nowMs: Long): Boolean =
        state is LockState.Locked && nowMs < state.session.effectiveEndMs

    fun isExpired(state: LockState, nowMs: Long): Boolean =
        state is LockState.Locked && nowMs >= state.session.effectiveEndMs

    /**
     * Starts the early-unlock countdown. It returns null if early unlock isn't allowed or is
     * already pending. The countdown never runs past the lock's own end.
     */
    fun requestBypass(state: LockState, nowMs: Long): LockState? {
        if (!isActive(state, nowMs)) return null
        val session = (state as LockState.Locked).session
        val delay = session.bypassDelayMs ?: return null
        if (session.bypassPending) return null
        val bypassEnd = minOf(nowMs + delay, session.endsAtMs)
        return state.copy(session = session.copy(bypassEndsAtMs = bypassEnd))
    }

    /** Stops a pending early unlock. Returns null if none is pending. */
    fun cancelBypass(state: LockState): LockState? {
        if (state !is LockState.Locked || !state.session.bypassPending) return null
        return state.copy(session = state.session.copy(bypassEndsAtMs = null))
    }

    fun decideOnUnlock(state: LockState, nowMs: Long): UnlockDecision = when {
        isExpired(state, nowMs) -> UnlockDecision.END_LOCK
        !isActive(state, nowMs) -> UnlockDecision.NONE
        (state as LockState.Locked).session.passActive -> UnlockDecision.NONE
        else -> UnlockDecision.RELOCK
    }

    fun isAllowed(state: LockState, packageName: String): Boolean =
        state is LockState.Locked && packageName in state.session.allowedPackages

    /** Returns the state with a pass granted, or null if [packageName] isn't allowed. */
    fun grantPass(state: LockState, nowMs: Long, packageName: String): LockState? {
        if (!isActive(state, nowMs) || !isAllowed(state, packageName)) return null
        val locked = state as LockState.Locked
        return locked.copy(session = locked.session.copy(passActive = true))
    }

    /** Opens the allowed-apps picker. That's our own screen, so no package check is needed. */
    fun grantPickerPass(state: LockState, nowMs: Long): LockState? {
        if (!isActive(state, nowMs)) return null
        val locked = state as LockState.Locked
        return locked.copy(session = locked.session.copy(passActive = true))
    }

    fun clearPass(state: LockState): LockState = when (state) {
        is LockState.Locked -> state.copy(session = state.session.copy(passActive = false, passPaused = false))
        is LockState.Idle -> state
    }

    /** The screen turned off during a pass: pause it. Returns null if no pass is active. */
    fun pausePass(state: LockState): LockState? {
        if (state !is LockState.Locked || !state.session.passActive) return null
        return state.copy(session = state.session.copy(passActive = false, passPaused = true))
    }

    /**
     * Called when the user unlocks again. A paused pass resumes only if the app in front is still
     * one the pass allowed: an allowed app, or this app's own countdown/picker screen. Anything
     * else (e.g. a camera opened from the lock screen) falls back to the normal unlock rules.
     *
     * @param appInFront the last app seen in front, ignoring system components; null if unknown.
     * @param appInFrontIsOwnApp whether [appInFront] is this app.
     */
    fun decideResume(
        state: LockState,
        nowMs: Long,
        appInFront: String?,
        appInFrontIsOwnApp: Boolean,
    ): ResumeDecision {
        if (!isActive(state, nowMs) || appInFront == null) return ResumeDecision.NONE
        val session = (state as LockState.Locked).session
        if (!session.passPaused) return ResumeDecision.NONE
        if (!appInFrontIsOwnApp && appInFront !in session.allowedPackages) return ResumeDecision.NONE
        return when (session.resumeMode) {
            UnlockResumeMode.RESUME_APP -> ResumeDecision.RESUME_APP
            UnlockResumeMode.SHOW_LOCK_SCREEN -> ResumeDecision.SHOW_LOCK_SCREEN
        }
    }

    /** Turns a paused pass back into an active one. */
    fun resumePass(state: LockState): LockState = when (state) {
        is LockState.Locked -> state.copy(session = state.session.copy(passActive = true, passPaused = false))
        is LockState.Idle -> state
    }

    /**
     * Called for each app that comes to the front while the device is unlocked.
     * Without a pass, it ignores the event: the unlock handler already relocks.
     * With a pass, only allowed apps and system components ([isAlwaysAllowed]) may be in
     * front. Anything else relocks, including the launcher, which is the home screen and the
     * app switcher.
     */
    fun decideOnForeground(
        state: LockState,
        nowMs: Long,
        packageName: String,
        isAlwaysAllowed: Boolean,
    ): ForegroundDecision {
        if (!isActive(state, nowMs)) return ForegroundDecision.IGNORE
        val session = (state as LockState.Locked).session
        if (!session.passActive) return ForegroundDecision.IGNORE
        if (isAlwaysAllowed || packageName in session.allowedPackages) return ForegroundDecision.IGNORE
        return ForegroundDecision.RELOCK
    }
}
