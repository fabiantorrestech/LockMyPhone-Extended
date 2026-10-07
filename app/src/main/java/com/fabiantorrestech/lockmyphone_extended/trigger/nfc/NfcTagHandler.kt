package com.fabiantorrestech.lockmyphone_extended.trigger.nfc

import android.content.Context
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult
import com.fabiantorrestech.lockmyphone_extended.data.SettingsStore
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import com.fabiantorrestech.lockmyphone_extended.ui.formatAlreadyLocked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A lock a tag can start: a profile, or Quick lock. */
data class LockChoice(val lockId: String, val name: String, val icon: String?)

/** What a tap did, for the screen that saw it. */
sealed interface TapResult {
    /** Done (or refused): show this message. */
    data class Message(val text: String) : TapResult

    /** The tag is linked to several locks: ask which one to start. */
    data class ChooseLock(val tag: NfcTag, val choices: List<LockChoice>) : TapResult
}

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
     * Acts on a tapped tag.
     *
     * @param code the code read from the tag itself, or null if it has none.
     * @param readInApp true when the app's own reader saw the tag (the gate). Only then can a tag
     *   end a lock: a tag delivered by Android arrives through an exported activity, which another
     *   app could imitate with a copied ID. Starting a lock or its early-unlock wait is harmless,
     *   so those work either way.
     */
    suspend fun onTagTapped(uid: String, code: String?, readInApp: Boolean): TapResult {
        val tag = database.nfcTagDao().findByUid(uid)
        if (tag == null) {
            Log.i(TAG, "Unregistered tag $uid")
            return message(R.string.nfc_tap_unknown)
        }
        val active = (lockController.state.value as? LockState.Locked)?.session?.takeIf { lockController.isLockActive() }
        val outcome = NfcRules.decideTap(tag, active, code)
        Log.i(TAG, "Tag $uid (${tag.name}): $outcome")
        return when (outcome) {
            TapOutcome.Start -> {
                val choices = startableLocks(tag)
                when (choices.size) {
                    0 -> message(R.string.nfc_tap_profile_missing, tag.name)
                    1 -> TapResult.Message(startLock(tag, choices.single().lockId))
                    else -> TapResult.ChooseLock(tag, choices)
                }
            }
            TapOutcome.StartBypassWait -> {
                lockController.requestBypass()
                message(R.string.nfc_tap_bypass_started, tag.name)
            }
            TapOutcome.EndLock -> if (readInApp) {
                lockController.endLockRemotely()
                message(R.string.nfc_tap_ended, tag.name)
            } else {
                message(R.string.nfc_tap_end_needs_gate)
            }
            is TapOutcome.Refused -> TapResult.Message(refusalText(outcome.reason, tag, active))
        }
    }

    /** The locks [tag] can start that still exist: its linked profiles in list order, then Quick lock. */
    suspend fun startableLocks(tag: NfcTag): List<LockChoice> {
        val profiles = database.profileDao().getAll()
            .filter { tag.links(it.id.toString()) }
            .map { LockChoice(it.id.toString(), it.name, it.icon) }
        val quick = LockChoice(QUICK_LOCK_ID, context.getString(R.string.quick_lock), null)
            .takeIf { tag.links(QUICK_LOCK_ID) }
        return profiles + listOfNotNull(quick)
    }

    /** Starts one of the tag's locks (from the tap, or the chooser). Returns what happened, for a toast. */
    suspend fun startLock(tag: NfcTag, lockId: String): String {
        val label = context.getString(R.string.nfc_lock_label, tag.name)
        val durationMs = tag.durationSec?.let { it * 1_000 }
        val result = if (lockId == QUICK_LOCK_ID) {
            lockStarter.startQuick(durationMs, label, LockKind.NFC, tag.id)
        } else {
            val profile = lockId.toLongOrNull()?.let { database.profileDao().getById(it) }
                ?: return context.getString(R.string.nfc_tap_profile_missing, tag.name)
            lockStarter.start(profile, durationMs, label, LockKind.NFC, tag.id)
        }
        return when (result) {
            StartResult.Started -> context.getString(R.string.nfc_tap_started, tag.name)
            is StartResult.Replaced -> context.getString(R.string.nfc_tap_took_over, tag.name)
            is StartResult.Refused -> formatAlreadyLocked(context, result.running)
            StartResult.AdminNotActive -> context.getString(R.string.start_needs_admin)
            is StartResult.Held -> result.untilMs?.let { untilMs ->
                val seconds = ((untilMs - System.currentTimeMillis() + 999) / 1_000).coerceAtLeast(1)
                context.getString(R.string.nfc_tap_held_cooldown, tag.name, seconds.toInt())
            } ?: context.getString(R.string.nfc_tap_held_editing, tag.name)
        }
    }

    private fun message(resId: Int, vararg args: Any): TapResult = TapResult.Message(context.getString(resId, *args))

    private fun refusalText(reason: TapRefusal, tag: NfcTag, active: LockSession?): String = when (reason) {
        TapRefusal.UNKNOWN_TAG -> context.getString(R.string.nfc_tap_unknown)
        TapRefusal.TAG_OFF -> context.getString(R.string.nfc_tap_off, tag.name)
        TapRefusal.NO_LOCK_RUNNING -> context.getString(R.string.nfc_tap_no_lock, tag.name)
        TapRefusal.ALREADY_LOCKED -> active?.let { formatAlreadyLocked(context, it) }.orEmpty()
        TapRefusal.NFC_NOT_ALLOWED -> context.getString(R.string.nfc_tap_not_allowed, tag.name)
        TapRefusal.EARLY_UNLOCK_NOT_ALLOWED -> context.getString(R.string.nfc_tap_no_bypass)
        TapRefusal.EARLY_UNLOCK_ALREADY_RUNNING -> context.getString(R.string.nfc_tap_bypass_running)
        TapRefusal.NOT_WRITTEN -> context.getString(R.string.nfc_tap_not_written, tag.name)
        TapRefusal.WRONG_CODE -> context.getString(R.string.nfc_tap_wrong_code, tag.name)
    }

    private companion object {
        const val TAG = "NfcTagHandler"
    }
}
