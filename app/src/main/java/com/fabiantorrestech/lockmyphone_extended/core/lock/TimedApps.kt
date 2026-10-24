package com.fabiantorrestech.lockmyphone_extended.core.lock

import org.json.JSONArray
import org.json.JSONObject

/** Timed apps that share one session and cooldown. */
data class TimeBank(val name: String, val packages: Set<String>)

/**
 * A Timed apps lock: only the allowed apps work, as in a normal lock, and they're used in
 * sessions with growing cooldowns ([LimitPolicy]). Each [TimeBank] has its own sessions and
 * cooldowns; a timed app in none of them is in the shared bank ([SHARED]).
 *
 * @param untimed allowed apps that never count and work during a cooldown. The phone and the
 *   system are always untimed.
 */
data class TimedApps(val untimed: Set<String> = emptySet(), val banks: List<TimeBank> = emptyList()) {

    /** The bank [packageName] counts in. */
    fun bankOf(packageName: String): String = banks.firstOrNull { packageName in it.packages }?.name ?: SHARED

    companion object {
        /** The bank of timed apps that aren't in a named one. */
        const val SHARED = ""
    }
}

/** [TimedApps], its banks and their [LimitState]s as JSON, for the database and a running lock. */
object TimedAppsJson {
    fun writeBanks(banks: List<TimeBank>): String = JSONArray().apply {
        banks.forEach { bank -> put(JSONObject().put("name", bank.name).put("packages", JSONArray(bank.packages.sorted()))) }
    }.toString()

    fun readBanks(text: String?): List<TimeBank> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val packages = o.optJSONArray("packages")
            TimeBank(name, (0 until (packages?.length() ?: 0)).map { packages!!.getString(it) }.toSet())
        }.distinctBy { it.name }
    }

    fun write(timed: TimedApps?): String? = timed?.let {
        JSONObject().put("untimed", JSONArray(it.untimed.sorted())).put("banks", JSONArray(writeBanks(it.banks))).toString()
    }

    fun read(text: String?): TimedApps? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val untimed = o.optJSONArray("untimed")
        return TimedApps(
            untimed = (0 until (untimed?.length() ?: 0)).map { untimed!!.getString(it) }.toSet(),
            banks = readBanks(o.optJSONArray("banks")?.toString()),
        )
    }

    fun writeStates(states: Map<String, LimitState>): String = JSONObject().apply {
        states.forEach { (bank, s) ->
            put(
                bank,
                JSONObject()
                    .put("inSession", s.inSession)
                    .put("usedMs", s.usedMs)
                    .put("countingSinceMs", s.countingSinceMs ?: JSONObject.NULL)
                    .put("screenOffAtMs", s.screenOffAtMs ?: JSONObject.NULL)
                    .put("sessionsDone", s.sessionsDone)
                    .put("peeksUsed", s.peeksUsed)
                    .put("cooldownEndsAtMs", s.cooldownEndsAtMs ?: JSONObject.NULL),
            )
        }
    }.toString()

    fun readStates(text: String?): Map<String, LimitState> {
        if (text.isNullOrBlank()) return emptyMap()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
        return o.keys().asSequence().mapNotNull { bank ->
            val s = o.optJSONObject(bank) ?: return@mapNotNull null
            bank to LimitState(
                inSession = s.optBoolean("inSession"),
                usedMs = s.optLong("usedMs"),
                countingSinceMs = s.longOrNull("countingSinceMs"),
                screenOffAtMs = s.longOrNull("screenOffAtMs"),
                sessionsDone = s.optInt("sessionsDone"),
                peeksUsed = s.optInt("peeksUsed"),
                cooldownEndsAtMs = s.longOrNull("cooldownEndsAtMs"),
            )
        }.toMap()
    }
}
