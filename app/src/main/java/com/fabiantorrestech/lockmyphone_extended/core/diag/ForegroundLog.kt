package com.fabiantorrestech.lockmyphone_extended.core.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The most recent windows reported by the accessibility service, shown on the diagnostics
 * screen to confirm a device's package names. It's held in memory only and never written to
 * disk.
 */
class ForegroundLog {

    enum class Verdict { ALLOWED_APP, ALWAYS_ALLOWED, LAUNCHER, OTHER }

    data class Entry(
        val timeMs: Long,
        val packageName: String,
        val className: String?,
        val verdict: Verdict,
        /** True if this window triggered a relock. */
        val relocked: Boolean,
        /** Why something happened, for entries that aren't a window (e.g. a pass that couldn't resume). */
        val note: String? = null,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(entry: Entry) {
        // Skip exact repeats: apps emit several window events per screen.
        val last = _entries.value.firstOrNull()
        if (last != null && last.packageName == entry.packageName &&
            last.className == entry.className && last.relocked == entry.relocked
        ) {
            return
        }
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
