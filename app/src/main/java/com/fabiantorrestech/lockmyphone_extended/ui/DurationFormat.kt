package com.fabiantorrestech.lockmyphone_extended.ui

import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import java.util.Locale

/** An Unlimited lock's length, next to "50 min" and "9 h". */
private const val NO_END = "No end"

private const val MS_PER_SECOND = 1_000L

/** Whole seconds, rounded up so a countdown never shows 0 while time remains. */
private fun ceilSeconds(durationMs: Long): Long =
    (durationMs.coerceAtLeast(0) + MS_PER_SECOND - 1) / MS_PER_SECOND

/** "1h 05m 00s", for labels and confirmations. */
fun formatDurationLabel(durationMs: Long): String {
    val totalSeconds = ceilSeconds(durationMs)
    return String.format(
        Locale.getDefault(),
        "%dh %02dm %02ds",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}

/** "50 min", "9 h", "1 h 30 min": a length to read at a glance (the H:M:S form is for editing). */
fun formatLengthLabel(durationMs: Long): String {
    if (durationMs >= LockSession.UNLIMITED_MS) return NO_END
    val totalSeconds = ceilSeconds(durationMs)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "$it h" },
        m.takeIf { it > 0 }?.let { "$it min" },
        s.takeIf { it > 0 && h == 0L }?.let { "$it s" },
    ).joinToString(" ").ifEmpty { "0 min" }
}

/** "1:05:00", for a live countdown. */
fun formatDurationClock(durationMs: Long): String {
    val totalSeconds = ceilSeconds(durationMs)
    return String.format(
        Locale.getDefault(),
        "%d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}
