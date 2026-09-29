package com.fabiantorrestech.lockmyphone_extended.ui

import java.util.Locale

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
