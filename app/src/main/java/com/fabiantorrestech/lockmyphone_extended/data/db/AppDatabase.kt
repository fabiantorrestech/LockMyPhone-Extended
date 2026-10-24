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

    /**
     * A beacon got a new nickname: every place watching it shows the new name, whichever of its
     * formats the place matches ([address] groups them; null when unknown).
     */
    @Query("UPDATE location_triggers SET beaconName = :name WHERE beaconKey = :key OR (:address IS NOT NULL AND beaconAddress = :address)")
    suspend fun renameBeacon(key: String, address: String?, name: String)

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
    @Query("DELETE FROM beacon_names") suspend fun clearBeaconNames()
    @Query("DELETE FROM alarm_triggers") suspend fun clearAlarmTriggers()
    @Query("DELETE FROM schedules") suspend fun clearSchedules()
    @Query("DELETE FROM profiles") suspend fun clearProfiles()
    @Query("DELETE FROM wake_triggers") suspend fun clearWakeTriggers()

    @Insert suspend fun insertProfiles(items: List<LockProfile>)
    @Insert suspend fun insertSchedules(items: List<LockSchedule>)
    @Insert suspend fun insertAlarmTriggers(items: List<AlarmTrigger>)
    @Insert suspend fun insertLocations(items: List<LocationTrigger>)
    @Insert suspend fun insertLocationSlots(items: List<LocationSlot>)
    @Insert suspend fun insertCalendarTriggers(items: List<CalendarTrigger>)
    @Insert suspend fun insertNfcTags(items: List<NfcTag>)
    @Insert suspend fun insertBeaconNames(items: List<BeaconName>)
    @Insert suspend fun insertWakeTriggers(items: List<WakeTrigger>)

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
        beaconNames: List<BeaconName>,
        wakeTriggers: List<WakeTrigger>,
    ) {
        clearWakeTriggers()
        clearBeaconNames()
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
        insertBeaconNames(beaconNames)
        insertWakeTriggers(wakeTriggers)
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

    @Query("SELECT * FROM nfc_tags WHERE id = :id")
    suspend fun getById(id: Long): NfcTag?

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
interface WakeTriggerDao {
    @Query("SELECT * FROM wake_triggers ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<WakeTrigger>>

    @Query("SELECT * FROM wake_triggers ORDER BY sortOrder, id")
    suspend fun getAll(): List<WakeTrigger>

    @Query("SELECT * FROM wake_triggers WHERE enabled = 1")
    suspend fun getEnabled(): List<WakeTrigger>

    @Query("SELECT * FROM wake_triggers WHERE enabled = 1")
    fun observeEnabled(): Flow<List<WakeTrigger>>

    @Query("SELECT * FROM wake_triggers WHERE id = :id")
    suspend fun getById(id: Long): WakeTrigger?

    @Insert
    suspend fun insert(trigger: WakeTrigger): Long

    @Update
    suspend fun update(trigger: WakeTrigger)

    @Delete
    suspend fun delete(trigger: WakeTrigger)

    /** Marks [day] (ISO date) as the day it locked, without touching the rest (the editor may have it open). */
    @Query("UPDATE wake_triggers SET lastFiredDay = :day WHERE id = :id")
    suspend fun setLastFiredDay(id: Long, day: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM wake_triggers")
    suspend fun nextSortOrder(): Int

    /** Inserts at the end of the list. */
    @Transaction
    suspend fun insertLast(trigger: WakeTrigger): Long = insert(trigger.copy(sortOrder = nextSortOrder()))

    @Query("UPDATE wake_triggers SET sortOrder = :sortOrder WHERE id = :id")
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

    @Query("SELECT * FROM location_slots")
    fun observeAll(): Flow<List<LocationSlot>>

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

    @TypeConverter
    fun fromIdSet(ids: Set<Long>): String = ids.sorted().joinToString(",")

    @TypeConverter
    fun toIdSet(value: String): Set<Long> =
        if (value.isEmpty()) emptySet() else value.split(',').mapNotNull { it.toLongOrNull() }.toSet()

    @TypeConverter
    fun fromWifiNetworks(networks: List<WifiNetwork>): String = WifiNetworksJson.write(networks)

    @TypeConverter
    fun toWifiNetworks(value: String): List<WifiNetwork> = WifiNetworksJson.read(value)
}

/** See ADR 0003: kept in device-protected storage so receivers can read it before the first unlock. */
@Database(
    entities = [LockProfile::class, LockSchedule::class, AlarmTrigger::class, LocationTrigger::class, LocationSlot::class, CalendarTrigger::class, NfcTag::class, BeaconName::class, WakeTrigger::class],
    version = 26,
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
    abstract fun beaconNameDao(): BeaconNameDao
    abstract fun backupDao(): BackupDao
    abstract fun wakeTriggerDao(): WakeTriggerDao

    companion object {
        private const val NAME = "lockmyphone.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.createDeviceProtectedStorageContext(),
                AppDatabase::class.java,
                NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26).build()

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

        /**
         * v17: a tag links to one, several or all locks instead of one profile. The table is rebuilt
         * without the profile foreign key (deleting a profile no longer deletes its tags); each
         * tag keeps its profile (or Quick lock) as its one linked lock.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_NFC_TAGS_V17.replace("`nfc_tags`", "`nfc_tags_new`"))
                db.execSQL(
                    "INSERT INTO nfc_tags_new (id, name, icon, uid, action, duringLock, linkMode, linkedLockIds, " +
                        "durationSec, enabled, sortOrder, secret) " +
                        "SELECT id, name, icon, uid, action, duringLock, 'ONE', COALESCE(CAST(profileId AS TEXT), 'quick'), " +
                        "durationSec, enabled, sortOrder, secret FROM nfc_tags"
                )
                db.execSQL("DROP TABLE nfc_tags")
                db.execSQL("ALTER TABLE nfc_tags_new RENAME TO nfc_tags")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nfc_tags_uid` ON `nfc_tags` (`uid`)")
            }
        }

        /** v18: a Start tag can take over a running manual lock. */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nfc_tags ADD COLUMN takeOver INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v23: several Wi-Fi networks per place (M20). Each place's one router (and its Listen
         * harder router) becomes a one-network list; the old columns stay, unused.
         */
        /** v26: allowed apps can be hidden from the lock-screen list (M24). */
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN hiddenPackages TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v25: Morning lock stands down for alarm locks, counts from the first screen-on, per-day end, late check (M22.1). */
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wake_triggers ADD COLUMN yieldToAlarms INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE wake_triggers ADD COLUMN yieldAlarmIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE wake_triggers ADD COLUMN countFromScreenOn INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE wake_triggers ADD COLUMN altEndMin INTEGER")
                db.execSQL("ALTER TABLE wake_triggers ADD COLUMN altDaysMask INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wake_triggers ADD COLUMN lateCheck INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wake_triggers ADD COLUMN lateCheckAfterMin INTEGER NOT NULL DEFAULT 660")
            }
        }

        /** v24: Morning lock (M22). */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `wake_triggers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT, " +
                        "`enabled` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `profileId` INTEGER, " +
                        "`lockMinutes` INTEGER NOT NULL, `windowStartMin` INTEGER NOT NULL, `windowEndMin` INTEGER NOT NULL, " +
                        "`daysMask` INTEGER NOT NULL, `alarmFirst` INTEGER NOT NULL, `alarmPackages` TEXT NOT NULL, " +
                        "`minSleepMinutes` INTEGER NOT NULL, `sleepPlaceIds` TEXT NOT NULL, `checkCharging` INTEGER NOT NULL, " +
                        "`chargingMinutes` INTEGER NOT NULL, `checkPlace` INTEGER NOT NULL, `lastFiredDay` TEXT, " +
                        "FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_wake_triggers_profileId` ON `wake_triggers` (`profileId`)")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN wifiNetworks TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconBoostWifiNetworks TEXT NOT NULL DEFAULT '[]'")
                db.query("SELECT id, wifiBssid, wifiSsid, beaconBoostWifiBssid, beaconBoostWifiSsid FROM location_triggers").use { cursor ->
                    while (cursor.moveToNext()) {
                        fun text(column: Int): String? = if (cursor.isNull(column)) null else cursor.getString(column)
                        val presence = WifiNetworksJson.legacy(bssid = text(1), ssid = text(2))
                        val boost = WifiNetworksJson.legacy(bssid = text(3), ssid = text(4))
                        if (presence.isEmpty() && boost.isEmpty()) continue
                        db.execSQL(
                            "UPDATE location_triggers SET wifiNetworks = ?, beaconBoostWifiNetworks = ? WHERE id = ?",
                            arrayOf<Any>(WifiNetworksJson.write(presence), WifiNetworksJson.write(boost), cursor.getLong(0)),
                        )
                    }
                }
            }
        }

        /** v22: when a beacon place listens harder before its beacon is heard (M19). */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconBoost TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconBoostZones TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconBoostWifiBssid TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconBoostWifiSsid TEXT")
            }
        }

        /** v21: a beacon place can react faster or slower than the default (M18). */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconPreset TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconArriveSec INTEGER")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconLeaveSec INTEGER")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconRecheckSec INTEGER")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconScanSpeed TEXT")
            }
        }

        /** v20: a beacon place remembers the beacon's address and its 1 m signal (ADR 0006). */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconAddress TEXT")
                db.execSQL("ALTER TABLE location_triggers ADD COLUMN beaconOneMeterDbm INTEGER")
            }
        }

        /** v19: your own names for beacons. Copied from Room's generated schema. */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `beacon_names` (`key` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        /** Copied from Room's generated schema (v17), so the rebuilt table matches a fresh install. */
        private const val CREATE_NFC_TAGS_V17 =
            "CREATE TABLE IF NOT EXISTS `nfc_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`icon` TEXT, " +
                "`uid` TEXT NOT NULL, " +
                "`action` TEXT NOT NULL, " +
                "`duringLock` TEXT NOT NULL, " +
                "`linkMode` TEXT NOT NULL, " +
                "`linkedLockIds` TEXT NOT NULL, " +
                "`durationSec` INTEGER, " +
                "`enabled` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`secret` TEXT)"

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
