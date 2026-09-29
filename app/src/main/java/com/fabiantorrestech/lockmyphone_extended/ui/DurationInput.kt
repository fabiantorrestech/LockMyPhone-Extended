package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R

/** Text state of an hours : minutes : seconds input. [durationMs] is null while invalid. */
@Stable
class DurationInputState(initialMs: Long) {
    private val initialSeconds = initialMs / 1_000
    var hoursText by mutableStateOf((initialSeconds / 3600).toString())
    var minutesText by mutableStateOf(((initialSeconds % 3600) / 60).toString())
    var secondsText by mutableStateOf((initialSeconds % 60).toString())

    val hours: Long? get() = hoursText.toLongOrNull()
    val minutes: Long? get() = minutesText.toLongOrNull()?.takeIf { it in 0..59 }
    val seconds: Long? get() = secondsText.toLongOrNull()?.takeIf { it in 0..59 }

    /** Between 1 second and 7 days, or null. */
    val durationMs: Long?
        get() {
            val h = hours ?: return null
            val m = minutes ?: return null
            val s = seconds ?: return null
            return ((h * 3600 + m * 60 + s) * 1_000).takeIf { it in 1_000..MAX_DURATION_MS }
        }

    companion object {
        const val MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}

/** A fresh state whenever [key] changes (e.g. another profile is picked). */
@Composable
fun rememberDurationInputState(initialMs: Long, key: Any? = null): DurationInputState =
    remember(key) { DurationInputState(initialMs) }

/** Three number fields: Hours, Minutes (0–59), Seconds (0–59). */
@Composable
fun DurationInput(state: DurationInputState, modifier: Modifier = Modifier) {
    val invalid = state.durationMs == null
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DurationField(
            value = state.hoursText,
            onValueChange = { state.hoursText = it },
            label = stringResource(R.string.start_hours_label),
            isError = state.hours == null || invalid,
            maxDigits = 3,
            modifier = Modifier.weight(1f),
        )
        DurationField(
            value = state.minutesText,
            onValueChange = { state.minutesText = it },
            label = stringResource(R.string.start_minutes_short_label),
            isError = state.minutes == null || invalid,
            maxDigits = 2,
            modifier = Modifier.weight(1f),
        )
        DurationField(
            value = state.secondsText,
            onValueChange = { state.secondsText = it },
            label = stringResource(R.string.start_seconds_label),
            isError = state.seconds == null || invalid,
            maxDigits = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A digits-only text field. */
@Composable
fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    maxDigits: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        isError = isError,
        modifier = modifier,
    )
}
