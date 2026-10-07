package com.fabiantorrestech.lockmyphone_extended.trigger.nfc

import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcDuringLock
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTagAction
import java.security.MessageDigest

/** Why a tap did nothing. */
enum class TapRefusal {
    UNKNOWN_TAG,
    TAG_OFF,

    /** An End tag with no lock running. */
    NO_LOCK_RUNNING,

    /** A Start tag during a lock, without "take over". */
    ALREADY_LOCKED,

    /** "End it, if it's one of this tag's locks", and the running lock isn't linked to this tag. */
    NFC_NOT_ALLOWED,

    /** Set to end locks, but never written by the app, so it has no code. */
    NOT_WRITTEN,

    /** The code read from the tag isn't the one the app wrote (a copy, or rewritten elsewhere). */
    WRONG_CODE,

    /** "Start the early-unlock wait", and this lock has no early unlock. */
    EARLY_UNLOCK_NOT_ALLOWED,
    EARLY_UNLOCK_ALREADY_RUNNING,
}

/** What a tap does. */
sealed interface TapOutcome {
    /** Start (or extend) one of the tag's linked locks. */
    data object Start : TapOutcome
    data object StartBypassWait : TapOutcome
    data object EndLock : TapOutcome
    data class Refused(val reason: TapRefusal) : TapOutcome
}

/** The NFC tag rules, in pure Kotlin. */
object NfcRules {

    /**
     * @param tag the registered tag with this hardware ID, or null if it isn't registered.
     * @param activeLock the lock running right now, or null if none is.
     * @param presentedCode the code read from the tag itself, or null if it has none. Ending a
     *   lock needs it to match the code the app wrote, so a tag with only a copied ID can't.
     */
    fun decideTap(tag: NfcTag?, activeLock: LockSession?, presentedCode: String?): TapOutcome {
        if (tag == null) return TapOutcome.Refused(TapRefusal.UNKNOWN_TAG)
        if (!tag.enabled) return TapOutcome.Refused(TapRefusal.TAG_OFF)
        if (activeLock == null) {
            return if (tag.startsLocks) {
                TapOutcome.Start
            } else {
                TapOutcome.Refused(TapRefusal.NO_LOCK_RUNNING)
            }
        }
        // A Start tag during a lock: only a take-over tag tries to start (it wins only over a manual lock).
        if (tag.action == NfcTagAction.START) {
            return if (tag.takeOver) TapOutcome.Start else TapOutcome.Refused(TapRefusal.ALREADY_LOCKED)
        }
        return when (tag.duringLock) {
            NfcDuringLock.START_BYPASS_WAIT -> when {
                !activeLock.bypassAllowed -> TapOutcome.Refused(TapRefusal.EARLY_UNLOCK_NOT_ALLOWED)
                activeLock.bypassPending -> TapOutcome.Refused(TapRefusal.EARLY_UNLOCK_ALREADY_RUNNING)
                else -> TapOutcome.StartBypassWait
            }
            NfcDuringLock.END_IF_ALLOWED -> when {
                !tag.written -> TapOutcome.Refused(TapRefusal.NOT_WRITTEN)
                !tag.links(activeLock.source.lockId) -> TapOutcome.Refused(TapRefusal.NFC_NOT_ALLOWED)
                else -> endIfCodeMatches(tag, presentedCode)
            }
            NfcDuringLock.END_ANY ->
                if (tag.written) endIfCodeMatches(tag, presentedCode) else TapOutcome.Refused(TapRefusal.NOT_WRITTEN)
        }
    }

    private fun endIfCodeMatches(tag: NfcTag, presentedCode: String?): TapOutcome {
        val expected = tag.secret ?: return TapOutcome.Refused(TapRefusal.NOT_WRITTEN)
        val matches = presentedCode != null &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), presentedCode.toByteArray(Charsets.US_ASCII))
        return if (matches) TapOutcome.EndLock else TapOutcome.Refused(TapRefusal.WRONG_CODE)
    }

    /**
     * Whether unlocking during [lock] should open the NFC gate: only if some tag could end the
     * lock or start its early-unlock wait right now. Otherwise the phone relocks as usual.
     */
    fun gateApplies(lock: LockSession, tags: List<NfcTag>): Boolean = tags.any { tag ->
        // As if the real tag were tapped: its own code.
        tag.actsDuringLock && decideTap(tag, lock, tag.secret).let { it == TapOutcome.EndLock || it == TapOutcome.StartBypassWait }
    }

    /** A tag's hardware ID as upper-case hex, the form stored in [NfcTag.uid]. */
    fun uidHex(id: ByteArray): String = id.joinToString("") { "%02X".format(it) }

    /** "04:A2:B3:1C" for display. */
    fun formatUid(uid: String): String = uid.chunked(2).joinToString(":")
}
