package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag

/**
 * Quick lock's settings on their own screen (length, allowed apps, focus, unlocking), so the Lock
 * tab stays short. Every change is saved right away. In Pro mode, ↻ copies the Lock defaults back in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickLockOptionsScreen(
    quickLock: LockSettings,
    presetsSec: List<Long>,
    nfcTags: List<NfcTag>,
    onChange: (LockSettings) -> Unit,
    onEditAllowedApps: () -> Unit,
    onResetToDefaults: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var confirmReset by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.quick_options_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                if (isPro()) {
                    IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.quick_reset_defaults))
                    }
                }
            },
            // The activity's Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.quick_options_hint), style = MaterialTheme.typography.bodyMedium)
            LockSettingsSections(
                settings = quickLock,
                presetsSec = presetsSec,
                nfcTags = nfcTags,
                lockId = QUICK_LOCK_ID,
                lengthTitle = stringResource(R.string.lock_length),
                onChange = onChange,
                onEditAllowedApps = onEditAllowedApps,
            )
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.quick_reset_confirm_title)) },
            text = { Text(stringResource(R.string.quick_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetToDefaults()
                }) { Text(stringResource(R.string.quick_reset_defaults)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
