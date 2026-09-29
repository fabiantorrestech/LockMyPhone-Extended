package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A fast-scroll thumb for a [LazyColumn][androidx.compose.foundation.lazy.LazyColumn]. Overlay it
 * on the list's right edge. Tapping or dragging anywhere along that edge jumps the list to the
 * matching position. The thumb appears while the list scrolls or is touched, and fades out after
 * [HIDE_DELAY_MS].
 */
@Composable
fun FastScrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
    val totalItems by remember { derivedStateOf { listState.layoutInfo.totalItemsCount } }
    val visibleItems by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.size } }
    // Nothing to fast-scroll when everything fits on screen.
    if (totalItems == 0 || visibleItems >= totalItems) return

    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    // Bumped on each tap. scrollToItem() jumps instantly without isScrollInProgress, so a tap
    // needs its own way to show the thumb.
    var taps by remember { mutableIntStateOf(0) }
    val active = listState.isScrollInProgress || dragging
    LaunchedEffect(active, taps) {
        if (active) {
            shown = true
            return@LaunchedEffect
        }
        if (taps > 0) shown = true
        delay(HIDE_DELAY_MS)
        shown = false
    }

    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scrollbarAlpha")
    val thumbWidth by animateDpAsState(if (dragging) 14.dp else 8.dp, label = "scrollbarWidth")
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Maps a y position on the track to a list position. Assumes the track is the list's height.
    fun jumpTo(y: Float, trackHeight: Int) {
        val fraction = (y / trackHeight).coerceIn(0f, 1f)
        val index = (fraction * (totalItems - visibleItems)).roundToInt()
        scope.launch { listState.scrollToItem(index.coerceIn(0, totalItems - 1)) }
    }

    // The touch strip is narrower than the gap the lists leave on the right, so it never
    // overlaps a row's checkbox and can take touches at all times.
    val tapModifier = Modifier.pointerInput(totalItems, visibleItems) {
        detectTapGestures { offset ->
            taps++
            jumpTo(offset.y, size.height)
        }
    }
    val dragModifier = Modifier.pointerInput(totalItems, visibleItems) {
        detectVerticalDragGestures(
            onDragStart = { offset ->
                dragging = true
                jumpTo(offset.y, size.height)
            },
            onDragEnd = { dragging = false },
            onDragCancel = { dragging = false },
        ) { change, _ ->
            change.consume()
            jumpTo(change.position.y, size.height)
        }
    }

    Box(
        modifier
            .fillMaxHeight()
            .width(TOUCH_WIDTH)
            .then(tapModifier)
            .then(dragModifier)
            .drawBehind {
                // Reading the list state here redraws on scroll without recomposing.
                val scrollable = (totalItems - visibleItems).coerceAtLeast(1)
                val progress = (listState.firstVisibleItemIndex.toFloat() / scrollable).coerceIn(0f, 1f)
                val thumbHeight = (size.height * visibleItems / totalItems).coerceAtLeast(MIN_THUMB.toPx())
                val top = (size.height - thumbHeight) * progress
                val widthPx = thumbWidth.toPx()
                drawRoundRect(
                    color = thumbColor.copy(alpha = alpha * 0.8f),
                    topLeft = Offset(size.width - widthPx - EDGE_GAP.toPx(), top),
                    size = Size(widthPx, thumbHeight),
                    cornerRadius = CornerRadius(widthPx / 2),
                )
            },
    )
}

private const val HIDE_DELAY_MS = 1_500L
private val TOUCH_WIDTH = 40.dp
private val MIN_THUMB = 48.dp
private val EDGE_GAP = 4.dp
