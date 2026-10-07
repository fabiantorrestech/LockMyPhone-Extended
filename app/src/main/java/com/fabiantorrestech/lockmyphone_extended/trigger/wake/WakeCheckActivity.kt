package com.fabiantorrestech.lockmyphone_extended.trigger.wake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.ui.theme.LockMyPhoneExtendedTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Morning lock's optional late check (M22.1): "Just waking up?" with a countdown to the lock.
 * Holding "I've been up a while" for [WakeTriggerManager.LATE_SKIP_HOLD_MS] skips today. The
 * countdown itself runs in [WakeTriggerManager], so closing this screen still locks.
 */
class WakeCheckActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LockApp.container(this)
        val triggerId = intent.getLongExtra(EXTRA_TRIGGER_ID, 0L)
        // The countdown starts now that the screen is up; nothing to ask if it's already over.
        val deadlineMs = container.wakeTriggerManager.onLateCheckShown(triggerId)
        if (deadlineMs == null) {
            finish()
            return
        }
        setContent {
            val themeMode by container.settingsStore.themeMode.collectAsState()
            LockMyPhoneExtendedTheme(themeMode) {
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var holding by remember { mutableStateOf(false) }
                var held by remember { mutableFloatStateOf(0f) }

                // The countdown: closes when the lock is due (the manager starts it).
                LaunchedEffect(Unit) {
                    while (nowMs < deadlineMs) {
                        delay(200)
                        nowMs = System.currentTimeMillis()
                    }
                    finish()
                }
                // Holding fills the bar; letting go early empties it.
                LaunchedEffect(holding) {
                    if (!holding) {
                        held = 0f
                        return@LaunchedEffect
                    }
                    val startMs = System.currentTimeMillis()
                    while (held < 1f) {
                        delay(50)
                        held = ((System.currentTimeMillis() - startMs).toFloat() / WakeTriggerManager.LATE_SKIP_HOLD_MS).coerceAtMost(1f)
                    }
                    lifecycleScope.launch {
                        container.wakeTriggerManager.skipLateCheck(triggerId)
                        finish()
                    }
                }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.wake_check_title), style = MaterialTheme.typography.headlineMedium)
                        val seconds = ((deadlineMs - nowMs + 999) / 1_000).coerceAtLeast(0)
                        Text(
                            stringResource(R.string.wake_check_countdown, seconds),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedCard(
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(onPress = {
                                        holding = true
                                        tryAwaitRelease()
                                        holding = false
                                    })
                                },
                        ) {
                            Box(Modifier.padding(20.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.wake_check_skip), style = MaterialTheme.typography.titleMedium)
                                    LinearProgressIndicator(progress = { held }, modifier = Modifier.fillMaxWidth().height(6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Back does nothing: the lock comes either way, and skipping needs the hold.
    @Deprecated("Back is intentionally ignored here")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = Unit

    companion object {
        const val EXTRA_TRIGGER_ID = "trigger_id"
    }
}
