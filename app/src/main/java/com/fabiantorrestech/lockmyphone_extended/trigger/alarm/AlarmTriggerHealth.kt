package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import android.content.Context
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A replacement alarm the app spotted: same app, same time, new internal ID. */
data class AlarmSuggestion(val alarmId: Int, val time: String?)

/** Why a trigger may need attention. Shown as a banner and a notification. */
sealed interface AlarmTriggerProblem {
    val triggerId: Long
    val suggestion: AlarmSuggestion?

    /** Not seen for [days] days, more than its expected interval allows. */
    data class Silent(override val triggerId: Long, val days: Int, override val suggestion: AlarmSuggestion?) : AlarmTriggerProblem

    /** Its alarm vanished and a new alarm appeared at the same time: probably deleted and re-created. */
    data class Recreated(override val triggerId: Long, override val suggestion: AlarmSuggestion) : AlarmTriggerProblem
}

/**
 * Tracks, per alarm trigger, when its alarm was last seen and any clues that it was deleted,
 * and turns them into [problems]. It's stored in device-protected prefs; rule logic lives in
 * [AlarmHealthRules].
 */
class AlarmTriggerHealth(context: Context, private val clock: () -> Long = System::currentTimeMillis) {

    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _problems = MutableStateFlow<Map<Long, AlarmTriggerProblem>>(emptyMap())
    val problems: StateFlow<Map<Long, AlarmTriggerProblem>> = _problems.asStateFlow()

    /** A trigger was saved, re-picked or re-linked: start fresh. */
    fun onTriggerSaved(triggerId: Long) {
        prefs.edit {
            putLong(seenKey(triggerId), clock())
            remove(vanishedKey(triggerId))
            remove(suggestKey(triggerId))
            remove(warnedKey(triggerId))
        }
    }

    /** The trigger's alarm was seen (its upcoming notice, or it rang). */
    fun onSeen(triggerId: Long) {
        prefs.edit {
            putLong(seenKey(triggerId), clock())
            remove(vanishedKey(triggerId))
            remove(suggestKey(triggerId))
            remove(warnedKey(triggerId))
        }
    }

    fun lastSeenMs(triggerId: Long): Long? =
        if (prefs.contains(seenKey(triggerId))) prefs.getLong(seenKey(triggerId), 0L) else null

    /** The trigger's upcoming notice disappeared and didn't come back, and the alarm didn't ring. */
    fun onVanished(triggerId: Long) {
        prefs.edit { putLong(vanishedKey(triggerId), clock()) }
    }

    /**
     * An upcoming notice for an alarm no trigger is linked to. If a trigger of the same app lost its
     * alarm shortly before, and the times match, this new alarm is suggested as its replacement.
     * The sighting is also remembered, for the silence warning's suggestion.
     */
    fun onUnlinkedAlarm(packageName: String, alarmId: Int, time: String?, triggers: List<AlarmTrigger>, timeOf: (AlarmTrigger) -> String?) {
        val now = clock()
        prefs.edit { putString(unlinkedKey(packageName, alarmId), "$now|${time.orEmpty()}") }
        triggers.filter { it.packageName == packageName && it.notificationId != alarmId }.forEach { trigger ->
            val vanishedAt = prefs.getLong(vanishedKey(trigger.id), 0L)
            if (vanishedAt > 0 && now - vanishedAt <= AlarmHealthRules.RECREATE_WINDOW_MS &&
                AlarmHealthRules.sameTime(timeOf(trigger), time)
            ) {
                prefs.edit { putString(suggestKey(trigger.id), "$alarmId|${time.orEmpty()}") }
            }
        }
    }

    /** "It's fine": the user confirms the alarm still exists. */
    fun dismiss(triggerId: Long) = onSeen(triggerId)

    /**
     * Recomputes [problems] for [triggers] and returns the ones not warned about yet, so they can be
     * notified once.
     *
     * @param listening false while notification access is off. Silence is meaningless then, so only
     *   re-creation clues count.
     */
    fun evaluate(triggers: List<AlarmTrigger>, listening: Boolean, timeOf: (AlarmTrigger) -> String?): List<AlarmTriggerProblem> {
        val now = clock()
        val found = mutableMapOf<Long, AlarmTriggerProblem>()
        // A first-alarm trigger follows no particular alarm, so it can't go missing.
        for (trigger in triggers.filter { it.enabled && it.kind == AlarmTriggerKind.SPECIFIC_ALARM }) {
            val suggestion = readSuggestion(trigger.id)
            val lastSeen = lastSeenMs(trigger.id) ?: now.also { prefs.edit { putLong(seenKey(trigger.id), it) } }
            val problem = when {
                suggestion != null -> AlarmTriggerProblem.Recreated(trigger.id, suggestion)
                listening && AlarmHealthRules.isSilent(lastSeen, trigger.expectedEveryDays, now) ->
                    AlarmTriggerProblem.Silent(
                        trigger.id,
                        AlarmHealthRules.daysSince(lastSeen, now),
                        unlinkedSuggestionFor(trigger, timeOf(trigger)),
                    )
                else -> null
            }
            if (problem != null) found[trigger.id] = problem
        }
        _problems.value = found

        return found.values.filter { problem ->
            val key = problem::class.simpleName + (problem.suggestion?.alarmId ?: "")
            val isNew = prefs.getString(warnedKey(problem.triggerId), null) != key
            if (isNew) prefs.edit { putString(warnedKey(problem.triggerId), key) }
            isNew
        }
    }

    private fun readSuggestion(triggerId: Long): AlarmSuggestion? {
        val raw = prefs.getString(suggestKey(triggerId), null) ?: return null
        val id = raw.substringBefore('|').toIntOrNull() ?: return null
        return AlarmSuggestion(id, raw.substringAfter('|').ifEmpty { null })
    }

    /** A recently seen, unlinked alarm of the same app at the trigger's time, if any. */
    private fun unlinkedSuggestionFor(trigger: AlarmTrigger, triggerTime: String?): AlarmSuggestion? {
        if (triggerTime == null) return null
        val prefix = "unlinked_${trigger.packageName}#"
        val windowMs = AlarmHealthRules.silenceLimitMs(trigger.expectedEveryDays)
        return prefs.all.entries
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                val raw = value as? String ?: return@mapNotNull null
                val seenAt = raw.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
                val time = raw.substringAfter('|').ifEmpty { null }
                val alarmId = key.removePrefix(prefix).toIntOrNull() ?: return@mapNotNull null
                if (alarmId != trigger.notificationId && clock() - seenAt <= windowMs && AlarmHealthRules.sameTime(time, triggerTime)) {
                    AlarmSuggestion(alarmId, time)
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private fun seenKey(id: Long) = "seen_$id"
    private fun vanishedKey(id: Long) = "vanished_$id"
    private fun suggestKey(id: Long) = "suggest_$id"
    private fun warnedKey(id: Long) = "warned_$id"
    private fun unlinkedKey(packageName: String, alarmId: Int) = "unlinked_$packageName#$alarmId"

    private companion object {
        const val PREFS_NAME = "alarm_trigger_health"
    }
}
