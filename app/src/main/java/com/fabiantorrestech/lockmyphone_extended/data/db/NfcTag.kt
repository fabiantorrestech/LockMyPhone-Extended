package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What tapping a tag does. */
enum class NfcTagAction {
    /** Starts its lock (or extends the running one). */
    START,

    /** During a lock, does [NfcTag.duringLock]; with no lock running, nothing. */
    END,

    /** Starts its lock when none is running; during a lock, does [NfcTag.duringLock]. */
    TOGGLE,
}

/** What an End or Toggle tag does when it's tapped during a lock. */
enum class NfcDuringLock {
    /** Starts the lock's early-unlock wait; the lock ends when the wait is over. */
    START_BYPASS_WAIT,

    /** Ends the lock now, if the lock allows NFC tags to end it. */
    END_IF_ALLOWED,

    /** Ends any lock now. */
    END_ANY,
}

/**
 * A physical NFC tag, recognised by its hardware ID. Added in DB version 15.
 *
 * @param uid the tag's hardware ID as upper-case hex, e.g. "04A2B31C5E6F80".
 * @param profileId the lock a Start or Toggle tag starts; null = Quick lock.
 * @param durationSec the length a Start or Toggle tag uses; null = that lock's own length.
 * @param secret the random code "Write to tag" put on the tag; null if it was never written.
 *   Ending a lock needs this code read from the tag. Added in DB version 16.
 */
@Entity(
    tableName = "nfc_tags",
    foreignKeys = [
        ForeignKey(
            entity = LockProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index(value = ["uid"], unique = true)],
)
data class NfcTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val uid: String,
    val action: NfcTagAction = NfcTagAction.TOGGLE,
    val duringLock: NfcDuringLock = NfcDuringLock.START_BYPASS_WAIT,
    val profileId: Long? = null,
    val durationSec: Long? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val secret: String? = null,
) {
    val written: Boolean get() = secret != null

    /** Set to end locks (not just start them or the early-unlock wait). */
    val endsLocks: Boolean get() = actsDuringLock && duringLock != NfcDuringLock.START_BYPASS_WAIT

    val startsLocks: Boolean get() = action != NfcTagAction.END
    val actsDuringLock: Boolean get() = action != NfcTagAction.START
}
