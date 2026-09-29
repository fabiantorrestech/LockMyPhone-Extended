package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R

/**
 * First-run setup in two steps: confirm the device profile, then grant the permissions.
 * The permissions step reuses the home screen's checklist.
 */
@Composable
fun OnboardingScreen(
    deviceProfilePicker: @Composable () -> Unit,
    setupChecklist: @Composable () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.onboarding_step, step + 1, STEP_COUNT),
            style = MaterialTheme.typography.labelLarge,
        )
        when (step) {
            0 -> {
                Text(stringResource(R.string.onboarding_device_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.onboarding_device_body), style = MaterialTheme.typography.bodySmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { deviceProfilePicker() }
                }
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_next))
                }
            }
            else -> {
                Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.onboarding_permissions_body), style = MaterialTheme.typography.bodySmall)
                setupChecklist()
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_finish))
                }
                OutlinedButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_back))
                }
            }
        }
    }
}

private const val STEP_COUNT = 2
