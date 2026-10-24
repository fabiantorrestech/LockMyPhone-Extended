package com.fabiantorrestech.lockmyphone_extended.core.lock

import org.json.JSONArray
import org.json.JSONObject

/**
 * A folder on the allowed-apps list, as a companion app sends it (ADR 0008).
 *
 * @param packages its apps, in its own order; all of them are allowed apps.
 * @param shown false = search-only: not listed, found by its exact name.
 */
data class AppFolder(val name: String, val packages: List<String>, val shown: Boolean = true)

/**
 * How a synced profile's allowed-apps list is arranged: its folders, and the order of its rows.
 * Profiles you make in this app have none, and their list stays A–Z.
 *
 * @param order row keys ([folderKey], [appKey]) in the list's order; rows it leaves out go after, A–Z.
 */
data class PickerLayout(val folders: List<AppFolder> = emptyList(), val order: List<String> = emptyList()) {
    companion object {
        val NONE = PickerLayout()
        fun folderKey(name: String): String = "folder:$name"
        fun appKey(packageName: String): String = "app:$packageName"
    }
}

/** [PickerLayout] as JSON, for the database, a running lock and backups. */
object PickerLayoutJson {
    fun write(layout: PickerLayout): String {
        if (layout == PickerLayout.NONE) return ""
        return JSONObject()
            .put("folders", foldersToJson(layout.folders))
            .put("order", JSONArray(layout.order))
            .toString()
    }

    fun read(text: String?): PickerLayout {
        if (text.isNullOrBlank()) return PickerLayout.NONE
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return PickerLayout.NONE
        return PickerLayout(readFolders(o.optJSONArray("folders")), readStrings(o.optJSONArray("order")))
    }

    /** The `folders` extra of SYNC_PROFILE: `[{"name", "packages": [...], "shown"}]`. */
    fun readFolders(text: String): List<AppFolder> = readFolders(runCatching { JSONArray(text) }.getOrNull())

    /** The `order` extra of SYNC_PROFILE: `["folder:<name>" | "app:<package>", ...]`. */
    fun readOrder(text: String): List<String> = readStrings(runCatching { JSONArray(text) }.getOrNull())

    private fun foldersToJson(folders: List<AppFolder>) = JSONArray().apply {
        folders.forEach { folder ->
            put(
                JSONObject()
                    .put("name", folder.name)
                    .put("packages", JSONArray(folder.packages))
                    .put("shown", folder.shown)
            )
        }
    }

    private fun readFolders(array: JSONArray?): List<AppFolder> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AppFolder(name, readStrings(o.optJSONArray("packages")), o.optBoolean("shown", true))
        }.distinctBy { it.name }
    }

    private fun readStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) }.distinct()
    }
}
