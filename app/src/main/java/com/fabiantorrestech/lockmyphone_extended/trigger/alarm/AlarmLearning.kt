package com.fabiantorrestech.lockmyphone_extended.trigger.alarm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Learn" step of the alarm-trigger editor. The editor starts learning for one app, and
 * the notification listener reports the next ringing alarm from that app.
 * It's in memory only.
 */
class AlarmLearning(private val clock: () -> Long = System::currentTimeMillis) {

    sealed interface State {
        data object Idle : State
        /** @param rejectedTimer a timer rang instead of an alarm; the editor says so and keeps waiting. */
        data class Waiting(val packageName: String, val sinceMs: Long, val rejectedTimer: Boolean = false) : State
        data class Learned(val fingerprint: AlarmFingerprint) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(packageName: String) {
        _state.value = State.Waiting(packageName, clock())
    }

    fun cancel() {
        _state.value = State.Idle
    }

    /** The app the listener should watch right now, or null (also after the timeout). */
    fun waitingFor(): String? {
        val waiting = _state.value as? State.Waiting ?: return null
        if (clock() - waiting.sinceMs > TIMEOUT_MS) {
            _state.value = State.Idle
            return null
        }
        return waiting.packageName
    }

    fun onRingingAlarm(fingerprint: AlarmFingerprint) {
        val waiting = _state.value as? State.Waiting ?: return
        if (waitingFor() != fingerprint.packageName) return
        // A timer or stopwatch must never become a trigger: keep waiting for a real alarm.
        _state.value = if (AlarmKinds.isAlarm(fingerprint)) {
            State.Learned(fingerprint)
        } else {
            waiting.copy(rejectedTimer = true)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15L * 60 * 1_000
    }
}
