package com.fabiantorrestech.lockmyphone_extended.core.dnd

/**
 * How strict Do Not Disturb is during a lock. Declared in strictness order, so a higher
 * [ordinal] is stricter (used when two locks merge). Emergency calls ring at every level.
 */
enum class DndLevel {
    /** Only the user's DND exceptions get through (starred contacts, repeat callers, alarms…). */
    PRIORITY,

    /** Only alarms (and media, per Android's rules) get through. */
    ALARMS,

    /** Nothing gets through, not even alarms. */
    TOTAL_SILENCE;

    companion object {
        fun stricter(a: DndLevel, b: DndLevel): DndLevel = if (a.ordinal >= b.ordinal) a else b
    }
}
