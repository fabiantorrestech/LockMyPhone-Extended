package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppIconProvider
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp

/**
 * A dim ⋮ for the allowed-apps list: "App info…" picks one of the lock's allowed apps and opens
 * its App info page, e.g. to force stop an app that misbehaves.
 *
 * @param apps every allowed app, hidden ones included.
 */
@Composable
fun AppInfoMenu(
    apps: List<LaunchableApp>,
    iconProvider: AppIconProvider,
    showIcons: Boolean,
    onOpenAppInfo: (packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.picker_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.picker_app_info)) },
                onClick = {
                    menuOpen = false
                    choosing = true
                },
            )
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.picker_app_info_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.picker_app_info_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            ListItem(
                                leadingContent = if (showIcons) {
                                    { AppIcon(app.packageName, iconProvider) }
                                } else {
                                    null
                                },
                                headlineContent = { Text(app.label) },
                                modifier = Modifier.clickable {
                                    choosing = false
                                    onOpenAppInfo(app.packageName)
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
