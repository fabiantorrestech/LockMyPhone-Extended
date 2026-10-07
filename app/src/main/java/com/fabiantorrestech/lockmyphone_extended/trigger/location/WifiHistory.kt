package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A network this phone has connected to, and when it last did. */
data class KnownWifi(val bssid: String, val ssid: String?, val lastConnectedMs: Long)

/**
 * The Wi-Fi networks this phone has connected to, for picking a place's networks. Android 10+
 * doesn't let normal apps read the networks saved in Wi-Fi settings, so the app keeps its own
 * list as it notices connections. Belongs to this phone, so it isn't backed up.
 */
class WifiHistory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Newest first. */
    fun entries(): List<KnownWifi> {
        val array = runCatching { JSONArray(prefs.getString(KEY_ENTRIES, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            KnownWifi(
                bssid = o.optString("bssid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                ssid = if (o.isNull("ssid")) null else o.optString("ssid"),
                lastConnectedMs = o.optLong("lastConnectedMs"),
            )
        }.sortedByDescending { it.lastConnectedMs }
    }

    /**
     * Notes that the phone is connected to [wifi] now. Skipped when that network was already noted
     * lately, so frequent wake-ups don't write each time.
     */
    fun record(wifi: ConnectedWifi, nowMs: Long = System.currentTimeMillis()) {
        val current = entries()
        val previous = current.firstOrNull { it.bssid.equals(wifi.bssid, ignoreCase = true) }
        if (previous != null && previous.ssid == wifi.ssid && nowMs - previous.lastConnectedMs < REFRESH_MS) return
        val updated = (listOf(KnownWifi(wifi.bssid, wifi.ssid, nowMs)) + current.filterNot { it.bssid.equals(wifi.bssid, ignoreCase = true) })
            .take(MAX_ENTRIES)
        val array = JSONArray()
        updated.forEach { entry ->
            array.put(
                JSONObject()
                    .put("bssid", entry.bssid)
                    .put("ssid", entry.ssid ?: JSONObject.NULL)
                    .put("lastConnectedMs", entry.lastConnectedMs)
            )
        }
        prefs.edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "wifi_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 50
        const val REFRESH_MS = 10L * 60 * 1_000
    }
}
