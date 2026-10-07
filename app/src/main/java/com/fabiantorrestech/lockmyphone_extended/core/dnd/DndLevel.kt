package com.fabiantorrestech.lockmyphone_extended.core.dnd

/**
 * How strict Do Not Disturb is during a lock, from least to most strict. Emergency calls ring
 * at every level.
 */
enum class DndLevel {
    /** Only the user's DND exceptions get through (starred contacts, repeat callers, alarms…). */
    PRIORITY,

    /** Only alarms (and media, per Android's rules) get through. */
    ALARMS,

    /** Nothing gets through, not even alarms. */
    TOTAL_SILENCE,
}
