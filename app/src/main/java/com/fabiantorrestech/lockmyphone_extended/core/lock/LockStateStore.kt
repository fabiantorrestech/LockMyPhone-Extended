package com.fabiantorrestech.lockmyphone_extended.core.lock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saves the active lock in device-protected storage, so the lock can be read after a reboot
 * before the user unlocks the device for the first time (direct boot).
 */
class LockStateStore(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<LockState> = _state.asStateFlow()

    val current: LockState get() = _state.value

    fun save(state: LockState) {
        // commit() rather than apply(): the process may be killed right after a lock starts.
        prefs.edit(commit = true) {
            when (state) {
                is LockState.Idle -> clear()
                is LockState.Locked -> {
                    putBoolean(KEY_ACTIVE, true)
                    putString(KEY_LABEL, state.session.label)
                    putLong(KEY_STARTED_AT, state.session.startedAtMs)
                    putLong(KEY_ENDS_AT, state.session.endsAtMs)
                    putStringSet(KEY_ALLOWED_PACKAGES, state.session.allowedPackages)
                    putBoolean(KEY_PASS_ACTIVE, state.session.passActive)
                    putOrRemove(KEY_BYPASS_DELAY, state.session.bypassDelayMs)
                    putOrRemove(KEY_BYPASS_ENDS_AT, state.session.bypassEndsAtMs)
                    putBoolean(KEY_DND, state.session.dndEnabled)
                    putString(KEY_DND_LEVEL, state.session.dndLevel.name)
                    putBoolean(KEY_GRAYSCALE, state.session.grayscale)
                    putString(KEY_RESUME_MODE, state.session.resumeMode.name)
                    putInt(KEY_UNLOCK_CHOICE_SECONDS, state.session.unlockChoiceSeconds)
                    putString(KEY_SOURCE_KIND, state.session.source.kind.name)
                    putString(KEY_SOURCE_LOCK_ID, state.session.source.lockId)
                    putOrRemove(KEY_SOURCE_OWNER_ID, state.session.source.ownerId)
                    putString(KEY_SOURCE_NAME, state.session.source.name)
                    putStringSet(KEY_HIDDEN_PACKAGES, state.session.hiddenPackages)
                    putBoolean(KEY_ASSISTANT_POPUP, state.session.assistantPopupAllowed)
                    putString(KEY_HOME_ACTION, state.session.homeAction.name)
                    putBoolean(KEY_UNLOCK_INTO_ALLOWED_APP, state.session.unlockIntoAllowedApp)
                    putStringSet(KEY_POPUP_PACKAGES, state.session.popupPackages)
                    putLimit(state.session.limit, state.session.limitState)
                    putString(KEY_PICKER_LAYOUT, PickerLayoutJson.write(state.session.pickerLayout))
                }
            }
        }
        _state.value = state
    }

    private fun read(): LockState {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return LockState.Idle
        return LockState.Locked(
            LockSession(
                label = prefs.getString(KEY_LABEL, "") ?: "",
                startedAtMs = prefs.getLong(KEY_STARTED_AT, 0L),
                endsAtMs = prefs.getLong(KEY_ENDS_AT, 0L),
                allowedPackages = prefs.getStringSet(KEY_ALLOWED_PACKAGES, null)?.toSet() ?: emptySet(),
                // A pass never survives a process restart; the user must go through the
                // notification again. The same goes for a paused pass, which isn't stored.
                passActive = false,
                bypassDelayMs = prefs.getLongOrNull(KEY_BYPASS_DELAY),
                bypassEndsAtMs = prefs.getLongOrNull(KEY_BYPASS_ENDS_AT),
                dndEnabled = prefs.getBoolean(KEY_DND, false),
                dndLevel = prefs.getString(KEY_DND_LEVEL, null)
                    ?.let { saved -> DndLevel.entries.firstOrNull { it.name == saved } }
                    ?: DndLevel.PRIORITY,
                grayscale = prefs.getBoolean(KEY_GRAYSCALE, false),
                resumeMode = prefs.getString(KEY_RESUME_MODE, null)
                    ?.let { saved -> UnlockResumeMode.entries.firstOrNull { it.name == saved } }
                    ?: UnlockResumeMode.RESUME_APP,
                unlockChoiceSeconds = prefs.getInt(KEY_UNLOCK_CHOICE_SECONDS, LockSession.DEFAULT_UNLOCK_CHOICE_SECONDS),
                source = readSource(),
                hiddenPackages = prefs.getStringSet(KEY_HIDDEN_PACKAGES, null)?.toSet() ?: emptySet(),
                assistantPopupAllowed = prefs.getBoolean(KEY_ASSISTANT_POPUP, false),
                // A lock saved before M24 keeps the old behaviour: Home locks.
                homeAction = prefs.getString(KEY_HOME_ACTION, null)
                    ?.let { saved -> HomeAction.entries.firstOrNull { it.name == saved } }
                    ?: HomeAction.LOCK,
                unlockIntoAllowedApp = prefs.getBoolean(KEY_UNLOCK_INTO_ALLOWED_APP, true),
                // Locks saved before 0.10.0 have none.
                popupPackages = prefs.getStringSet(KEY_POPUP_PACKAGES, null)?.toSet() ?: emptySet(),
                limit = readLimit(),
                limitState = readLimitState(),
                // Locks saved before 0.11.2 have none.
                pickerLayout = PickerLayoutJson.read(prefs.getString(KEY_PICKER_LAYOUT, null)),
            )
        )
    }

    private fun SharedPreferences.Editor.putLimit(limit: LimitPolicy?, limitState: LimitState) {
        if (limit == null) {
            LIMIT_KEYS.forEach(::remove)
            return
        }
        putLong(KEY_LIMIT_SESSION, limit.sessionMs)
        putLong(KEY_LIMIT_FIRST_COOLDOWN, limit.firstCooldownMs)
        putLong(KEY_LIMIT_COOLDOWN_STEP, limit.cooldownStepMs)
        putLong(KEY_LIMIT_MAX_COOLDOWN, limit.maxCooldownMs)
        putLong(KEY_LIMIT_PEEK, limit.peekMs)
        putInt(KEY_LIMIT_PEEK_COUNT, limit.peekCount)
        putBoolean(KEY_LIMIT_IN_SESSION, limitState.inSession)
        putLong(KEY_LIMIT_USED, limitState.usedMs)
        putOrRemove(KEY_LIMIT_COUNTING_SINCE, limitState.countingSinceMs)
        putOrRemove(KEY_LIMIT_SCREEN_OFF_AT, limitState.screenOffAtMs)
        putInt(KEY_LIMIT_SESSIONS_DONE, limitState.sessionsDone)
        putInt(KEY_LIMIT_PEEKS_USED, limitState.peeksUsed)
        putOrRemove(KEY_LIMIT_COOLDOWN_ENDS, limitState.cooldownEndsAtMs)
    }

    /** A normal lock (or one saved before Limited locks) has none. */
    private fun readLimit(): LimitPolicy? {
        if (!prefs.contains(KEY_LIMIT_SESSION)) return null
        return LimitPolicy(
            sessionMs = prefs.getLong(KEY_LIMIT_SESSION, 0L),
            firstCooldownMs = prefs.getLong(KEY_LIMIT_FIRST_COOLDOWN, 0L),
            cooldownStepMs = prefs.getLong(KEY_LIMIT_COOLDOWN_STEP, 0L),
            maxCooldownMs = prefs.getLong(KEY_LIMIT_MAX_COOLDOWN, 0L),
            peekMs = prefs.getLong(KEY_LIMIT_PEEK, 0L),
            peekCount = prefs.getInt(KEY_LIMIT_PEEK_COUNT, 0),
        )
    }

    /**
     * After a process restart a run that was counting keeps counting from when it started (the safe
     * side: the time the process was gone counts), and the next window seen corrects it.
     */
    private fun readLimitState(): LimitState = LimitState(
        inSession = prefs.getBoolean(KEY_LIMIT_IN_SESSION, false),
        usedMs = prefs.getLong(KEY_LIMIT_USED, 0L),
        countingSinceMs = prefs.getLongOrNull(KEY_LIMIT_COUNTING_SINCE),
        screenOffAtMs = prefs.getLongOrNull(KEY_LIMIT_SCREEN_OFF_AT),
        sessionsDone = prefs.getInt(KEY_LIMIT_SESSIONS_DONE, 0),
        peeksUsed = prefs.getInt(KEY_LIMIT_PEEKS_USED, 0),
        cooldownEndsAtMs = prefs.getLongOrNull(KEY_LIMIT_COOLDOWN_ENDS),
    )

    /** A lock saved before sources existed reads as [LockSource.UNKNOWN]. */
    private fun readSource(): LockSource {
        val kind = prefs.getString(KEY_SOURCE_KIND, null)
            ?.let { saved -> LockKind.entries.firstOrNull { it.name == saved } }
            ?: return LockSource.UNKNOWN
        return LockSource(
            kind = kind,
            lockId = prefs.getString(KEY_SOURCE_LOCK_ID, null) ?: QUICK_LOCK_ID,
            ownerId = prefs.getLongOrNull(KEY_SOURCE_OWNER_ID),
            name = prefs.getString(KEY_SOURCE_NAME, null).orEmpty(),
        )
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun SharedPreferences.getLongOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private companion object {
        const val PREFS_NAME = "lock_state"
        const val KEY_ACTIVE = "active"
        const val KEY_LABEL = "label"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ENDS_AT = "ends_at"
        const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        const val KEY_PASS_ACTIVE = "pass_active"
        const val KEY_BYPASS_DELAY = "bypass_delay_ms"
        const val KEY_BYPASS_ENDS_AT = "bypass_ends_at"
        const val KEY_DND = "dnd_enabled"
        const val KEY_DND_LEVEL = "dnd_level"
        const val KEY_GRAYSCALE = "grayscale"
        const val KEY_RESUME_MODE = "resume_mode"
        const val KEY_UNLOCK_CHOICE_SECONDS = "unlock_choice_seconds"
        const val KEY_SOURCE_KIND = "source_kind"
        const val KEY_SOURCE_LOCK_ID = "source_lock_id"
        const val KEY_SOURCE_OWNER_ID = "source_owner_id"
        const val KEY_SOURCE_NAME = "source_name"
        const val KEY_HIDDEN_PACKAGES = "hidden_packages"
        const val KEY_ASSISTANT_POPUP = "assistant_popup"
        const val KEY_HOME_ACTION = "home_action"
        const val KEY_UNLOCK_INTO_ALLOWED_APP = "unlock_into_allowed_app"
        const val KEY_POPUP_PACKAGES = "popup_packages"
        const val KEY_PICKER_LAYOUT = "picker_layout"
        const val KEY_LIMIT_SESSION = "limit_session_ms"
        const val KEY_LIMIT_FIRST_COOLDOWN = "limit_first_cooldown_ms"
        const val KEY_LIMIT_COOLDOWN_STEP = "limit_cooldown_step_ms"
        const val KEY_LIMIT_MAX_COOLDOWN = "limit_max_cooldown_ms"
        const val KEY_LIMIT_PEEK = "limit_peek_ms"
        const val KEY_LIMIT_PEEK_COUNT = "limit_peek_count"
        const val KEY_LIMIT_IN_SESSION = "limit_in_session"
        const val KEY_LIMIT_USED = "limit_used_ms"
        const val KEY_LIMIT_COUNTING_SINCE = "limit_counting_since"
        const val KEY_LIMIT_SCREEN_OFF_AT = "limit_screen_off_at"
        const val KEY_LIMIT_SESSIONS_DONE = "limit_sessions_done"
        const val KEY_LIMIT_PEEKS_USED = "limit_peeks_used"
        const val KEY_LIMIT_COOLDOWN_ENDS = "limit_cooldown_ends"
        val LIMIT_KEYS = listOf(
            KEY_LIMIT_SESSION, KEY_LIMIT_FIRST_COOLDOWN, KEY_LIMIT_COOLDOWN_STEP, KEY_LIMIT_MAX_COOLDOWN, KEY_LIMIT_PEEK,
            KEY_LIMIT_PEEK_COUNT, KEY_LIMIT_IN_SESSION, KEY_LIMIT_USED, KEY_LIMIT_COUNTING_SINCE, KEY_LIMIT_SCREEN_OFF_AT,
            KEY_LIMIT_SESSIONS_DONE, KEY_LIMIT_PEEKS_USED, KEY_LIMIT_COOLDOWN_ENDS,
        )
    }
}
