package com.fabiantorrestech.lockmyphone_extended.trigger.nfc

import android.content.Context
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult
import com.fabiantorrestech.lockmyphone_extended.data.SettingsStore
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Does what a tapped tag says (via [NfcRules]) and decides whether the NFC gate opens after an
 * unlock. Keeps the tag list in memory, because the gate is decided on the main thread the
 * moment the phone unlocks. Main thread only.
 */
class NfcTagHandler(
    private val context: Context,
    private val database: AppDatabase,
    private val lockController: LockController,
    private val lockStarter: ProfileLockStarter,
    private val settingsStore: () -> SettingsStore,
) {
    private val tags = MutableStateFlow<List<NfcTag>>(emptyList())

    /** Keeps [tags] current. The database is device-protected, so this works before the first unlock too. */
    fun watchTags(scope: CoroutineScope) {
        scope.launch { database.nfcTagDao().observeAll().collect { tags.value = it } }
    }

    /**
     * The gate's length for [lock], or null for no gate. It needs NFC on and a tag that could end
     * this lock or start its early-unlock wait. The gate only opens after an unlock, when the
     * credential-protected settings are readable.
     */
    fun gateSeconds(lock: LockSession): Int? {
        if (!NfcTagIo.isOn(context)) return null
        if (!NfcRules.gateApplies(lock, tags.value)) return null
        return settingsStore().nfcGateSeconds.value
    }

    /**
     * Acts on a tapped tag. Returns a short message saying what happened, for a toast.
     *
     * @param code the code read from the tag itself, or null if it has none.
     * @param readInApp true when the app's own reader saw the tag (the gate). Only then can a tag
     *   end a lock: a tag delivered by Android arrives through an exported activity, which another
     *   app could imitate with a copied ID. Starting a lock or its early-unlock wait is harmless,
     *   so those work either way.
     */
    suspend fun onTagTapped(uid: String, code: String?, readInApp: Boolean): String {
        val tag = database.nfcTagDao().findByUid(uid)
        if (tag == null) {
            Log.i(TAG, "Unregistered tag $uid")
            return context.getString(R.string.nfc_tap_unknown)
        }
        val active = (lockController.state.value as? LockState.Locked)?.session?.takeIf { lockController.isLockActive() }
        val outcome = NfcRules.decideTap(tag, active, code)
        Log.i(TAG, "Tag $uid (${tag.name}): $outcome")
        return when (outcome) {
            is TapOutcome.Start -> start(tag, outcome)
            TapOutcome.StartBypassWait -> {
                lockController.requestBypass()
                context.getString(R.string.nfc_tap_bypass_started, tag.name)
            }
            TapOutcome.EndLock -> if (readInApp) {
                lockController.endLockRemotely()
                context.getString(R.string.nfc_tap_ended, tag.name)
            } else {
                context.getString(R.string.nfc_tap_end_needs_gate)
            }
            is TapOutcome.Refused -> refusalText(outcome.reason, tag)
        }
    }

    private suspend fun start(tag: NfcTag, outcome: TapOutcome.Start): String {
        val label = context.getString(R.string.nfc_lock_label, tag.name)
        val durationMs = outcome.durationSec?.let { it * 1_000 }
        val result = if (outcome.profileId == null) {
            lockStarter.startQuick(durationMs, label)
        } else {
            val profile = database.profileDao().getById(outcome.profileId)
                ?: return context.getString(R.string.nfc_tap_profile_missing, tag.name)
            lockStarter.start(profile, durationMs, label)
        }
        return when (result) {
            StartResult.STARTED -> context.getString(R.string.nfc_tap_started, tag.name)
            StartResult.ADMIN_NOT_ACTIVE -> context.getString(R.string.start_needs_admin)
        }
    }

    private fun refusalText(reason: TapRefusal, tag: NfcTag): String = when (reason) {
        TapRefusal.UNKNOWN_TAG -> context.getString(R.string.nfc_tap_unknown)
        TapRefusal.TAG_OFF -> context.getString(R.string.nfc_tap_off, tag.name)
        TapRefusal.NO_LOCK_RUNNING -> context.getString(R.string.nfc_tap_no_lock, tag.name)
        TapRefusal.NFC_NOT_ALLOWED -> context.getString(R.string.nfc_tap_not_allowed)
        TapRefusal.EARLY_UNLOCK_NOT_ALLOWED -> context.getString(R.string.nfc_tap_no_bypass)
        TapRefusal.EARLY_UNLOCK_ALREADY_RUNNING -> context.getString(R.string.nfc_tap_bypass_running)
        TapRefusal.NOT_WRITTEN -> context.getString(R.string.nfc_tap_not_written, tag.name)
        TapRefusal.WRONG_CODE -> context.getString(R.string.nfc_tap_wrong_code, tag.name)
    }

    private companion object {
        const val TAG = "NfcTagHandler"
    }
}
