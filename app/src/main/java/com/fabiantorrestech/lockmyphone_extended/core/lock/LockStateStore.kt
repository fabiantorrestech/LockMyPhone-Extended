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
                    putBoolean(KEY_NFC_UNLOCK_ANY, state.session.nfcUnlock.anyTag)
                    putStringSet(KEY_NFC_UNLOCK_TAGS, state.session.nfcUnlock.tagIds)
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
                nfcUnlock = NfcUnlock(
                    anyTag = prefs.getBoolean(KEY_NFC_UNLOCK_ANY, false),
                    tagIds = prefs.getStringSet(KEY_NFC_UNLOCK_TAGS, null)?.toSet() ?: emptySet(),
                ),
            )
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
        const val KEY_NFC_UNLOCK_ANY = "nfc_unlock"
        const val KEY_NFC_UNLOCK_TAGS = "nfc_unlock_tags"
    }
}
