package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppIconProvider
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pick one app, e.g. the alarm app for an alarm trigger.
 *
 * @param suggested packages listed first (e.g. known alarm apps), if installed.
 */
@Composable
fun AppPickerScreen(
    title: String,
    loadApps: () -> List<LaunchableApp>,
    iconProvider: AppIconProvider,
    suggested: List<String>,
    onPick: (LaunchableApp) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val apps by produceState<List<LaunchableApp>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { loadApps() }
    }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.allowed_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val loaded = apps
        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val ordered = remember(loaded, query, suggested) {
            val needle = query.trim()
            val matching = if (needle.isEmpty()) {
                loaded
            } else {
                loaded.filter { it.label.contains(needle, true) || it.packageName.contains(needle, true) }
            }
            // Suggested apps first, the rest in their usual alphabetical order.
            matching.sortedBy { app -> suggested.indexOf(app.packageName).let { if (it < 0) Int.MAX_VALUE else it } }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState) {
                items(ordered, key = { it.packageName }, contentType = { "app" }) { app ->
                    AppRow(app, iconProvider, onClick = { onPick(app) })
                }
            }
            FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}
