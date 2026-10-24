package com.fabiantorrestech.lockmyphone_extended.pass

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppMatch
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp

/** Room the corner buttons take at the bottom of the list, so its last row stays reachable. */
val PickerCornerClearance = 96.dp

private val CornerMargin = 20.dp
private val CornerButtonSize = 60.dp
private const val FAINT_ALPHA = 0.18f

/** The round Phone button in a bottom corner of the allowed-apps list. */
@Composable
fun BoxScope.CornerPhoneButton(corner: Alignment, onDial: () -> Unit) {
    Surface(
        onClick = onDial,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.align(corner).padding(CornerMargin).size(CornerButtonSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.notif_action_phone), modifier = Modifier.size(28.dp))
        }
    }
}

/**
 * The hidden-app search of the allowed-apps list: a faint (or invisible) icon in a bottom corner.
 * Tapping it opens the keyboard with no text box: what you type shows as plain text across the
 * bottom, on its own black band above the keyboard. Enter shows the apps whose name the text is, or
 * nearly is ([AppMatch]), just above it. Back (also closing the keyboard) or a tap anywhere else
 * cancels and clears it: in this box here, and elsewhere on the screen with [cancelSearchOnTap].
 *
 * @param searching whether the search is open; held by the screen so taps outside this box can close it.
 * @param resultRow draws one match; tapping it opens the app.
 */
@Composable
fun BoxScope.CornerSearch(
    corner: Alignment,
    faintIcon: Boolean,
    hiddenApps: List<LaunchableApp>,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    resultRow: @Composable (LaunchableApp) -> Unit,
) {
    if (!searching) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(corner)
                .padding(CornerMargin)
                .size(CornerButtonSize)
                .clickable { onSearchingChange(true) },
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = stringResource(R.string.picker_search_open),
                modifier = Modifier.size(28.dp).alpha(if (faintIcon) FAINT_ALPHA else 0f),
            )
        }
    } else {
        SearchBand(hiddenApps, resultRow, onCancel = { onSearchingChange(false) })
    }
}

/** While the search is open, a tap on a part of the screen that doesn't use it closes the search. */
fun Modifier.cancelSearchOnTap(searching: Boolean, onCancel: () -> Unit): Modifier =
    if (searching) pointerInput(Unit) { detectTapGestures { onCancel() } } else this

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.SearchBand(
    hiddenApps: List<LaunchableApp>,
    resultRow: @Composable (LaunchableApp) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onCancel)
    // With the keyboard up, Back first closes it: that counts as cancelling, so one Back is enough.
    val imeVisible = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeSeen = true else if (imeSeen) onCancel()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // A tap anywhere outside the band cancels.
    Box(
        Modifier
            .matchParentSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
    )
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 12.dp),
    ) {
        when {
            results == null -> Unit
            results!!.isEmpty() -> Text(
                stringResource(R.string.picker_search_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            else -> results!!.forEach { resultRow(it) }
        }
        BasicTextField(
            value = query,
            onValueChange = {
                query = it
                results = null
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                results = hiddenApps.filter { AppMatch.matches(query, it.label) }.sortedBy { it.label.lowercase() }
            }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.Center) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.picker_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    field()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).focusRequester(focus),
        )
    }
}
