package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID

/** What tapping a tag does. */
enum class NfcTagAction {
    /** Starts one of its locks; during a lock only if it takes over (see [NfcTag.takeOver]). */
    START,

    /** During a lock, does [NfcTag.duringLock]; with no lock running, nothing. */
    END,

    /** Starts one of its locks when none is running; during a lock, does [NfcTag.duringLock]. */
    TOGGLE,
}

/** What an End or Toggle tag does when it's tapped during a lock. */
enum class NfcDuringLock {
    /** Starts the lock's early-unlock wait; the lock ends when the wait is over. */
    START_BYPASS_WAIT,

    /** Ends the lock now, if it's one of the tag's linked locks. (Name kept from DB version 15.) */
    END_IF_ALLOWED,

    /** Ends any lock now. */
    END_ANY,
}

/** Which locks a tag is linked to. */
enum class NfcLinkMode {
    /** One lock (the default): a tap starts it straight away. */
    ONE,

    /** Several chosen locks: a tap asks which one to start. */
    SOME,

    /** Every profile and Quick lock: a tap asks which one to start. */
    ALL,
}

/**
 * A physical NFC tag, recognised by its hardware ID. Added in DB version 15.
 *
 * @param name the tag's nickname, e.g. "Fridge".
 * @param uid the tag's hardware ID as upper-case hex, e.g. "04A2B31C5E6F80".
 * @param linkedLockIds with [NfcLinkMode.ONE] or [NfcLinkMode.SOME]: the linked locks, as profile
 *   IDs (text) or [QUICK_LOCK_ID]. Starting picks from them; "End it, if it's one of this tag's
 *   locks" ends only them. Added in DB version 17, replacing a single profile.
 * @param durationSec the length a started lock uses; null = that lock's own length.
 * @param secret the random code "Write to tag" put on the tag; null if it was never written.
 *   Ending a lock needs this code read from the tag. Added in DB version 16.
 * @param takeOver a Start tag tapped during a lock replaces it with its own lock, if the running
 *   lock is a manual one (ADR 0005). Off: it says a lock is already running. Added in DB version 18.
 */
@Entity(tableName = "nfc_tags", indices = [Index(value = ["uid"], unique = true)])
data class NfcTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val uid: String,
    val action: NfcTagAction = NfcTagAction.TOGGLE,
    val duringLock: NfcDuringLock = NfcDuringLock.START_BYPASS_WAIT,
    val linkMode: NfcLinkMode = NfcLinkMode.ONE,
    val linkedLockIds: Set<String> = setOf(QUICK_LOCK_ID),
    val durationSec: Long? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val secret: String? = null,
    @ColumnInfo(defaultValue = "0") val takeOver: Boolean = false,
) {
    val startsLocks: Boolean get() = action != NfcTagAction.END
    val actsDuringLock: Boolean get() = action != NfcTagAction.START
    val written: Boolean get() = secret != null

    /** Set to end locks (not just start them or the early-unlock wait). */
    val endsLocks: Boolean get() = actsDuringLock && duringLock != NfcDuringLock.START_BYPASS_WAIT

    /** Whether this tag is linked to the lock with [lockId] (a profile ID as text, or [QUICK_LOCK_ID]). */
    fun links(lockId: String): Boolean = linkMode == NfcLinkMode.ALL || lockId in linkedLockIds
}
