package com.fabiantorrestech.lockmyphone_extended.trigger.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar on the phone, e.g. "Work" in someone@gmail.com. */
data class DeviceCalendar(val id: Long, val name: String, val account: String)

/** Reads the phone's calendars and event occurrences (read-only, needs READ_CALENDAR). */
class CalendarReader(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<DeviceCalendar> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(DeviceCalendar(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))
                    }
                }
            }
            .orEmpty()
            .sortedWith(compareBy({ it.account }, { it.name }))
    }

    /** Every event occurrence overlapping [fromMs, toMs]; recurring events are expanded by Android. */
    fun occurrences(fromMs: Long, toMs: Long): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.AVAILABILITY,
        )
        val zone = ZoneId.systemDefault()
        return context.contentResolver.query(uri, projection, null, null, CalendarContract.Instances.BEGIN)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val allDay = cursor.getInt(7) == 1
                        var begin = cursor.getLong(5)
                        var end = cursor.getLong(6)
                        if (allDay) {
                            // All-day events are stored as UTC midnights; lock from local midnight.
                            begin = utcMidnightToLocal(begin, zone)
                            end = utcMidnightToLocal(end, zone)
                        }
                        add(
                            CalendarEvent(
                                eventId = cursor.getLong(0),
                                calendarId = cursor.getLong(1),
                                title = cursor.getString(2).orEmpty(),
                                description = cursor.getString(3).orEmpty(),
                                location = cursor.getString(4).orEmpty(),
                                beginMs = begin,
                                endMs = end,
                                allDay = allDay,
                                declined = cursor.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED,
                                free = cursor.getInt(9) == CalendarContract.Events.AVAILABILITY_FREE,
                            )
                        )
                    }
                }
            }
            .orEmpty()
    }

    private fun utcMidnightToLocal(utcMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
