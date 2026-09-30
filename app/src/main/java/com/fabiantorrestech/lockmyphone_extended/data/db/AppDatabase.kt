package com.fabiantorrestech.lockmyphone_extended.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<LockProfile>>

    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    suspend fun getAll(): List<LockProfile>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM profiles")
    suspend fun nextSortOrder(): Int

    /** Inserts at the end of the list. */
    @Transaction
    suspend fun insertLast(profile: LockProfile): Long = insert(profile.copy(sortOrder = nextSortOrder()))

    @Query("UPDATE profiles SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** Saves a new list order: [ids] from top to bottom. */
    @Transaction
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): LockProfile?

    @Query("SELECT * FROM profiles WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): LockProfile?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Insert
    suspend fun insert(profile: LockProfile): Long

    @Update
    suspend fun update(profile: LockProfile)

    @Delete
    suspend fun delete(profile: LockProfile)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<LockSchedule>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM schedules")
    suspend fun nextSortOrder(): Int

    /** Inserts at the end of the list. */
    @Transaction
    suspend fun insertLast(schedule: LockSchedule): Long = insert(schedule.copy(sortOrder = nextSortOrder()))

    @Query("UPDATE schedules SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** Saves a new list order: [ids] from top to bottom. */
    @Transaction
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("SELECT * FROM schedules WHERE enabled = 1")
    suspend fun getEnabled(): List<LockSchedule>

    @Query("SELECT * FROM schedules ORDER BY sortOrder, id")
    suspend fun getAll(): List<LockSchedule>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): LockSchedule?

    @Insert
    suspend fun insert(schedule: LockSchedule): Long

    @Update
    suspend fun update(schedule: LockSchedule)

    @Delete
    suspend fun delete(schedule: LockSchedule)
}

@Dao
interface LocationTriggerDao {
    @Query("SELECT * FROM location_triggers ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<LocationTrigger>>

    @Query("SELECT * FROM location_triggers WHERE enabled = 1")
    suspend fun getEnabled(): List<LocationTrigger>

    @Query("SELECT * FROM location_triggers ORDER BY sortOrder, id")
    suspend fun getAll(): List<LocationTrigger>

    @Query("SELECT * FROM location_triggers WHERE id = :id")
    suspend fun getById(id: Long): LocationTrigger?

    @Insert
    suspend fun insert(trigger: LocationTrigger): Long

    @Update
    suspend fun update(trigger: LocationTrigger)

    @Delete
    suspend fun deleteOnly(trigger: LocationTrigger)

    @Query("UPDATE schedules SET requiredPlaceId = NULL WHERE requiredPlaceId = :placeId")
    suspend fun clearScheduleConditions(placeId: Long)

    @Query("UPDATE calendar_triggers SET requiredPlaceId = NULL WHERE requiredPlaceId = :placeId")
    suspend fun clearCalendarConditions(placeId: Long)

    /** Deletes a place; schedules and calendar triggers that required it go back to "anywhere". */
    @Transaction
    suspend fun delete(trigger: LocationTrigger) {
        clearScheduleConditions(trigger.id)
        clearCalendarConditions(trigger.id)
        deleteOnly(trigger)
    }

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM location_triggers")
    suspend fun nextSortOrder(): Int

    /** Inserts at the end of the list. */
    @Transaction
    suspend fun insertLast(trigger: LocationTrigger): Long = insert(trigger.copy(sortOrder = nextSortOrder()))

    @Query("UPDATE location_triggers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** Saves a new list order: [ids] from top to bottom. */
    @Transaction
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}

/** Restore: swaps every table's rows for a backup's, in one transaction. */
@Dao
interface BackupDao {
    @Query("DELETE FROM location_slots") suspend fun clearLocationSlots()
    @Query("DELETE FROM location_triggers") suspend fun clearLocations()
    @Query("DELETE FROM calendar_triggers") suspend fun clearCalendarTriggers()
    @Query("DELETE FROM nfc_tags") suspend fun clearNfcTags()
    @Query("DELETE FROM alarm_triggers") suspend fun clearAlarmTriggers()
    @Query("DELETE FROM schedules") suspend fun clearSchedules()
    @Query("DELETE FROM profiles") suspend fun clearProfiles()

    @Insert suspend fun insertProfiles(items: List<LockProfile>)
    @Insert suspend fun insertSchedules(items: List<LockSchedule>)
    @Insert suspend fun insertAlarmTriggers(items: List<AlarmTrigger>)
    @Insert suspend fun insertLocations(items: List<LocationTrigger>)
    @Insert suspend fun insertLocationSlots(items: List<LocationSlot>)
    @Insert suspend fun insertCalendarTriggers(items: List<CalendarTrigger>)
    @Insert suspend fun insertNfcTags(items: List<NfcTag>)

    /** Children are cleared first and parents inserted first, so the foreign keys always hold. IDs are kept. */
    @Transaction
    suspend fun replaceAll(
        profiles: List<LockProfile>,
        schedules: List<LockSchedule>,
        alarmTriggers: List<AlarmTrigger>,
        locations: List<LocationTrigger>,
        slots: List<LocationSlot>,
        calendarTriggers: List<CalendarTrigger>,
        nfcTags: List<NfcTag>,
    ) {
        clearNfcTags()
        clearLocationSlots()
        clearLocations()
        clearCalendarTriggers()
        clearAlarmTriggers()
        clearSchedules()
        clearProfiles()
        insertProfiles(profiles)
        insertSchedules(schedules)
        insertAlarmTriggers(alarmTriggers)
        insertLocations(locations)
        insertLocationSlots(slots)
        insertCalendarTriggers(calendarTriggers)
        insertNfcTags(nfcTags)
    }
}

@Dao
interface NfcTagDao {
    @Query("SELECT * FROM nfc_tags ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<NfcTag>>

    @Query("SELECT * FROM nfc_tags ORDER BY sortOrder, id")
    suspend fun getAll(): List<NfcTag>

    @Query("SELECT * FROM nfc_tags WHERE uid = :uid")
    suspend fun findByUid(uid: String): NfcTag?

    @Insert
    suspend fun insert(tag: NfcTag): Long

    @Update
    suspend fun update(tag: NfcTag)

    @Delete
    suspend fun delete(tag: NfcTag)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM nfc_tags")
    suspend fun nextSortOrder(): Int

    /** Inserts at the end of the list. */
    @Transaction
    suspend fun insertLast(tag: NfcTag): Long = insert(tag.copy(sortOrder = nextSortOrder()))

    @Query("UPDATE nfc_tags SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** Saves a new list order: [ids] from top to bottom. */
    @Transaction
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}

@Dao
interface CalendarTriggerDao {
    @Query("SELECT * FROM calendar_triggers ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CalendarTrigger>>

    @Query("SELECT * FROM calendar_triggers ORDER BY sortOrder, id")
    suspend fun getAll(): List<CalendarTrigger>

    @Query("SELECT * FROM calendar_triggers WHERE enabled = 1")
    suspend fun getEnabled(): List<CalendarTrigger>

    @Insert
    suspend fun insert(trigger: CalendarTrigger): Long

    @Update
    suspend fun update(trigger: CalendarTrigger)

    @Delete
    suspend fun delete(trigger: CalendarTrigger)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM calendar_triggers")
    suspend fun nextSortOrder(): Int

    /** Inserts at the end of the list. */
    @Transaction
    suspend fun insertLast(trigger: CalendarTrigger): Long = insert(trigger.copy(sortOrder = nextSortOrder()))

    @Query("UPDATE calendar_triggers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** Saves a new list order: [ids] from top to bottom. */
    @Transaction
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}

@Dao
interface LocationSlotDao {
    @Query("SELECT * FROM location_slots WHERE locationId = :locationId ORDER BY startMinuteOfDay")
    suspend fun getForLocation(locationId: Long): List<LocationSlot>

    @Query("SELECT * FROM location_slots")
    suspend fun getAll(): List<LocationSlot>

    @Query("DELETE FROM location_slots WHERE locationId = :locationId")
    suspend fun deleteForLocation(locationId: Long)

    @Insert
    suspend fun insertAll(slots: List<LocationSlot>)

    /** Saves a place's slots as edited: the old ones are replaced. */
    @Transaction
    suspend fun replaceForLocation(locationId: Long, slots: List<LocationSlot>) {
        deleteForLocation(locationId)
        insertAll(slots.map { it.copy(id = 0, locationId = locationId) })
    }
}

@Dao
interface AlarmTriggerDao {
    @Query("SELECT * FROM alarm_triggers ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AlarmTrigger>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM alarm_triggers")
    suspend fun nextSortOrder(): Int

    /** Inserts at the end of the list. */
    @Transaction
    suspend fun insertLast(trigger: AlarmTrigger): Long = insert(trigger.copy(sortOrder = nextSortOrder()))

    @Query("UPDATE alarm_triggers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** Saves a new list order: [ids] from top to bottom. */
    @Transaction
    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Query("SELECT * FROM alarm_triggers WHERE enabled = 1")
    fun observeEnabled(): Flow<List<AlarmTrigger>>

    @Query("SELECT * FROM alarm_triggers ORDER BY sortOrder, id")
    suspend fun getAll(): List<AlarmTrigger>

    @Query("SELECT * FROM alarm_triggers WHERE id = :id")
    suspend fun getById(id: Long): AlarmTrigger?

    @Insert
    suspend fun insert(trigger: AlarmTrigger): Long

    @Update
    suspend fun update(trigger: AlarmTrigger)

    @Delete
    suspend fun delete(trigger: AlarmTrigger)
}

class Converters {
    // Package names never contain '|', so it's a safe separator.
    @TypeConverter
    fun fromPackageSet(packages: Set<String>): String = packages.sorted().joinToString("|")

    @TypeConverter
    fun toPackageSet(value: String): Set<String> =
        if (value.isEmpty()) emptySet() else value.split('|').toSet()
}

/** See ADR 0003: kept in device-protected storage so receivers can read it before the first unlock. */
@Database(
    entities = [LockProfile::class, LockSchedule::class, AlarmTrigger::class, LocationTrigger::class, LocationSlot::class, CalendarTrigger::class, NfcTag::class],
    version = 16,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun alarmTriggerDao(): AlarmTriggerDao
    abstract fun locationTriggerDao(): LocationTriggerDao
    abstract fun locationSlotDao(): LocationSlotDao
    abstract fun calendarTriggerDao(): CalendarTriggerDao
    abstract fun nfcTagDao(): NfcTagDao
    abstract fun backupDao(): BackupDao

    companion object {
        private const val NAME = "lockmyphone.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.createDeviceProtectedStorageContext(),
                AppDatabase::class.java,
                NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16).build()

        /** v2: profiles gain a Do Not Disturb switch (off for existing profiles). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN dndEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3: alarm-dismiss triggers. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `alarm_triggers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, `channelId` TEXT, `notificationId` INTEGER, " +
                        "`title` TEXT, `text` TEXT, `matchMode` TEXT NOT NULL, `profileId` INTEGER, " +
                        "`delayMinutes` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_triggers_profileId` ON `alarm_triggers` (`profileId`)")
            }
        }

        /** v4: how often a trigger's alarm is expected to ring (for "may have been deleted" warnings). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN expectedEveryDays INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v5: what unlocking after a screen-off inside an allowed app does. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN unlockResumeMode TEXT NOT NULL DEFAULT 'RESUME_APP'")
                db.execSQL("ALTER TABLE profiles ADD COLUMN unlockChoiceSeconds INTEGER NOT NULL DEFAULT 3")
            }
        }

        /** v6: how strict a profile's Do Not Disturb is. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN dndLevel TEXT NOT NULL DEFAULT 'PRIORITY'")
            }
        }

        /** v7: an optional emoji icon per profile. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN icon TEXT")
            }
        }

        /**
         * v8: list order set by dragging, seeded from the order shown until now (profiles and
         * triggers by id, schedules by start time), plus icons for schedules and triggers.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE profiles SET sortOrder = id")
                db.execSQL("ALTER TABLE schedules ADD COLUMN icon TEXT")
                db.execSQL("ALTER TABLE schedules ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE schedules SET sortOrder = (SELECT COUNT(*) FROM schedules AS s " +
                        "WHERE s.startMinuteOfDay < schedules.startMinuteOfDay " +
                        "OR (s.startMinuteOfDay = schedules.startMinuteOfDay AND s.id < schedules.id))"
                )
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN icon TEXT")
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE alarm_triggers SET sortOrder = id")
            }
        }

        /** v9: grayscale per profile; "first alarm of the day" triggers and day rules. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN grayscale INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN kind TEXT NOT NULL DEFAULT 'SPECIFIC_ALARM'")
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN firstAlarmPackages TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN activeRule TEXT NOT NULL DEFAULT 'EVERY_DAY'")
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN daysMask INTEGER NOT NULL DEFAULT 127")
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN dates TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE alarm_triggers ADD COLUMN lastFiredDay TEXT")
            }
        }

        /** v10: location triggers (ADR 0004). */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_LOCATION_TRIGGERS)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_triggers_profileId` ON `location_triggers` (`profileId`)")
            }
        }

        /** v11: time slots for locations, and how GPS and Wi-Fi combine. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN matchMode TEXT NOT NULL DEFAULT 'GPS_ONLY'")
                // Until now a Wi-Fi network, when set, decided on its own.
                db.execSQL("UPDATE location_triggers SET matchMode = 'WIFI_ONLY' WHERE wifiBssid IS NOT NULL")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN lockOnReturn INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN stayLockedAfterLeaving INTEGER NOT NULL DEFAULT 0")
                db.execSQL(CREATE_LOCATION_SLOTS)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_slots_locationId` ON `location_slots` (`locationId`)")
            }
        }

        /** v12: calendar triggers. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_CALENDAR_TRIGGERS)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_calendar_triggers_profileId` ON `calendar_triggers` (`profileId`)")
            }
        }

        /**
         * v13: places decide "here" from any mix of signals (GPS, Wi-Fi, beacon, charger), and
         * schedules / calendar triggers can require a place. The old matchMode becomes signals.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN signals TEXT NOT NULL DEFAULT 'GPS'")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN requireAll INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconKey TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconName TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconRssiThreshold INTEGER NOT NULL DEFAULT -75")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN chargerMode TEXT NOT NULL DEFAULT 'ANY'")
                db.execSQL(
                    "UPDATE location_triggers SET " +
                        "signals = CASE matchMode WHEN 'WIFI_ONLY' THEN 'WIFI' " +
                        "WHEN 'GPS_AND_WIFI' THEN 'GPS|WIFI' WHEN 'GPS_OR_WIFI' THEN 'GPS|WIFI' ELSE 'GPS' END, " +
                        "requireAll = CASE matchMode WHEN 'GPS_OR_WIFI' THEN 0 ELSE 1 END"
                )
                db.execSQL("ALTER TABLE schedules ADD COLUMN requiredPlaceId INTEGER")
                db.execSQL("ALTER TABLE calendar_triggers ADD COLUMN requiredPlaceId INTEGER")
            }
        }

        /** v14: a paired Bluetooth device as a place signal (connected to it, or near it). */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN btAddress TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN btName TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN btDetection TEXT NOT NULL DEFAULT 'CONNECTED'")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN btRssiThreshold INTEGER NOT NULL DEFAULT -75")
            }
        }

        /** v15: NFC tags that start or end locks. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_NFC_TAGS)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_nfc_tags_profileId` ON `nfc_tags` (`profileId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nfc_tags_uid` ON `nfc_tags` (`uid`)")
            }
        }

        /** v16: only written tags (with their secret code) and chosen tags can end a lock. */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nfc_tags ADD COLUMN secret TEXT")
                db.execSQL("ALTER TABLE profiles ADD COLUMN nfcUnlockTagIds TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Copied from Room's generated schema (v15), so the migrated table matches a fresh install. */
        private const val CREATE_NFC_TAGS =
            "CREATE TABLE IF NOT EXISTS `nfc_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`icon` TEXT, " +
                "`uid` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, " +
                "`duringLock` TEXT NOT NULL, " +
                "`profileId` INTEGER, " +
                "`durationSec` INTEGER, " +
                "`enabled` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        /** Copied from Room's generated schema, so the migrated table matches a fresh install. */
        private const val CREATE_CALENDAR_TRIGGERS =
            "CREATE TABLE IF NOT EXISTS `calendar_triggers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`icon` TEXT, " +
                "`enabled` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`calendarIds` TEXT NOT NULL, " +
                "`keywords` TEXT NOT NULL, " +
                "`searchNotesAndLocation` INTEGER NOT NULL, " +
                "`includeAllDay` INTEGER NOT NULL, " +
                "`skipDeclined` INTEGER NOT NULL, " +
                "`skipFree` INTEGER NOT NULL, " +
                "`lengthMode` TEXT NOT NULL, " +
                "`fixedDurationSec` INTEGER NOT NULL, " +
                "`startEarlyMinutes` INTEGER NOT NULL, " +
                "`profileId` INTEGER, " +
                "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        /** Copied from Room's generated schema, so the migrated table matches a fresh install. */
        private const val CREATE_LOCATION_SLOTS =
            "CREATE TABLE IF NOT EXISTS `location_slots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`locationId` INTEGER NOT NULL, " +
                "`daysMask` INTEGER NOT NULL, " +
                "`startMinuteOfDay` INTEGER NOT NULL, " +
                "`endMinuteOfDay` INTEGER NOT NULL, " +
                "FOREIGN KEY(`locationId`) REFERENCES `location_triggers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        /** Copied from Room's generated schema, so the migrated table matches a fresh install. */
        private const val CREATE_LOCATION_TRIGGERS =
            "CREATE TABLE IF NOT EXISTS `location_triggers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`icon` TEXT, " +
                "`enabled` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`latitude` REAL NOT NULL, " +
                "`longitude` REAL NOT NULL, " +
                "`radiusMeters` INTEGER NOT NULL, " +
                "`wifiBssid` TEXT, " +
                "`wifiSsid` TEXT, " +
                "`mode` TEXT NOT NULL, " +
                "`profileId` INTEGER, " +
                "`activeRule` TEXT NOT NULL, " +
                "`daysMask` INTEGER NOT NULL, " +
                "`dates` TEXT NOT NULL, " +
                "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    }
}
