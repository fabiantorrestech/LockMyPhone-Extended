package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.data.PickerStyle
import com.fabiantorrestech.lockmyphone_extended.trigger.location.RefreshUi
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * The countdown, shown instead of the settings while a lock is active, so the configuration
 * can't be changed mid-lock. Back from the allowed-apps picker lands here.
 *
 * @param passButtonsEnabled show Allowed apps / Phone. False while the accessibility service
 *   isn't bound, because a pass couldn't be enforced then.
 * @param choiceDeadlineMs set after an unlock back into an allowed app ("Show the lock screen"):
 *   the phone locks again at this time unless the user picks an app.
 * @param nfcGate the NFC gate is open: a tag can end the lock (or start its early-unlock wait)
 *   before [choiceDeadlineMs].
 * @param refresh the Refresh button's state, or null when this lock wasn't started by a place
 *   that unlocks when you leave.
 * @param pickerStyle / [pickerFont] the clock's size and font, shared with the allowed-apps picker.
 */
@Composable
fun LockedScreen(
    session: LockSession,
    pickerStyle: PickerStyle,
    pickerFont: FontFamily,
    passButtonsEnabled: Boolean,
    onOpenAllowedApps: () -> Unit,
    onOpenPhone: () -> Unit,
    modifier: Modifier = Modifier,
    choiceDeadlineMs: Long? = null,
    nfcGate: Boolean = false,
    refresh: RefreshUi? = null,
    onRefresh: () -> Unit = {},
) {
    // A pending early unlock ends the lock sooner than the scheduled end.
    val endMs = session.effectiveEndMs
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks faster while the "locking again" countdown shows, so its seconds don't skip.
    val tickMs = if (choiceDeadlineMs != null) 250L else 1_000L
    LaunchedEffect(endMs, tickMs) {
        while (now < endMs) {
            delay(tickMs)
            now = System.currentTimeMillis()
        }
    }
    val endTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(endMs))

    Column(modifier.fillMaxSize().padding(24.dp)) {
        PickerClock(pickerStyle, pickerFont)
        // The countdown and its buttons, centred in the space under the clock.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                formatDurationClock(endMs - now),
                style = MaterialTheme.typography.displayMedium,
            )
            Text(stringResource(R.string.locked_until, endTime))
            if (nfcGate) {
                Text(
                    stringResource(R.string.nfc_gate_prompt),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (choiceDeadlineMs != null) {
                val secondsLeft = ((choiceDeadlineMs - now + 999) / 1_000).coerceAtLeast(0)
                Text(
                    stringResource(R.string.locked_choice_countdown, secondsLeft.toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (passButtonsEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onOpenAllowedApps) { Text(stringResource(R.string.picker_title)) }
                    OutlinedButton(onClick = onOpenPhone) { Text(stringResource(R.string.notif_action_phone)) }
                }
            }
            if (refresh != null) {
                RefreshButton(refresh, onRefresh)
            }
        }
    }
}

/** "↻ Check location now", with "Checking…" while it runs and the last result under it. */
@Composable
fun RefreshButton(refresh: RefreshUi, onRefresh: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onRefresh, enabled = refresh != RefreshUi.Checking) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text(
                stringResource(if (refresh == RefreshUi.Checking) R.string.refresh_checking else R.string.refresh_button),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (refresh is RefreshUi.Done) {
            Text(refresh.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
