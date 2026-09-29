package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A short list whose rows can be reordered: long-press a row, drag it up or down, and let go.
 * A row swaps with its neighbour once it's dragged past half of the neighbour's height.
 * Meant for short lists inside a scrolling page (profiles, schedules, triggers); it doesn't
 * auto-scroll at the screen edges.
 *
 * @param key a stable, unique key per item (e.g. its database id).
 * @param onReorder called once, when a drag that changed the order ends, with the new order.
 * @param itemContent draws one row; `dragging` is true for the row being moved.
 */
@Composable
fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    // A local copy that changes while dragging; it follows [items] whenever no drag is running.
    val order = remember { items.toMutableStateList() }
    var draggedKey by remember { mutableStateOf<Any?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<Any, Int>() }
    val haptics = LocalHapticFeedback.current
    // The gesture handlers outlive recompositions, so they read these through state.
    val latestOnReorder by rememberUpdatedState(onReorder)
    val latestItems by rememberUpdatedState(items)
    val latestKey by rememberUpdatedState(key)
    val draggedBackground = MaterialTheme.colorScheme.surfaceContainerHighest

    LaunchedEffect(items) {
        if (draggedKey == null) {
            order.clear()
            order.addAll(items)
        }
    }

    fun moveDraggedBy(deltaY: Float) {
        val index = order.indexOfFirst { latestKey(it) == draggedKey }
        if (index < 0) return
        dragOffsetY += deltaY
        val next = order.getOrNull(index + 1)
        val previous = order.getOrNull(index - 1)
        val nextHeight = next?.let { heights[latestKey(it)] } ?: 0
        val previousHeight = previous?.let { heights[latestKey(it)] } ?: 0
        when {
            next != null && dragOffsetY > nextHeight / 2f -> {
                order.add(index + 1, order.removeAt(index))
                dragOffsetY -= nextHeight
            }
            previous != null && dragOffsetY < -previousHeight / 2f -> {
                order.add(index - 1, order.removeAt(index))
                dragOffsetY += previousHeight
            }
        }
    }

    fun endDrag() {
        val changed = order.map(latestKey) != latestItems.map(latestKey)
        draggedKey = null
        dragOffsetY = 0f
        if (changed) latestOnReorder(order.toList())
    }

    Column(modifier) {
        order.forEach { item ->
            val itemKey = key(item)
            key(itemKey) {
                val dragging = itemKey == draggedKey
                Column(
                    Modifier
                        .onSizeChanged { heights[itemKey] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffsetY else 0f
                            shadowElevation = if (dragging) 12f else 0f
                            shape = DRAG_SHAPE
                            clip = dragging
                        }
                        .then(if (dragging) Modifier.background(draggedBackground, DRAG_SHAPE) else Modifier)
                        .pointerInput(itemKey) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggedKey = itemKey
                                    dragOffsetY = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    moveDraggedBy(amount.y)
                                },
                                onDragEnd = ::endDrag,
                                onDragCancel = ::endDrag,
                            )
                        },
                ) {
                    itemContent(item, dragging)
                }
            }
        }
    }
}

private val DRAG_SHAPE = RoundedCornerShape(12.dp)
