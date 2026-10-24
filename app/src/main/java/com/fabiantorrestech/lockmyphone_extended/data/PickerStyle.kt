package com.fabiantorrestech.lockmyphone_extended.data

/**
 * How the allowed-apps picker and the lock countdown draw their clock and app names.
 * One font is used for both the clock and the app names.
 */
data class PickerStyle(
    val clockSizeSp: Int = DEFAULT_CLOCK_SIZE_SP,
    val appNameSizeSp: Int = DEFAULT_APP_NAME_SIZE_SP,
    val font: FontChoice = FontChoice.System(SystemFont.DEFAULT),
    /** Hide the status bar on both screens; a swipe from the top still shows it for a moment. */
    val hideStatusBar: Boolean = true,
    /** Show the time left in the top-right of the allowed-apps list. */
    val showTimeLeft: Boolean = true,
) {
    fun clamped(): PickerStyle = copy(
        clockSizeSp = clockSizeSp.coerceIn(CLOCK_SIZE_RANGE),
        appNameSizeSp = appNameSizeSp.coerceIn(APP_NAME_SIZE_RANGE),
    )

    companion object {
        val CLOCK_SIZE_RANGE = 32..120
        val APP_NAME_SIZE_RANGE = 14..40
        const val DEFAULT_CLOCK_SIZE_SP = 64
        const val DEFAULT_APP_NAME_SIZE_SP = 20
    }
}

/**
 * The phone's built-in font families.
 *
 * @param familyName the name passed to `Typeface.create`, or null for the default font.
 */
enum class SystemFont(val familyName: String?) {
    DEFAULT(null),
    LIGHT("sans-serif-light"),
    THIN("sans-serif-thin"),
    CONDENSED("sans-serif-condensed"),
    MEDIUM("sans-serif-medium"),
    SERIF("serif"),
    MONOSPACE("monospace"),
    SERIF_MONO("serif-monospace"),
    CASUAL("casual"),
    CURSIVE("cursive"),
    SMALL_CAPS("sans-serif-smallcaps"),
}

/** A built-in font, or a font file the user added ([FontLibrary]). */
sealed interface FontChoice {
    /** Stored in the settings and in backups. */
    val id: String

    data class System(val font: SystemFont) : FontChoice {
        override val id: String get() = SYSTEM_PREFIX + font.name
    }

    /** [fileName] is the file's name inside [FontLibrary]'s folder. */
    data class UserFile(val fileName: String) : FontChoice {
        override val id: String get() = FILE_PREFIX + fileName
    }

    companion object {
        private const val SYSTEM_PREFIX = "system:"
        private const val FILE_PREFIX = "file:"

        /** Reads a stored [id]; anything unknown is the default font. */
        fun fromId(id: String?): FontChoice {
            if (id != null && id.startsWith(FILE_PREFIX) && id.length > FILE_PREFIX.length) {
                return UserFile(id.removePrefix(FILE_PREFIX))
            }
            val name = id?.removePrefix(SYSTEM_PREFIX)
            return System(SystemFont.entries.firstOrNull { it.name == name } ?: SystemFont.DEFAULT)
        }
    }
}
