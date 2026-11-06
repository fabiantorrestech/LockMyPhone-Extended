package com.fabiantorrestech.lockmyphone_extended.trigger

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import com.fabiantorrestech.lockmyphone_extended.BuildConfig
import com.fabiantorrestech.lockmyphone_extended.LockApp

/**
 * Read-only list of profile names for a companion app signed with this app's key (ADR 0009). The
 * manifest guards it with the MANAGE_PROFILES signature permission.
 *
 * `content://com.fabiantorrestech.lockmyphone_extended.profiles/profiles` returns one row per
 * profile, in the app's order, with columns `name` and `managedBy` (null unless a companion app
 * keeps that profile in sync, ADR 0008). Nothing else leaves the app, and nothing can be written.
 */
class ProfilesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (MATCHER.match(uri) != PROFILES) return null
        val context = context ?: return null
        // Queries arrive on a binder thread, so reading the database here is fine.
        return LockApp.container(context).database.profileDao().profileNames()
    }

    override fun getType(uri: Uri): String? =
        if (MATCHER.match(uri) == PROFILES) "vnd.android.cursor.dir/vnd.$AUTHORITY.profile" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException(READ_ONLY)

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(READ_ONLY)

    companion object {
        // Per build: the .debug build must not claim the release app's authority.
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".profiles"
        private const val PROFILES = 1
        private const val READ_ONLY = "Profiles are read-only"
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply { addURI(AUTHORITY, "profiles", PROFILES) }
    }
}
