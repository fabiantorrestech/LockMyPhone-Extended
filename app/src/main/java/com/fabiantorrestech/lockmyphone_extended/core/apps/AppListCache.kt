package com.fabiantorrestech.lockmyphone_extended.core.apps

import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Saves the launchable-app list to disk, so reopening the list after the process was killed
 * doesn't have to re-read every app label. [AppCatalog] decides whether a snapshot is still
 * valid.
 */
class AppListCache(file: File) {

    /**
     * @param bootCount `Settings.Global.BOOT_COUNT` when the snapshot was taken. Package
     *   sequence numbers restart on every boot, so a different boot count means the snapshot is stale.
     * @param sequenceNumber the `PackageManager.getChangedPackages` sequence number at snapshot time.
     */
    data class Snapshot(
        val bootCount: Int,
        val sequenceNumber: Int,
        val apps: List<LaunchableApp>,
    )

    private val atomicFile = AtomicFile(file)

    /** Returns null if there is no cache or it can't be read. */
    fun read(): Snapshot? = try {
        val json = JSONObject(String(atomicFile.readFully(), Charsets.UTF_8))
        val appsJson = json.getJSONArray(KEY_APPS)
        val apps = List(appsJson.length()) { i ->
            val app = appsJson.getJSONObject(i)
            LaunchableApp(app.getString(KEY_PACKAGE), app.getString(KEY_LABEL))
        }
        Snapshot(json.getInt(KEY_BOOT_COUNT), json.getInt(KEY_SEQUENCE), apps)
    } catch (e: IOException) {
        null // no cache yet
    } catch (e: JSONException) {
        Log.w(TAG, "App list cache is corrupt; rebuilding", e)
        null
    }

    fun write(snapshot: Snapshot) {
        val appsJson = JSONArray()
        snapshot.apps.forEach { app ->
            appsJson.put(JSONObject().put(KEY_PACKAGE, app.packageName).put(KEY_LABEL, app.label))
        }
        val json = JSONObject()
            .put(KEY_BOOT_COUNT, snapshot.bootCount)
            .put(KEY_SEQUENCE, snapshot.sequenceNumber)
            .put(KEY_APPS, appsJson)

        val stream = try {
            atomicFile.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "Can't write app list cache", e)
            return
        }
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: IOException) {
            atomicFile.failWrite(stream)
            Log.w(TAG, "Can't write app list cache", e)
        }
    }

    private companion object {
        const val TAG = "AppListCache"
        const val KEY_BOOT_COUNT = "bootCount"
        const val KEY_SEQUENCE = "sequenceNumber"
        const val KEY_APPS = "apps"
        const val KEY_PACKAGE = "pkg"
        const val KEY_LABEL = "label"
    }
}
