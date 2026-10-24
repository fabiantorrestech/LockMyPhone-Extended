package com.fabiantorrestech.lockmyphone_extended.core.apps

/**
 * The picker's search for hidden allowed apps (press Enter, nothing shows before): an app matches only when the query is its name
 * or almost: case, spaces and punctuation don't count ("droidify" → Droidify, "doc" → D.O.C), the first word is
 * enough ("brave" → Brave Browser), a beginning that's most of the name is enough, and on longer names one typo is
 * forgiven. Short or vague text finds nothing, on purpose: you have to know what you're looking for.
 */
object AppMatch {
    fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    fun matches(query: String, name: String): Boolean {
        val q = normalize(query)
        val full = normalize(name)
        if (q.isEmpty() || full.isEmpty()) {
            return false
        }

        if (q == full) {
            return true
        }

        val firstWord = name.trim().split(Regex("\\s+")).firstOrNull()?.let(::normalize).orEmpty()
        if (firstWord.length >= 3 && q == firstWord) {
            return true
        }

        // A beginning that's most of the name ("spotif" for Spotify), never a stub like "sp".
        if (full.startsWith(q) && q.length >= 3 && q.length * 10 >= full.length * 6) {
            return true
        }

        // One typo on longer names ("spotfy").
        return full.length >= 5 && distanceAtMostOne(q, full)
    }

    // True when one insertion, deletion or substitution (or none) turns [a] into [b].
    private fun distanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) {
            return false
        }

        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }

            if (++edits > 1) {
                return false
            }

            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }

        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
