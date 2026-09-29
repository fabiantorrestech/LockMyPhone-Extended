package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

/**
 * Pure rules for noticing that a learned alarm may no longer exist. The alarm app never says
 * "deleted", so these read clues from what's visible:
 * - **Silent:** the alarm hasn't been seen (upcoming notice or ring) for longer than it should be.
 * - **Re-created:** its upcoming notice vanished, and soon after a new, unlinked alarm appeared at
 *   the same time.
 */
object AlarmHealthRules {

    private const val HOUR_MS = 60L * 60 * 1_000

    /** How long the alarm's upcoming notice must stay gone, with no ring, before it counts as vanished. */
    const val VANISH_CONFIRM_MS = 60_000L

    /** A new alarm at the same time within this window after a vanish looks like a re-creation. */
    const val RECREATE_WINDOW_MS = 10L * 60 * 1_000

    /** Silence allowed before warning: the expected interval plus a 12-hour margin. */
    fun silenceLimitMs(expectedEveryDays: Int): Long = expectedEveryDays.coerceAtLeast(1) * 24 * HOUR_MS + 12 * HOUR_MS

    fun isSilent(lastSeenMs: Long, expectedEveryDays: Int, nowMs: Long): Boolean =
        nowMs - lastSeenMs > silenceLimitMs(expectedEveryDays)

    /** Whole days since [lastSeenMs], for the warning text. */
    fun daysSince(lastSeenMs: Long, nowMs: Long): Int = ((nowMs - lastSeenMs) / (24 * HOUR_MS)).toInt()

    /** Compares times like "05:00 PM" and "5:00 pm" as equal. */
    fun sameTime(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return normalize(a) == normalize(b)
    }

    private fun normalize(time: String): String =
        time.lowercase().filter { it.isLetterOrDigit() || it == ':' }.trimStart('0')
}
