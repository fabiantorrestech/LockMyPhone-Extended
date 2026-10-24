package com.fabiantorrestech.lockmyphone_extended.pass

import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.core.lock.PickerLayout

/** A folder as the list shows it: only its apps that may be listed, in the folder's order. */
data class PickerFolder(val name: String, val apps: List<LaunchableApp>, val shown: Boolean)

/** A top-level entry of the allowed-apps list: an app on its own, or a folder. */
sealed interface PickerEntry {
    val key: String
    val label: String

    data class App(val app: LaunchableApp) : PickerEntry {
        override val key: String get() = PickerLayout.appKey(app.packageName)
        override val label: String get() = app.label
    }

    data class Folder(val folder: PickerFolder) : PickerEntry {
        override val key: String get() = PickerLayout.folderKey(folder.name)
        override val label: String get() = folder.name
    }
}

/** One row as drawn: a folder (open or closed), or an app, indented when it's inside an open folder. */
sealed interface PickerRow {
    val key: String

    data class FolderRow(val folder: PickerFolder, val open: Boolean) : PickerRow {
        override val key: String get() = PickerLayout.folderKey(folder.name)
    }

    data class AppRow(val app: LaunchableApp, val folderName: String? = null) : PickerRow {
        override val key: String get() = (folderName?.let { "in:$it:" } ?: "") + app.packageName
    }
}

object PickerEntries {
    /**
     * The list's entries. Apps in a folder are only listed inside it, and a folder with nothing to
     * list is left out. Entries follow [PickerLayout.order]; the rest come after it, A–Z, so without
     * a layout the list is A–Z as before.
     *
     * @param listed the allowed apps that may be listed (not hidden, not the dialer).
     */
    fun build(listed: List<LaunchableApp>, layout: PickerLayout): List<PickerEntry> {
        val byPackage = listed.associateBy { it.packageName }
        val inFolders = layout.folders.flatMap { it.packages }.toSet()
        val folders = layout.folders
            .map { PickerFolder(it.name, it.packages.distinct().mapNotNull(byPackage::get), it.shown) }
            .filter { it.apps.isNotEmpty() }
            .map(PickerEntry::Folder)
        val loose = listed.filter { it.packageName !in inFolders }.map(PickerEntry::App)
        val position = layout.order.withIndex().associate { (index, key) -> key to index }
        return (folders + loose).sortedWith(
            compareBy<PickerEntry> { position[it.key] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() }
        )
    }

    /** The rows to draw: search-only folders only once [revealed], and the apps of [open] folders under them. */
    fun rows(entries: List<PickerEntry>, open: Set<String>, revealed: Set<String>): List<PickerRow> =
        entries.flatMap { entry ->
            when (entry) {
                is PickerEntry.App -> listOf(PickerRow.AppRow(entry.app))
                is PickerEntry.Folder -> {
                    val folder = entry.folder
                    if (!folder.shown && folder.name !in revealed) {
                        emptyList()
                    } else {
                        val isOpen = folder.name in open
                        listOf(PickerRow.FolderRow(folder, isOpen)) +
                            if (isOpen) folder.apps.map { PickerRow.AppRow(it, folder.name) } else emptyList()
                    }
                }
            }
        }
}
