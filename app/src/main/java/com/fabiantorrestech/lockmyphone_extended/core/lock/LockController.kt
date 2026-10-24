package com.fabiantorrestech.lockmyphone_extended.core.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.R
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date
import com.fabiantorrestech.lockmyphone_extended.core.diag.ForegroundLog
import com.fabiantorrestech.lockmyphone_extended.core.diag.RelockEntry
import com.fabiantorrestech.lockmyphone_extended.core.diag.RelockLog
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndController
import com.fabiantorrestech.lockmyphone_extended.core.dnd.GrayscaleController
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.enforce.ForegroundPolicy
import com.fabiantorrestech.lockmyphone_extended.core.enforce.LockAccessibilityService
import com.fabiantorrestech.lockmyphone_extended.core.enforce.Relocker
import com.fabiantorrestech.lockmyphone_extended.core.notify.LockNotifications
import com.fabiantorrestech.lockmyphone_extended.core.service.LockForegroundService

/** A counting session's time left ([leftMs]) at [atMs], for the on-screen clock. */
data class LimitClock(val leftMs: Long, val atMs: Long)

/** What happened when a lock was asked to start. */
sealed interface StartResult {
    data object Started : StartResult

    /** It replaced [previous], a weaker lock. */
    data class Replaced(val previous: LockSession) : StartResult

    /** [running], an equal or stronger lock, stays; nothing changed. */
    data class Refused(val running: LockSession) : StartResult
    data object AdminNotActive : StartResult

    /**
     * The automation starting it is held: its editor is open, or it changed less than a minute
     * ago ([untilMs], or null while it's being edited). Nothing changed.
     */
    data class Held(val untilMs: Long?) : StartResult
}

/**
 * Connects [LockEngine]'s rules to the Android side: storage, the end alarm, the foreground
 * service, notifications and relocking. Every entry point (UI, alarms, boot, the accessibility
 * service, and later the triggers) goes through this class. All methods must be called on the
 * main thread.
 */
class LockController(
    private val context: Context,
    private val store: LockStateStore,
    private val engine: LockEngine,
    private val relocker: Relocker,
    private val endScheduler: LockEndScheduler,
    private val notifications: LockNotifications,
    private val foregroundPolicy: ForegroundPolicy,
    private val foregroundLog: ForegroundLog,
    private val dndController: DndController,
    private val grayscaleController: GrayscaleController,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val state get() = store.state

    /**
     * Called with the lock that ended before its scheduled end: an intent stop, a completed early
     * unlock, or an NFC end tag. The schedule uses it so it doesn't recreate its own lock for the
     * rest of the same window.
     */
    var onLockEndedEarly: ((LockSession) -> Unit)? = null

    /** Opens the countdown screen (MainActivity). Set by the app container, so this class stays UI-free. */
    var onShowLockScreen: (() -> Unit)? = null

    /** Opens the allowed apps or the countdown when Home is pressed during a pass. Set by the app container. */
    var onRedirectHome: ((HomeAction) -> Unit)? = null


    /**
     * The last app seen in front, ignoring system components (keyguard, shade, keyboards). It's
     * also updated while the keyguard shows, so an app opened over the lock screen (e.g. the
     * camera) replaces it. In memory only: after a process restart it's unknown, so a paused
     * pass can't resume and the phone relocks.
     */
    private var lastAppInFront: String? = null

    /** Why the phone locked again (Settings → During a lock → Why did it lock?). */
    val relockLog = RelockLog(context)

    /** Diagnostics only: [lastAppInFront] when the screen turned off and paused the pass. */
    private var appInFrontWhenPaused: String? = null

    /**
     * An App info page opened from the allowed-apps list, or null. It ends when the user is back
     * on this app, on a relock or a new pass; screen off keeps it with the paused pass. In memory only: after a process
     * restart Settings relocks, which is the safe side.
     */
    private var appInfoVisit: AppInfoVisit? = null

    private val _choiceDeadlineMs = MutableStateFlow<Long?>(null)

    /** While the countdown screen waits for the user to pick an app: when it locks again. */
    val choiceDeadlineMs: StateFlow<Long?> = _choiceDeadlineMs.asStateFlow()

    private val _gateActive = MutableStateFlow(false)

    /**
     * True while the NFC gate is open: after an unlock, the countdown screen waits a few seconds
     * for a tag instead of relocking right away. It closes with [choiceDeadlineMs].
     */
    val gateActive: StateFlow<Boolean> = _gateActive.asStateFlow()

    /**
     * How many seconds the NFC gate stays open for this lock, or null for no gate (no NFC, NFC
     * off, or no tag that could act on it). Set by the app container, so this class stays
     * free of NFC and the database.
     */
    var nfcGateSeconds: ((LockSession) -> Int?)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The relock scheduled after an unlock. It's cancelled if a pass arrives during the grace period. */
    private val pendingRelock = Runnable {
        if (engine.decideOnUnlock(store.current, clock()) == UnlockDecision.RELOCK) {
            Log.i(TAG, "Grace period over without a pass; relocking")
            relocker.lockNow()
        }
    }

    /**
     * Safety net after a Home redirect: if the launcher is still in front (the screen didn't open,
     * e.g. Android blocked it), the home screen must not stay usable during a pass, so relock.
     */
    private val redirectCheck = Runnable {
        val app = lastAppInFront ?: return@Runnable
        val passActive = (store.current as? LockState.Locked)?.session?.passActive == true
        if (!passActive || isKeyguardLocked() || !foregroundPolicy.isLauncher(app)) return@Runnable
        Log.i(TAG, "Still on the launcher after a Home redirect; relocking")
        cancelLockScreenChoice()
        store.save(engine.clearPass(store.current))
        relocker.lockNow()
    }

    /** A Limited lock's session: warns near its end and ends it when its time is up. */
    private val limitTick = Runnable { checkLimit() }

    private val _limitClock = MutableStateFlow<LimitClock?>(null)

    /** While a Limited or Timed apps session counts: its time left, for the on-screen clock. */
    val limitClock: StateFlow<LimitClock?> = _limitClock.asStateFlow()

    /** Whether the time-left clock is on screen; then the session warnings don't pop up. Set by the app container. */
    var showsTimeLeftOnScreen: () -> Boolean = { false }

    /** The countdown screen waited long enough without the user picking an app. */
    private val choiceRelock = Runnable {
        _choiceDeadlineMs.value = null
        _gateActive.value = false
        if (engine.isActive(store.current, clock())) {
            Log.i(TAG, "No app picked on the lock screen in time; relocking")
            store.save(engine.clearPass(store.current))
            relocker.lockNow()
        }
    }

    /**
     * @param bypassDelayMs the early-unlock wait, or null to forbid early unlock for this lock.
     * @param dndEnabled turn Do Not Disturb on for the length of the lock.
     * @param dndLevel how strict that Do Not Disturb is.
     * @param grayscale turn the screen grayscale once, now. It's turned off when the lock ends.
     * @param resumeMode what happens when the user unlocks again after the screen turned off in
     *   an allowed app.
     * @param unlockChoiceSeconds how long the countdown screen waits with
     *   [UnlockResumeMode.SHOW_LOCK_SCREEN].
     * @param source what started it; its kind decides whether it may replace a running lock.
     * @param hiddenPackages allowed apps not listed on the lock screen (e.g. the camera).
     * @param options the global "During a lock" settings, saved into the lock.
     * @param limit a Limited lock's sessions and cooldowns, or null for a normal lock.
     * @param pickerLayout the allowed-apps list's folders and row order (a synced profile's).
     * @param timedApps a Timed apps lock's untimed apps and banks (with [limit]), or null.
     */
    fun startLock(
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
        hiddenPackages: Set<String>,
        options: GlobalLockOptions,
        restartOwn: Boolean = false,
        limit: LimitPolicy? = null,
        pickerLayout: PickerLayout = PickerLayout.NONE,
        timedApps: TimedApps? = null,
    ): StartResult {
        if (!relocker.isAdminActive()) return StartResult.AdminNotActive

        val decision = engine.start(
            current = store.current,
            nowMs = clock(),
            durationMs = durationMs,
            label = label,
            allowedPackages = allowedPackages,
            bypassDelayMs = bypassDelayMs,
            dndEnabled = dndEnabled,
            dndLevel = dndLevel,
            grayscale = grayscale,
            resumeMode = resumeMode,
            unlockChoiceSeconds = unlockChoiceSeconds,
            source = source,
            hiddenPackages = hiddenPackages,
            options = options,
            restartOwn = restartOwn,
            limit = limit,
            pickerLayout = pickerLayout,
            timedApps = timedApps,
        )
        val (newState, previous) = when (decision) {
            is StartDecision.Refused -> {
                Log.i(TAG, "${source.kind} lock refused: a ${decision.running.source.kind} lock is running")
                return StartResult.Refused(decision.running)
            }
            is StartDecision.Started -> decision.state to null
            is StartDecision.Replaced -> decision.state to decision.previous
        }
        // A replaced lock's pass, gate or "locking again" countdown is over.
        mainHandler.removeCallbacks(pendingRelock)
        mainHandler.removeCallbacks(limitTick)
        _limitClock.value = null
        cancelLockScreenChoice()
        store.save(newState)
        val session = newState.session
        endScheduler.schedule(session.effectiveEndMs)
        when {
            session.dndEnabled -> dndController.enable(session.dndLevel)
            previous?.dndEnabled == true -> dndController.disable()
        }
        // Only once, at the start: if the user turns grayscale off mid-lock, it stays off.
        when {
            grayscale -> grayscaleController.enable()
            previous?.grayscale == true -> grayscaleController.disable()
        }
        startService()
        updateNotifications()
        relocker.lockNow()
        if (previous != null) Log.i(TAG, "${source.kind} lock replaced a ${previous.source.kind} lock")
        return if (previous != null) StartResult.Replaced(previous) else StartResult.Started
    }

    /** Starts the early-unlock countdown (from the lock-screen notification). */
    fun requestBypass() {
        val newState = engine.requestBypass(store.current, clock()) ?: return
        store.save(newState)
        endScheduler.schedule((newState as LockState.Locked).session.effectiveEndMs)
        Log.i(TAG, "Early unlock requested; ends at ${newState.session.bypassEndsAtMs}")
        updateNotifications()
    }

    /** Stops a pending early unlock; the lock runs to its normal end. */
    fun cancelBypass() {
        val newState = engine.cancelBypass(store.current) ?: return
        store.save(newState)
        endScheduler.schedule((newState as LockState.Locked).session.effectiveEndMs)
        Log.i(TAG, "Early unlock cancelled")
        updateNotifications()
    }

    /**
     * Called by the foreground service each time the user unlocks the device. The relock waits
     * a short grace period. When the user taps an allowed app on the lock screen, SystemUI
     * unlocks first and only then opens our pass activity, so the pass arrives just after
     * this call.
     */
    fun onUserUnlocked() {
        if (tryLimitedUnlock()) return
        if (tryResumePass() || tryUnlockIntoAllowedApp()) {
            // The app already in front gets no new window event: a timed app counts from now.
            lastAppInFront?.let { countLimited(it, null) }
            return
        }
        val decision = engine.decideOnUnlock(store.current, clock())
        Log.i(TAG, "User unlocked; decision=$decision")
        when (decision) {
            UnlockDecision.RELOCK -> {
                logResumeFailure()
                // A paused pass that couldn't resume (another app is in front) is used up.
                appInfoVisit = null
                store.save(engine.clearPass(store.current))
                mainHandler.removeCallbacks(pendingRelock)
                if (!tryOpenGate()) mainHandler.postDelayed(pendingRelock, RELOCK_GRACE_MS)
            }
            UnlockDecision.END_LOCK -> endLock()
            UnlockDecision.NONE -> Unit
        }
    }

    /**
     * An unlock during a Limited lock outside a cooldown opens (or continues) a session: every app
     * is usable until its time is used up. Returns true if it did; in a cooldown the normal unlock
     * rules follow.
     */
    private fun tryLimitedUnlock(): Boolean {
        val result = engine.limitedUnlock(store.current, clock()) ?: return false
        when (result) {
            is LimitedUnlock.Cooldown -> {
                store.save(result.state)
                updateNotifications()
                Log.i(TAG, "Limited lock: unlocked during a cooldown (until ${result.untilMs})")
                return false
            }
            is LimitedUnlock.Session -> {
                mainHandler.removeCallbacks(pendingRelock)
                cancelLockScreenChoice()
                store.save(result.state)
                // The app already in front gets no new window event: count it now.
                lastAppInFront?.let { countLimited(it, null) }
                scheduleLimitTick()
                updateNotifications()
                Log.i(TAG, "Limited lock: session ${if (result.resumed) "continues" else "starts"}")
                return true
            }
        }
    }

    /** Updates the counting of a Limited session, or a Timed apps lock's banks, for the window now in front. */
    private fun countLimited(packageName: String, className: String?, alwaysAllowed: Boolean? = null) {
        val session = (store.current as? LockState.Locked)?.session ?: return
        if (session.limit == null) return
        val system = packageName == context.packageName ||
            (alwaysAllowed ?: foregroundPolicy.isAlwaysAllowed(packageName, className, popupPackages = session.popupPackages))
        val next = if (session.timedApps != null) {
            engine.timedForeground(store.current, clock(), packageName, exempt = system)
        } else {
            engine.limitedForeground(store.current, clock(), exempt = system || packageName in session.allowedPackages)
        }
        next?.let(store::save)
        scheduleLimitTick()
    }

    /**
     * Wakes up at the next warning (1 min, 15 s) or the session's end, while time counts, and
     * updates [limitClock].
     */
    private fun scheduleLimitTick() {
        mainHandler.removeCallbacks(limitTick)
        val leftMs = engine.countingLeftMs(store.current, clock())
        _limitClock.value = leftMs?.let { LimitClock(it, clock()) }
        if (leftMs == null) return
        val delayMs = when {
            leftMs > LIMIT_WARNING_EARLY_MS -> leftMs - LIMIT_WARNING_EARLY_MS
            leftMs > LIMIT_WARNING_LATE_MS -> leftMs - LIMIT_WARNING_LATE_MS
            else -> leftMs
        }
        mainHandler.postDelayed(limitTick, delayMs.coerceAtLeast(0L))
    }

    private fun checkLimit() {
        val leftMs = engine.countingLeftMs(store.current, clock()) ?: return
        // The on-screen clock turns amber and red instead.
        val warn = !showsTimeLeftOnScreen()
        when {
            leftMs <= LIMIT_TICK_SLACK_MS -> {
                val ended = engine.endCountedSession(store.current, clock()) ?: return
                store.save(ended.state)
                val cooldownEnd = ended.cooldownEndsAtMs
                Log.i(TAG, "Session used up; cooldown until $cooldownEnd")
                val minutes = cooldownEnd?.let { ((it - clock() + 59_999) / 60_000).toInt() } ?: 0
                toast(context.getString(R.string.limit_session_over, minutes))
                _limitClock.value = null
                updateNotifications()
                store.save(engine.clearPass(store.current))
                relocker.lockNow()
                return
            }
            !warn -> Unit
            leftMs <= LIMIT_WARNING_LATE_MS + LIMIT_TICK_SLACK_MS -> toast(context.getString(R.string.limit_warning_seconds, 15))
            leftMs <= LIMIT_WARNING_EARLY_MS + LIMIT_TICK_SLACK_MS -> toast(context.getString(R.string.limit_warning_minute))
        }
        scheduleLimitTick()
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    /**
     * The unlock landed on an allowed app or this app's screens: grant a pass instead of
     * relocking ([LockEngine.grantUnlockPass]). Returns true if it did.
     */
    private fun tryUnlockIntoAllowedApp(): Boolean {
        if (!LockAccessibilityService.isRunning) return false
        val appInFront = lastAppInFront
        val newState = engine.grantUnlockPass(store.current, clock(), appInFront, appInFront == context.packageName)
            ?: return false
        applyPass(newState)
        Log.i(TAG, "Unlocked into $appInFront; pass granted")
        return true
    }

    /** When [packageName]'s time bank may start a session again, or null if it isn't cooling down (Timed apps). */
    fun timedCooldownEnd(packageName: String): Long? {
        val session = (store.current as? LockState.Locked)?.session ?: return null
        return engine.timedCooldownEnd(session, packageName, clock())
    }

    /** The soonest end of a Timed apps lock's running cooldowns, or null without one. */
    fun nextTimedCooldownEnd(): Long? {
        val session = (store.current as? LockState.Locked)?.session ?: return null
        val now = clock()
        return session.bankStates.values.mapNotNull { it.cooldownEndsAtMs?.takeIf { end -> end > now } }.minOrNull()
    }

    /** Grants a pass for an allowed app. Returns false if it isn't allowed or can't be enforced. */
    fun grantPass(packageName: String): Boolean {
        if (!LockAccessibilityService.isRunning) return false
        val newState = engine.grantPass(store.current, clock(), packageName) ?: return false
        applyPass(newState)
        return true
    }

    /** Grants a pass for the allowed-apps picker, which is this app's own screen. */
    fun grantPickerPass(): Boolean {
        if (!LockAccessibilityService.isRunning) return false
        val newState = engine.grantPickerPass(store.current, clock()) ?: return false
        applyPass(newState)
        return true
    }

    /**
     * The allowed-apps list is about to open an allowed app's App info page (e.g. to force stop
     * it): let that one page through ([LockEngine.followAppInfoVisit]). Returns false without a
     * running pass.
     */
    fun startAppInfoVisit(settingsPackage: String): Boolean {
        val session = (store.current as? LockState.Locked)?.session ?: return false
        if (!session.passActive) return false
        appInfoVisit = AppInfoVisit(settingsPackage)
        Log.i(TAG, "Opening App info in $settingsPackage")
        return true
    }

    private fun applyPass(newState: LockState) {
        appInfoVisit = null
        mainHandler.removeCallbacks(pendingRelock)
        // Picking an app (or opening the picker) on the countdown screen answers its question.
        cancelLockScreenChoice()
        store.save(newState)
        Log.i(TAG, "Pass granted")
    }

    /** Screen off during a pass pauses it; the next unlock may resume it ([tryResumePass]). */
    fun onScreenOff() {
        // A Limited session pauses; unlocking again soon continues it.
        (engine.limitedScreenOff(store.current, clock()) ?: engine.timedScreenOff(store.current, clock()))?.let {
            store.save(it)
            updateNotifications()
        }
        mainHandler.removeCallbacks(limitTick)
        _limitClock.value = null
        // The gate only lasts for this unlock: turning the screen off closes it for good.
        val gateWasOpen = _gateActive.value
        cancelLockScreenChoice()
        if (gateWasOpen) {
            appInfoVisit = null
            store.save(engine.clearPass(store.current))
            return
        }
        val paused = engine.pausePass(store.current) ?: return
        store.save(paused)
        appInFrontWhenPaused = lastAppInFront
        Log.i(TAG, "Screen off; pass paused (app in front: $lastAppInFront)")
    }

    /**
     * Diagnostics: a pass paused at screen-off that couldn't resume on unlock, and the app in front
     * then versus when it paused. "unknown" now means the app's process restarted in between.
     */
    private fun logResumeFailure() {
        val session = (store.current as? LockState.Locked)?.session ?: return
        if (!session.passPaused) return
        val note = "couldn't resume: app in front ${lastAppInFront ?: "unknown"}, " +
            "paused in ${appInFrontWhenPaused ?: "unknown"}"
        Log.w(TAG, "Unlocked; $note")
        foregroundLog.add(
            ForegroundLog.Entry(
                timeMs = clock(),
                packageName = "(unlock)",
                className = null,
                verdict = ForegroundLog.Verdict.OTHER,
                relocked = true,
                note = note,
            )
        )
    }

    /**
     * Resumes a paused pass if the app in front is still allowed. With
     * [UnlockResumeMode.SHOW_LOCK_SCREEN] it opens the countdown screen and locks again after
     * the session's choice time unless the user picks an app. Returns true if it resumed.
     */
    private fun tryResumePass(): Boolean {
        val current = store.current
        val appInFront = lastAppInFront
        val decision = engine.decideResume(current, clock(), appInFront, appInFront == context.packageName)
        if (decision == ResumeDecision.NONE) return false

        mainHandler.removeCallbacks(pendingRelock)
        val resumed = engine.resumePass(current)
        store.save(resumed)
        Log.i(TAG, "Unlocked back into $appInFront; pass resumed ($decision)")
        if (decision == ResumeDecision.SHOW_LOCK_SCREEN) {
            val delayMs = (resumed as LockState.Locked).session.unlockChoiceSeconds * 1_000L
            _choiceDeadlineMs.value = clock() + delayMs
            mainHandler.removeCallbacks(choiceRelock)
            mainHandler.postDelayed(choiceRelock, delayMs)
            onShowLockScreen?.invoke()
        }
        return true
    }

    private fun cancelLockScreenChoice() {
        mainHandler.removeCallbacks(choiceRelock)
        _choiceDeadlineMs.value = null
        _gateActive.value = false
    }

    /**
     * Instead of relocking, opens the countdown screen for a few seconds with the NFC reader on,
     * so a tag can end the lock or start its early-unlock wait. Doing nothing relocks
     * ([choiceRelock]); the allowed apps and Phone work there as usual. Returns false (relock
     * as usual) when no tag could act on this lock.
     */
    private fun tryOpenGate(): Boolean {
        val current = store.current as? LockState.Locked ?: return false
        val seconds = nfcGateSeconds?.invoke(current.session) ?: return false
        val opened = engine.grantPickerPass(current, clock()) ?: return false
        store.save(opened)
        val delayMs = seconds * 1_000L
        _choiceDeadlineMs.value = clock() + delayMs
        _gateActive.value = true
        mainHandler.removeCallbacks(choiceRelock)
        mainHandler.postDelayed(choiceRelock, delayMs)
        Log.i(TAG, "Unlocked during a lock; NFC gate open for $seconds s")
        onShowLockScreen?.invoke()
        return true
    }

    /**
     * Called by the accessibility service each time a window comes to the front. Every event
     * is also recorded for the diagnostics screen.
     */
    fun onForegroundApp(packageName: String, className: String?) {
        val session = (store.current as? LockState.Locked)?.session
        val onAppInfo = followAppInfoVisit(packageName, className, session)
        val alwaysAllowed = onAppInfo || foregroundPolicy.isAlwaysAllowed(
            packageName,
            className,
            allowAssistantPopup = session?.assistantPopupAllowed == true,
            popupPackages = session?.popupPackages.orEmpty(),
        )
        // System components (keyguard, shade, keyboards) sit on top of the real app, so they
        // don't count. This app does: its picker and countdown screen are pass screens.
        val previousApp = lastAppInFront
        if (!alwaysAllowed || packageName == context.packageName) lastAppInFront = packageName
        if (!isKeyguardLocked()) countLimited(packageName, className, alwaysAllowed)
        val decision = if (isKeyguardLocked()) {
            ForegroundDecision.IGNORE
        } else {
            engine.decideOnForeground(
                store.current,
                clock(),
                packageName,
                alwaysAllowed,
                isLauncher = foregroundPolicy.isLauncher(packageName),
            )
        }
        val relock = decision == ForegroundDecision.RELOCK
        foregroundLog.add(
            ForegroundLog.Entry(
                timeMs = clock(),
                packageName = packageName,
                className = className,
                verdict = verdictFor(packageName, alwaysAllowed),
                relocked = relock,
                note = when {
                    decision == ForegroundDecision.REDIRECT -> "home → ${session?.homeAction}"
                    onAppInfo -> "App info"
                    else -> null
                },
            )
        )
        if (relock) {
            // A timed app whose bank is cooling down: say until when.
            session?.let { engine.timedCooldownEnd(it, packageName, clock(), alwaysAllowed) }?.let { until ->
                toast(context.getString(R.string.timed_cooling_down, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))))
            }
            appInfoVisit = null
            val isActivity = foregroundPolicy.isActivity(packageName, className)
            Log.i(TAG, "Left allowed apps (foreground=$packageName/$className, activity=$isActivity, from=$previousApp); relocking")
            relockLog.record(RelockEntry(clock(), packageName, className, isActivity, previousApp))
            cancelLockScreenChoice()
            store.save(engine.clearPass(store.current))
            relocker.lockNow()
        }
        if (decision == ForegroundDecision.REDIRECT && session != null) redirectHome(session.homeAction)
    }

    /**
     * Updates [appInfoVisit] for this window. Returns true if it's the visit's App info page or
     * one of its dialogs, so it doesn't relock.
     */
    private fun followAppInfoVisit(
        packageName: String,
        className: String?,
        session: LockSession?,
    ): Boolean {
        val visit = appInfoVisit ?: return false
        // Back on the allowed-apps list (or the countdown), or the pass is gone: the visit is
        // over. A pass paused by screen off keeps it, so unlocking back onto App info resumes.
        // Our own windows only count once App info has shown: the list's closing dialog can
        // report itself just after the visit starts, before Settings comes up.
        val passKept = session?.passActive == true || session?.passPaused == true
        val backOnOurApp = packageName == context.packageName && visit.pageClassName != null
        if (backOnOurApp || !passKept) {
            appInfoVisit = null
            return false
        }
        if (packageName != visit.settingsPackage) return false
        val next = engine.followAppInfoVisit(visit, foregroundPolicy.isActivity(packageName, className), className)
        // Behind the lock screen, App info redraws itself on wake. That counts as App info (so
        // the paused pass can resume on it), but it never moves the visit on.
        if (isKeyguardLocked()) return next == visit
        if (next == null) Log.i(TAG, "Moved on from App info to $className")
        appInfoVisit = next
        return next != null
    }

    /**
     * Opens the allowed apps (or countdown) for every launcher window: repeated starts reuse the
     * same screen (CLEAR_TOP | SINGLE_TOP), so nothing stacks and no Home press is ignored.
     */
    private fun redirectHome(action: HomeAction) {
        Log.i(TAG, "Home during a pass; opening $action")
        onRedirectHome?.invoke(action)
        mainHandler.removeCallbacks(redirectCheck)
        mainHandler.postDelayed(redirectCheck, REDIRECT_CHECK_MS)
    }

    private fun verdictFor(packageName: String, alwaysAllowed: Boolean): ForegroundLog.Verdict = when {
        engine.isAllowed(store.current, packageName) -> ForegroundLog.Verdict.ALLOWED_APP
        alwaysAllowed -> ForegroundLog.Verdict.ALWAYS_ALLOWED
        foregroundPolicy.isLauncher(packageName) -> ForegroundLog.Verdict.LAUNCHER
        else -> ForegroundLog.Verdict.OTHER
    }

    /** Ends the lock now. Only for the token-checked intent API and NFC end tags. */
    fun endLockRemotely() {
        if (store.current is LockState.Locked) endLock()
    }

    fun onEndAlarm() {
        val current = store.current
        when {
            engine.isExpired(current, clock()) -> endLock()
            // The alarm fired early (inexact fallback), so schedule it again.
            current is LockState.Locked -> endScheduler.schedule(current.session.effectiveEndMs)
        }
    }

    /** After a reboot or a process restart, re-arm the lock if it's still active. */
    fun resume() {
        val current = store.current
        when {
            engine.isExpired(current, clock()) -> endLock()
            current is LockState.Locked -> {
                endScheduler.schedule(current.session.effectiveEndMs)
                // DND state survives reboots on most builds, but turning it on again is harmless.
                if (current.session.dndEnabled) dndController.enable(current.session.dndLevel)
                startService()
                updateNotifications()
                scheduleLimitTick()
            }
        }
    }

    /**
     * Relocks if a lock is active, no pass is active, and the device is currently unlocked.
     * This covers cases where the unlock broadcast was missed, e.g. the user unlocked right
     * after a boot, before the service was running. It also covers the screen coming back on
     * before the keyguard engaged ("lock after screen timeout"), where a paused pass may resume.
     *
     * @param allowGate open the NFC gate instead of relocking, if one applies. Only for the screen
     *   turning on: right after a lock starts, the keyguard may not show yet, and that isn't an unlock.
     */
    fun enforceIfUnlocked(allowGate: Boolean = false) {
        if (isKeyguardLocked()) return
        // Screen back on before the keyguard engaged: for a Limited lock that's an unlock.
        if (tryLimitedUnlock()) return
        if (tryResumePass()) {
            lastAppInFront?.let { countLimited(it, null) }
            return
        }
        if (engine.decideOnUnlock(store.current, clock()) != UnlockDecision.RELOCK) return
        store.save(engine.clearPass(store.current))
        if (allowGate && tryOpenGate()) return
        Log.i(TAG, "Lock active but device unlocked; relocking")
        relocker.lockNow()
    }

    fun isLockActive(): Boolean = engine.isActive(store.current, clock())

    /**
     * Refreshes the lock notification: countdown, Phone / Allowed apps (when passes can be
     * enforced) and the early-unlock button.
     */
    fun updateNotifications() {
        val current = store.current
        if (current is LockState.Locked && engine.isActive(current, clock())) {
            notifications.updateStatus(current.session, passesAvailable = LockAccessibilityService.isRunning)
        }
    }

    private fun endLock() {
        val current = store.current
        val endedEarly = current is LockState.Locked && clock() < current.session.endsAtMs
        mainHandler.removeCallbacks(pendingRelock)
        mainHandler.removeCallbacks(limitTick)
        _limitClock.value = null
        cancelLockScreenChoice()
        store.save(LockState.Idle)
        endScheduler.cancel()
        dndController.disable()
        grayscaleController.disable()
        context.stopService(Intent(context, LockForegroundService::class.java))
        if (endedEarly) onLockEndedEarly?.invoke((current as LockState.Locked).session)
    }

    private fun isKeyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun startService() {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LockForegroundService::class.java),
            )
        } catch (e: IllegalStateException) {
            // Android 12+ can refuse to start a foreground service from the background
            // (ForegroundServiceStartNotAllowedException). The lock state is already saved,
            // so the next entry point that is allowed to start the service will pick it up.
            Log.w(TAG, "Foreground service start refused", e)
        }
    }

    private companion object {
        const val TAG = "LockController"

        /** Long enough for SystemUI to open the pass activity after an unlock, short enough to feel instant. */
        const val RELOCK_GRACE_MS = 1_000L

        /** How long a Home redirect gets to show its screen before [redirectCheck] relocks. */
        const val REDIRECT_CHECK_MS = 1_500L

        /** A Limited session warns at 1 minute and at 15 seconds left. */
        const val LIMIT_WARNING_EARLY_MS = 60_000L
        const val LIMIT_WARNING_LATE_MS = 15_000L

        /** A Handler wakes up a little early or late; this close counts as on time. */
        const val LIMIT_TICK_SLACK_MS = 500L
    }
}
