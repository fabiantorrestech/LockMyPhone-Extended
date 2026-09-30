package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
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
 */
@Composable
fun LockedScreen(
    session: LockSession,
    passButtonsEnabled: Boolean,
    onOpenAllowedApps: () -> Unit,
    onOpenPhone: () -> Unit,
    modifier: Modifier = Modifier,
    choiceDeadlineMs: Long? = null,
    nfcGate: Boolean = false,
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

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
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
    }
}
