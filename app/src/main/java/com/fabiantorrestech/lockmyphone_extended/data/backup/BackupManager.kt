package com.fabiantorrestech.lockmyphone_extended.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.InvalidationTracker
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync
import com.fabiantorrestech.lockmyphone_extended.data.BACKUP_SETTING_KEYS
import com.fabiantorrestech.lockmyphone_extended.data.SettingsStore
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarReader
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.DeviceCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What a restore will need attention for, shown before the user confirms. */
data class RestoreWarnings(
    /** Calendars the backup's calendar triggers use that this phone doesn't have (by account + name). */
    val missingCalendars: List<DeviceCalendar>,
    /** Calendar access isn't granted, so calendars can't be matched yet. */
    val calendarAccessMissing: Boolean,
    val alarmTriggerCount: Int,
    val calendarTriggerCount: Int,
    /** NFC tags work on any phone, but only one with NFC hardware. */
    val nfcTagsWithoutNfc: Boolean,
)

/**
 * Backs up everything (settings, profiles, schedules, alarm/location/calendar triggers, NFC tags) to a
 * folder the user picked, restores from a backup file (replacing everything), and runs the
 * auto backup: 10 minutes after the last change, then idle until the next change.
 * The stop token is never included.
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: () -> SettingsStore,
    /** Re-arms schedules, places, calendars and alarm checks after a restore. */
    private val onRestored: suspend () -> Unit,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val calendarReader = CalendarReader(context)
    private val autoIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            AUTO_REQUEST_CODE,
            Intent(context, AutoBackupReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Kept as fields: the database holds observers strongly, the preferences only weakly.
    private val databaseObserver = object : InvalidationTracker.Observer(WATCHED_TABLES) {
        override fun onInvalidated(tables: Set<String>) = onDataChanged()
    }
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key !in BACKUP_SETTING_KEYS) onDataChanged()
    }

    /** Starts noticing changes for the auto backup. Call once, after the first unlock. */
    fun watchForChanges() {
        database.invalidationTracker.addObserver(databaseObserver)
        settings().addChangeListener(settingsListener)
    }

    /** Something changed: (re)arm the auto backup for 10 minutes from now. */
    private fun onDataChanged() {
        val store = settings()
        if (!store.autoBackupEnabled.value || autoFolder() == null) return
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + AUTO_DELAY_MS, autoIntent)
    }

    /** Auto backups go to their own folder if one is set, else to the backup folder. */
    private fun autoFolder(): String? = settings().autoBackupFolderUri.value ?: settings().backupFolderUri.value

    /** Writes a backup file to the chosen folder. Returns its file name. */
    suspend fun backupNow(auto: Boolean): Result<String> = runCatching {
        val chosen = if (auto) autoFolder() else settings().backupFolderUri.value
        val folder = Uri.parse(chosen ?: error("No backup folder chosen"))
        val text = BackupJson.write(buildContents())
        val prefix = if (auto) AUTO_PREFIX else MANUAL_PREFIX
        val name = prefix + LocalDateTime.now().format(FILE_TIME) + ".json"
        withContext(Dispatchers.IO) {
            writeFile(folder, name, text)
            if (auto) pruneAutoBackups(folder)
        }
        settings().setLastBackupAt(System.currentTimeMillis())
        Log.i(TAG, "Backed up to $name")
        name
    }.onFailure { Log.w(TAG, "Backup failed", it) }

    suspend fun readBackup(file: Uri): BackupContents = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(file)?.use { it.readBytes().decodeToString() }
            ?: error("Can't open the file")
        BackupJson.read(text)
    }

    fun warningsFor(contents: BackupContents): RestoreWarnings {
        val local = calendarReader.calendars()
        val used = contents.calendarTriggers.flatMap { it.calendarIds }.toSet()
        val missing = contents.calendars
            .filter { it.id.toString() in used }
            .filter { wanted -> local.none { it.account == wanted.account && it.name == wanted.name } }
        return RestoreWarnings(
            missingCalendars = missing,
            calendarAccessMissing = contents.calendarTriggers.isNotEmpty() && !calendarReader.hasPermission(),
            alarmTriggerCount = contents.alarmTriggers.size,
            calendarTriggerCount = contents.calendarTriggers.size,
            nfcTagsWithoutNfc = contents.nfcTags.isNotEmpty() && android.nfc.NfcAdapter.getDefaultAdapter(context) == null,
        )
    }

    /** Replaces everything in the app with [contents]. */
    suspend fun restore(contents: BackupContents) {
        val calendars = withContext(Dispatchers.IO) { remapCalendars(contents) }
        withContext(Dispatchers.IO) {
            database.backupDao().replaceAll(
                profiles = contents.profiles,
                schedules = contents.schedules,
                alarmTriggers = contents.alarmTriggers,
                locations = contents.locations,
                slots = contents.locationSlots,
                calendarTriggers = calendars,
                nfcTags = contents.nfcTags,
            )
        }
        settings().restore(contents.settings)
        onRestored()
        Log.i(TAG, "Restored a backup from ${contents.appVersion}")
    }

    /** The folder's display name, for the settings row. */
    fun folderName(folder: Uri): String? = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private suspend fun buildContents(): BackupContents = withContext(Dispatchers.IO) {
        val calendarTriggers = database.calendarTriggerDao().getAll()
        val usedCalendarIds = calendarTriggers.flatMap { it.calendarIds }.toSet()
        BackupContents(
            formatVersion = BackupJson.FORMAT_VERSION,
            appVersion = appVersion(),
            createdAtMs = System.currentTimeMillis(),
            settings = settings().snapshot(),
            profiles = database.profileDao().getAll(),
            schedules = database.scheduleDao().getAll(),
            alarmTriggers = database.alarmTriggerDao().getAll(),
            locations = database.locationTriggerDao().getAll(),
            locationSlots = database.locationSlotDao().getAll(),
            calendarTriggers = calendarTriggers,
            calendars = calendarReader.calendars().filter { it.id.toString() in usedCalendarIds },
            nfcTags = database.nfcTagDao().getAll(),
        )
    }

    /** Calendar IDs differ between phones: point each trigger at this phone's calendar with the same account + name. */
    private fun remapCalendars(contents: BackupContents): List<com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger> {
        val local = calendarReader.calendars()
        val newIdByOld = contents.calendars.mapNotNull { old ->
            local.firstOrNull { it.account == old.account && it.name == old.name }?.let { old.id.toString() to it.id.toString() }
        }.toMap()
        // An unmatched calendar keeps its old ID, so the trigger matches nothing rather than every calendar.
        return contents.calendarTriggers.map { trigger ->
            trigger.copy(calendarIds = trigger.calendarIds.map { newIdByOld[it] ?: it }.toSet())
        }
    }

    private fun writeFile(folder: Uri, name: String, text: String) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val file = DocumentsContract.createDocument(context.contentResolver, parent, MIME_JSON, name)
            ?: error("Can't create $name in the backup folder")
        context.contentResolver.openOutputStream(file, "w")?.use { it.write(text.encodeToByteArray()) }
            ?: error("Can't write $name")
    }

    /** Keeps the newest [AUTO_KEEP] auto backups; manual backups are never deleted. */
    private fun pruneAutoBackups(folder: Uri) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val autos = context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1).orEmpty()
                    if (name.startsWith(AUTO_PREFIX)) add(cursor.getString(0) to name)
                }
            }
        }.orEmpty()
        // The file names carry a sortable timestamp, so sorting by name sorts by age.
        autos.sortedByDescending { it.second }.drop(AUTO_KEEP).forEach { (documentId, _) ->
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(folder, documentId))
            }
        }
    }

    private fun appVersion(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    companion object {
        private const val TAG = "Backup"
        private const val MIME_JSON = "application/json"
        const val MANUAL_PREFIX = "LockMyPhone-backup-"
        const val AUTO_PREFIX = "LockMyPhone-autobackup-"
        private const val AUTO_KEEP = 10
        private const val AUTO_DELAY_MS = 10L * 60 * 1_000
        private const val AUTO_REQUEST_CODE = 4201
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
        private val WATCHED_TABLES = arrayOf(
            "profiles", "schedules", "alarm_triggers", "location_triggers", "location_slots", "calendar_triggers", "nfc_tags",
        )
    }
}

/** Fires 10 minutes after the last change and writes one auto backup (to the auto folder, else the backup folder). */
class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = LockApp.container(context)
        if (!container.settingsStore.autoBackupEnabled.value) return
        launchAsync(container.appScope) { container.backupManager.backupNow(auto = true) }
    }
}
