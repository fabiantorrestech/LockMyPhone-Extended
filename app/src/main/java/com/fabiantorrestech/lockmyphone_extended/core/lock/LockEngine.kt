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

/** What an unlock does to a Limited lock ([LockEngine.limitedUnlock]). */
sealed interface LimitedUnlock {
    /** A cooldown is running: the normal unlock rules apply (only exempt apps, through a pass). */
    data class Cooldown(val state: LockState.Locked, val untilMs: Long) : LimitedUnlock

    /** A session is open; [resumed] when it's the same one as before a short screen-off. */
    data class Session(val state: LockState.Locked, val resumed: Boolean) : LimitedUnlock
}

/** A session of a Limited or Timed apps lock that ended now ([LockEngine.endCountedSession]). */
data class EndedSession(val state: LockState.Locked, val cooldownEndsAtMs: Long?)

/** What to do when a new app window comes to the front while the device is unlocked. */
enum class ForegroundDecision {
    IGNORE,
    RELOCK,

    /** The launcher (Home, app switcher) during a pass: open the allowed apps or countdown instead. */
    REDIRECT,
}

/**
 * A visit to one allowed app's App info page in Settings, opened from the allowed-apps list
 * (e.g. to force stop it). Only that page and its dialogs are let through.
 *
 * @param settingsPackage the package that shows App info.
 * @param pageClassName the App info page's activity, once it has come to the front.
 */
data class AppInfoVisit(
    val settingsPackage: String,
    val pageClassName: String? = null,
)

/**
 * The lock rules, in pure Kotlin with no Android dependencies.
 * Every function takes the current time as a parameter, so the rules are deterministic.
 */
class LockEngine {

    /**
     * Starts a lock of [durationMs]. Only one lock runs at a time: if one is running, the new
     * lock replaces it only when its kind weighs more ([LockKind.weight]); then it runs with its
     * own settings and length, even if that ends sooner. Otherwise it's refused, including when
     * it's the same automation's own running lock, unless [restartOwn].
     *
     * @param restartOwn the same automation's own running lock is replaced (a Morning lock after
     *   "Going back to sleep" starts again in full); any other running lock follows the weights.
     * @param bypassDelayMs the early-unlock wait, or null if early unlock isn't allowed.
     * @param hiddenPackages allowed apps left out of the lock-screen list.
     * @param options the global "During a lock" settings.
     * @param pickerLayout the allowed-apps list's folders and row order.
     * @param timedApps a Timed apps lock's untimed apps and banks (with [limit]), or null.
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
        hiddenPackages: Set<String> = emptySet(),
        options: GlobalLockOptions = GlobalLockOptions(),
        restartOwn: Boolean = false,
        limit: LimitPolicy? = null,
        pickerLayout: PickerLayout = PickerLayout.NONE,
        timedApps: TimedApps? = null,
    ): StartDecision {
        require(durationMs > 0) { "durationMs must be positive" }
        require(bypassDelayMs == null || bypassDelayMs > 0) { "bypassDelayMs must be positive" }
        require(unlockChoiceSeconds > 0) { "unlockChoiceSeconds must be positive" }
        // An Unlimited lock can only end early, so early unlock is always on for it.
        val bypass = bypassDelayMs ?: LockSession.UNLIMITED_BYPASS_MS.takeIf { durationMs >= LockSession.UNLIMITED_MS }
        val new = LockState.Locked(
            LockSession(
                label = label,
                startedAtMs = nowMs,
                endsAtMs = nowMs + durationMs,
                allowedPackages = allowedPackages,
                bypassDelayMs = bypass,
                dndEnabled = dndEnabled,
                dndLevel = dndLevel,
                grayscale = grayscale,
                resumeMode = resumeMode,
                unlockChoiceSeconds = unlockChoiceSeconds,
                source = source,
                // Only allowed apps can be hidden.
                hiddenPackages = hiddenPackages intersect allowedPackages,
                assistantPopupAllowed = options.assistantPopupAllowed,
                homeAction = options.homeAction,
                unlockIntoAllowedApp = options.unlockIntoAllowedApp,
                popupPackages = options.popupPackages,
                limit = limit,
                pickerLayout = pickerLayout,
                // A Timed apps lock needs its timer.
                timedApps = timedApps.takeIf { limit != null },
            )
        )
        // A lock whose time is up counts as over, even before its end alarm has run.
        val running = (current as? LockState.Locked)?.session?.takeIf { isActive(current, nowMs) }
        return when {
            running == null -> StartDecision.Started(new)
            source.kind.weight > running.source.kind.weight -> StartDecision.Replaced(new, running)
            restartOwn && source.ownerId != null && running.source.kind == source.kind && running.source.ownerId == source.ownerId ->
                StartDecision.Replaced(new, running)
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
        // A Limited lock's open session: the phone is usable.
        state.session.limitState.sessionRunning -> UnlockDecision.NONE
        else -> UnlockDecision.RELOCK
    }

    /**
     * An unlock during a Limited lock (null for any other lock). A session that the screen paused
     * for less than [LimitPolicy.GRACE_MS] continues; one paused longer ended when the screen went
     * off ([closeSession]). Then, outside a cooldown, a new session starts with its full length.
     */
    fun limitedUnlock(state: LockState, nowMs: Long): LimitedUnlock? {
        if (!isActive(state, nowMs)) return null
        val locked = state as LockState.Locked
        // A Timed apps lock unlocks like a normal one; its apps count when they're in front.
        if (locked.session.timedApps != null) return null
        val policy = locked.session.limit ?: return null
        var limitState = locked.session.limitState
        if (limitState.inSession) {
            val offAt = limitState.screenOffAtMs ?: return LimitedUnlock.Session(locked, resumed = true)
            if (nowMs - offAt <= LimitPolicy.GRACE_MS) {
                return LimitedUnlock.Session(locked.withLimitState(limitState.copy(screenOffAtMs = null)), resumed = true)
            }
            limitState = closeSession(policy, limitState, offAt)
        }
        val cooldownEnd = limitState.cooldownEndsAtMs
        if (cooldownEnd != null && nowMs < cooldownEnd) {
            return LimitedUnlock.Cooldown(locked.withLimitState(limitState), cooldownEnd)
        }
        val open = limitState.copy(inSession = true, usedMs = 0L, countingSinceMs = null, screenOffAtMs = null, cooldownEndsAtMs = null)
        return LimitedUnlock.Session(locked.withLimitState(open), resumed = false)
    }

    /**
     * A window came to the front during a Limited lock's running session: time counts while a
     * counted app is in front, and stops on an exempt one (allowed apps, the phone, the system).
     * Returns null when nothing changes.
     */
    fun limitedForeground(state: LockState, nowMs: Long, exempt: Boolean): LockState? {
        val locked = state as? LockState.Locked ?: return null
        val limitState = locked.session.limitState
        if (locked.session.limit == null || !limitState.sessionRunning) return null
        val counting = limitState.countingSinceMs
        return when {
            exempt && counting != null ->
                locked.withLimitState(limitState.copy(usedMs = limitState.usedMs + (nowMs - counting).coerceAtLeast(0L), countingSinceMs = null))
            !exempt && counting == null -> locked.withLimitState(limitState.copy(countingSinceMs = nowMs))
            else -> null
        }
    }

    /** The screen turned off during a Limited lock's session: it pauses, and stops counting. */
    fun limitedScreenOff(state: LockState, nowMs: Long): LockState? {
        val locked = state as? LockState.Locked ?: return null
        val limitState = locked.session.limitState
        if (locked.session.limit == null || !limitState.sessionRunning) return null
        val counted = limitState.countingSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        return locked.withLimitState(limitState.copy(usedMs = limitState.usedMs + counted, countingSinceMs = null, screenOffAtMs = nowMs))
    }

    /** How much of the running session is left, or null without one. */
    fun sessionLeftMs(state: LockState, nowMs: Long): Long? {
        val session = (state as? LockState.Locked)?.session ?: return null
        val policy = session.limit ?: return null
        if (!session.limitState.sessionRunning) return null
        return policy.sessionMs - usedMs(session.limitState, nowMs)
    }

    /** The session's time is up: it ends now, and its cooldown starts. */
    fun endSession(state: LockState, nowMs: Long): LockState? {
        val locked = state as? LockState.Locked ?: return null
        val policy = locked.session.limit ?: return null
        if (!locked.session.limitState.inSession) return null
        return locked.withLimitState(closeSession(policy, locked.session.limitState, nowMs))
    }

    /**
     * Ends a session at [endMs]. Only exempt apps used: nothing to pay. A short one is a peek while
     * the lock has some left ([peeksUsed] so far: a Timed apps lock counts all its banks'). Otherwise
     * a cooldown starts, a step longer than the last.
     */
    private fun closeSession(
        policy: LimitPolicy,
        limitState: LimitState,
        endMs: Long,
        peeksUsed: Int = limitState.peeksUsed,
    ): LimitState {
        val used = usedMs(limitState, endMs)
        val closed = LimitState(sessionsDone = limitState.sessionsDone, peeksUsed = limitState.peeksUsed)
        return when {
            used <= 0L -> closed
            used < policy.peekMs && peeksUsed < policy.peekCount -> closed.copy(peeksUsed = limitState.peeksUsed + 1)
            else -> closed.copy(
                sessionsDone = limitState.sessionsDone + 1,
                cooldownEndsAtMs = endMs + policy.cooldownMs(limitState.sessionsDone),
            )
        }
    }

    private fun usedMs(limitState: LimitState, nowMs: Long): Long =
        limitState.usedMs + (limitState.countingSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)

    private fun LockState.Locked.withLimitState(limitState: LimitState) = copy(session = session.copy(limitState = limitState))

    // ---- Timed apps ----

    /**
     * The bank [packageName] counts in during a Timed apps lock, or null when it doesn't count:
     * [exempt] (this app, the system), not an allowed app, or untimed.
     */
    fun timedBank(session: LockSession, packageName: String, exempt: Boolean): String? {
        val timed = session.timedApps ?: return null
        if (exempt || packageName !in session.allowedPackages || packageName in timed.untimed) return null
        return timed.bankOf(packageName)
    }

    /** When [packageName]'s bank may start a session again, or null if it isn't cooling down. */
    fun timedCooldownEnd(session: LockSession, packageName: String, nowMs: Long, exempt: Boolean = false): Long? {
        val bank = timedBank(session, packageName, exempt) ?: return null
        return session.bankStates[bank]?.cooldownEndsAtMs?.takeIf { nowMs < it }
    }

    /**
     * A window came to the front during a Timed apps lock: time counts in its app's bank (a new
     * session opens there outside a cooldown) and stops in every other bank. Sessions paused by a
     * screen-off longer than [LimitPolicy.GRACE_MS] end first. A bank that's cooling down doesn't
     * start a session; [decideOnForeground] relocks its app. Returns null when nothing changes.
     */
    fun timedForeground(state: LockState, nowMs: Long, packageName: String, exempt: Boolean): LockState? {
        if (!isActive(state, nowMs)) return null
        val locked = state as LockState.Locked
        val session = locked.session
        val policy = session.limit ?: return null
        if (session.timedApps == null) return null
        val bank = timedBank(session, packageName, exempt)
        var banks = settleBanks(policy, session.bankStates, nowMs).mapValues { (name, s) ->
            if (name != bank) stopCounting(s, nowMs) else s
        }
        if (bank != null) {
            val s = banks[bank] ?: LimitState()
            val cooling = s.cooldownEndsAtMs?.let { nowMs < it } == true
            val next = when {
                cooling -> s
                !s.inSession -> LimitState(
                    inSession = true,
                    countingSinceMs = nowMs,
                    sessionsDone = s.sessionsDone,
                    peeksUsed = s.peeksUsed,
                )
                s.countingSinceMs == null -> s.copy(countingSinceMs = nowMs, screenOffAtMs = null)
                else -> s
            }
            banks = banks + (bank to next)
        }
        if (banks == session.bankStates) return null
        return locked.copy(session = session.copy(bankStates = banks))
    }

    /** The screen turned off during a Timed apps lock: every bank stops counting, open sessions pause. */
    fun timedScreenOff(state: LockState, nowMs: Long): LockState? {
        val locked = state as? LockState.Locked ?: return null
        val session = locked.session
        if (session.timedApps == null) return null
        val banks = session.bankStates.mapValues { (_, s) ->
            val stopped = stopCounting(s, nowMs)
            if (stopped.inSession && stopped.screenOffAtMs == null) stopped.copy(screenOffAtMs = nowMs) else stopped
        }
        if (banks == session.bankStates) return null
        return locked.copy(session = session.copy(bankStates = banks))
    }

    /**
     * How much of the session that's counting now is left (a Limited lock's, or the bank in front
     * in a Timed apps lock), or null while nothing counts.
     */
    fun countingLeftMs(state: LockState, nowMs: Long): Long? {
        val session = (state as? LockState.Locked)?.session ?: return null
        val policy = session.limit ?: return null
        if (session.timedApps == null) {
            if (session.limitState.countingSinceMs == null) return null
            return sessionLeftMs(state, nowMs)
        }
        val counting = session.bankStates.values.firstOrNull { it.countingSinceMs != null } ?: return null
        return policy.sessionMs - usedMs(counting, nowMs)
    }

    /** The session that's counting is used up: it ends now, and its cooldown starts. */
    fun endCountedSession(state: LockState, nowMs: Long): EndedSession? {
        val locked = state as? LockState.Locked ?: return null
        val session = locked.session
        val policy = session.limit ?: return null
        if (session.timedApps == null) {
            val ended = endSession(state, nowMs) as? LockState.Locked ?: return null
            return EndedSession(ended, ended.session.limitState.cooldownEndsAtMs)
        }
        val (bank, s) = session.bankStates.entries.firstOrNull { it.value.countingSinceMs != null } ?: return null
        val closed = closeSession(policy, s, nowMs, totalPeeks(session.bankStates))
        val ended = locked.copy(session = session.copy(bankStates = session.bankStates + (bank to closed)))
        return EndedSession(ended, closed.cooldownEndsAtMs)
    }

    /** Ends bank sessions paused by a screen-off longer than the grace period, at that screen-off. */
    private fun settleBanks(policy: LimitPolicy, banks: Map<String, LimitState>, nowMs: Long): Map<String, LimitState> {
        var settled = banks
        banks.forEach { (name, s) ->
            val offAt = s.screenOffAtMs
            if (s.inSession && offAt != null && nowMs - offAt > LimitPolicy.GRACE_MS) {
                settled = settled + (name to closeSession(policy, s, offAt, totalPeeks(settled)))
            }
        }
        return settled
    }

    private fun stopCounting(s: LimitState, nowMs: Long): LimitState {
        val since = s.countingSinceMs ?: return s
        return s.copy(usedMs = s.usedMs + (nowMs - since).coerceAtLeast(0L), countingSinceMs = null)
    }

    private fun totalPeeks(banks: Map<String, LimitState>): Int = banks.values.sumOf { it.peeksUsed }

    fun isAllowed(state: LockState, packageName: String): Boolean =
        state is LockState.Locked && packageName in state.session.allowedPackages

    /** Returns the state with a pass granted, or null if [packageName] isn't allowed. */
    fun grantPass(state: LockState, nowMs: Long, packageName: String): LockState? {
        if (!isActive(state, nowMs) || !isAllowed(state, packageName)) return null
        val locked = state as LockState.Locked
        // A timed app whose bank is cooling down can't be opened until it's over.
        if (timedCooldownEnd(locked.session, packageName, nowMs) != null) return null
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
        if (!appInFrontIsOwnApp && timedCooldownEnd(session, appInFront, nowMs) != null) return ResumeDecision.NONE
        return when (session.resumeMode) {
            UnlockResumeMode.RESUME_APP -> ResumeDecision.RESUME_APP
            UnlockResumeMode.SHOW_LOCK_SCREEN -> ResumeDecision.SHOW_LOCK_SCREEN
        }
    }

    /**
     * Called when the user unlocks with no pass to resume. If the lock allows it and the app in
     * front is an allowed app or this app's own screen (e.g. after tapping an allowed app's
     * notification on the lock screen), a pass starts as if the app had been picked from the list.
     * Returns null when the normal unlock rules apply.
     *
     * @param appInFront the last app seen in front, ignoring system components; null if unknown.
     */
    fun grantUnlockPass(
        state: LockState,
        nowMs: Long,
        appInFront: String?,
        appInFrontIsOwnApp: Boolean,
    ): LockState? {
        if (!isActive(state, nowMs) || appInFront == null) return null
        val locked = state as LockState.Locked
        val session = locked.session
        if (!session.unlockIntoAllowedApp || session.passActive) return null
        if (!appInFrontIsOwnApp && appInFront !in session.allowedPackages) return null
        if (!appInFrontIsOwnApp && timedCooldownEnd(session, appInFront, nowMs) != null) return null
        return locked.copy(session = session.copy(passActive = true, passPaused = false))
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
     * front. The launcher (home screen and app switcher) redirects, unless the lock's
     * [HomeAction] is LOCK. Anything else relocks.
     */
    fun decideOnForeground(
        state: LockState,
        nowMs: Long,
        packageName: String,
        isAlwaysAllowed: Boolean,
        isLauncher: Boolean,
    ): ForegroundDecision {
        if (!isActive(state, nowMs)) return ForegroundDecision.IGNORE
        val session = (state as LockState.Locked).session
        // A Limited lock's open session allows every app.
        if (session.limitState.sessionRunning) return ForegroundDecision.IGNORE
        if (!session.passActive) return ForegroundDecision.IGNORE
        // A Timed apps lock: an allowed app whose bank is cooling down locks again.
        if (!isAlwaysAllowed && timedCooldownEnd(session, packageName, nowMs) != null) return ForegroundDecision.RELOCK
        if (isAlwaysAllowed || packageName in session.allowedPackages) return ForegroundDecision.IGNORE
        if (isLauncher && session.homeAction != HomeAction.LOCK) return ForegroundDecision.REDIRECT
        return ForegroundDecision.RELOCK
    }

    /**
     * A Settings window during an [AppInfoVisit]. The first Settings activity is the App info
     * page; it reports itself more than once, and its dialogs (Force stop's confirmation) aren't
     * activities. A different Settings activity means the user moved on from App info. Android
     * gives no window IDs here, so pages inside the same activity can't be told apart.
     * Returns the visit to keep, or null if this window leaves it.
     */
    fun followAppInfoVisit(visit: AppInfoVisit, isActivity: Boolean, className: String?): AppInfoVisit? = when {
        !isActivity -> visit
        visit.pageClassName == null -> visit.copy(pageClassName = className)
        className == visit.pageClassName -> visit
        else -> null
    }
}
