package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppIconProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The icon size used by every app list, so prefetching and drawing hit the same cache entries. */
val AppIconSize: Dp = 40.dp

/**
 * An app's launcher icon. A cached icon draws on the first frame. Otherwise it's loaded off the
 * main thread, and an empty box of the same size keeps the row layout stable meanwhile.
 */
@Composable
fun AppIcon(
    packageName: String,
    iconProvider: AppIconProvider,
    modifier: Modifier = Modifier,
    size: Dp = AppIconSize,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val cached = remember(packageName, sizePx) { iconProvider.cachedIcon(packageName, sizePx) }
    val icon by produceState(initialValue = cached, packageName, sizePx) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { iconProvider.loadIcon(packageName, sizePx) }
        }
    }
    val loaded: ImageBitmap? = icon
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(modifier.size(size))
    }
}
