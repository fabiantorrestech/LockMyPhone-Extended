package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppIconProvider
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A searchable checklist of launchable apps. Checked apps can be opened from the lock screen
 * during a lock; 👁 hides a checked app from that list (it stays allowed).
 *
 * @param hidden the checked apps that are hidden; always a subset of [selected].
 */
@Composable
fun AllowedAppsScreen(
    loadApps: () -> List<LaunchableApp>,
    iconProvider: AppIconProvider,
    selected: Set<String>,
    hidden: Set<String>,
    onSelectionChange: (selected: Set<String>, hidden: Set<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val apps by produceState<List<LaunchableApp>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { loadApps() }
    }
    var query by remember { mutableStateOf("") }
    // Apps selected when the screen opened are listed first. This is a snapshot, so checking or
    // unchecking an app doesn't make rows jump around while you scroll.
    val selectedAtOpen = remember { selected }
    val listState = rememberLazyListState()

    // Warm the icon cache for every app, so scrolling never waits on an icon decode.
    val iconSizePx = with(LocalDensity.current) { AppIconSize.roundToPx() }
    LaunchedEffect(apps) {
        val loadedApps = apps ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            loadedApps.forEach { iconProvider.loadIcon(it.packageName, iconSizePx) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.allowed_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            stringResource(R.string.allowed_phone_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            stringResource(R.string.allowed_hide_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.allowed_search_label)) },
            supportingText = { Text(stringResource(R.string.allowed_selected_count, selected.size)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val loaded = apps
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            // Apps selected when the screen opened come first, the rest below a divider.
            // partition keeps each group in its alphabetical order.
            val (selectedGroup, otherGroup) = remember(loaded, query) {
                filterApps(loaded, query).partition { it.packageName in selectedAtOpen }
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // Keeps the checkboxes clear of the scrollbar's touch strip.
                    contentPadding = PaddingValues(end = 24.dp),
                ) {
                    appItems(selectedGroup, selected, hidden, onSelectionChange, iconProvider)
                    if (selectedGroup.isNotEmpty() && otherGroup.isNotEmpty()) {
                        item(key = "other_apps_divider", contentType = "divider") {
                            Column(Modifier.padding(top = 8.dp)) {
                                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                                Text(
                                    stringResource(R.string.allowed_apps_other_header),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    appItems(otherGroup, selected, hidden, onSelectionChange, iconProvider)
                }
                FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/**
 * A checkbox row per app, with 👁 to hide a checked app from the lock-screen list. Extension on
 * LazyListScope so both groups share it.
 */
private fun LazyListScope.appItems(
    apps: List<LaunchableApp>,
    selected: Set<String>,
    hidden: Set<String>,
    onSelectionChange: (Set<String>, Set<String>) -> Unit,
    iconProvider: AppIconProvider,
) {
    items(apps, key = { it.packageName }, contentType = { "app" }) { app ->
        val packageName = app.packageName
        val checked = packageName in selected
        val isHidden = packageName in hidden
        val toggle = {
            // Unchecking also un-hides, so checking it again starts visible.
            if (checked) {
                onSelectionChange(selected - packageName, hidden - packageName)
            } else {
                onSelectionChange(selected + packageName, hidden)
            }
        }
        AppRow(app, iconProvider, onClick = toggle) {
            if (checked) {
                IconButton(onClick = {
                    onSelectionChange(selected, if (isHidden) hidden - packageName else hidden + packageName)
                }) {
                    Icon(
                        if (isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(if (isHidden) R.string.allowed_hidden else R.string.allowed_shown),
                    )
                }
            }
            Checkbox(checked = checked, onCheckedChange = { toggle() })
        }
    }
}

/** Case-insensitive match on the app name or package name; the input order is kept. */
private fun filterApps(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
    val needle = query.trim()
    if (needle.isEmpty()) return apps
    return apps.filter {
        it.label.contains(needle, ignoreCase = true) || it.packageName.contains(needle, ignoreCase = true)
    }
}
