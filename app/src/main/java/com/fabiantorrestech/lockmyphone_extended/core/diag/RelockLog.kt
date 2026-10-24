package com.fabiantorrestech.lockmyphone_extended.core.diag

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * One time a window left the allowed apps and the phone locked again.
 *
 * @param isActivity true for another app's screen; false for a popup, bubble or overlay drawn over
 *   the allowed app (those can be allowed with "Popups from other apps").
 * @param fromPackage the app that was in front just before, e.g. the allowed app it covered.
 */
data class RelockEntry(
    val timeMs: Long,
    val packageName: String,
    val className: String?,
    val isActivity: Boolean,
    val fromPackage: String?,
)

/**
 * The last relocks caused by leaving the allowed apps, kept across restarts so "Why did it lock?"
 * can answer after the fact (the system log rotates within minutes). Device-protected, because
 * relocks happen before the first unlock after a reboot too. Only package and window class names
 * are stored, never content.
 */
class RelockLog(context: Context) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(read())
    val entries: StateFlow<List<RelockEntry>> = _entries.asStateFlow()

    fun record(entry: RelockEntry) {
        val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        prefs.edit { putString(KEY_ENTRIES, write(updated)) }
        _entries.value = updated
    }

    fun clear() {
        prefs.edit { remove(KEY_ENTRIES) }
        _entries.value = emptyList()
    }

    private fun read(): List<RelockEntry> {
        val text = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            RelockEntry(
                timeMs = o.optLong("time"),
                packageName = o.optString("package").ifEmpty { return@mapNotNull null },
                className = if (o.isNull("class")) null else o.optString("class"),
                isActivity = o.optBoolean("activity", true),
                fromPackage = if (o.isNull("from")) null else o.optString("from"),
            )
        }
    }

    private fun write(entries: List<RelockEntry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(
                JSONObject()
                    .put("time", e.timeMs)
                    .put("package", e.packageName)
                    .put("class", e.className ?: JSONObject.NULL)
                    .put("activity", e.isActivity)
                    .put("from", e.fromPackage ?: JSONObject.NULL)
            )
        }
    }.toString()

    private companion object {
        const val PREFS_NAME = "relock_log"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 20
    }
}
