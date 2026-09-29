package com.fabiantorrestech.lockmyphone_extended.ui

import android.icu.text.BreakIterator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fabiantorrestech.lockmyphone_extended.R

/** Stored icons that name a built-in icon (not an emoji) start with this, e.g. "mi:Bedtime". */
private const val MATERIAL_ICON_PREFIX = "mi:"

/** The icon shown for things that start a Quick lock and have no icon of their own. */
const val QUICK_LOCK_ICON = "⚡"

/**
 * The icon for a schedule or alarm trigger row: its own, else that of the lock it starts
 * ([profile] null = Quick lock).
 */
fun automationIcon(ownIcon: String?, profile: com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile?): String? =
    ownIcon ?: if (profile == null) QUICK_LOCK_ICON else profile.icon

/**
 * A profile's icon in a circle: a built-in Material icon, an emoji, or the first letter of its
 * name when it has none.
 *
 * @param icon "mi:<Name>" for a built-in icon, any other text for an emoji, or null.
 */
@Composable
fun ProfileIcon(icon: String?, name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val vector = materialIconFor(icon)
        if (vector != null) {
            Icon(
                vector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.6f),
            )
        } else {
            Text(
                text = icon?.takeUnless { it.startsWith(MATERIAL_ICON_PREFIX) }
                    ?: name.trim().take(1).uppercase().ifEmpty { "?" },
                fontSize = (size.value * 0.5f).sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * "Icon" row in an editor: the current icon and a button that opens [ProfileIconPickerDialog].
 *
 * @param fallbackIcon shown while [icon] is null, e.g. the icon of the profile a schedule starts.
 */
@Composable
fun ProfileIconField(
    icon: String?,
    name: String,
    onIconChange: (String?) -> Unit,
    fallbackIcon: String? = null,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileIcon(icon ?: fallbackIcon, name, size = 48.dp, modifier = Modifier.clickable { picking = true })
        Text(stringResource(R.string.profile_icon), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.profile_icon_change)) }
    }
    if (picking) {
        ProfileIconPickerDialog(
            onPick = {
                picking = false
                onIconChange(it)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * Two tabs: built-in icons (royalty-free Material icons) or emoji (a grid plus a field to type
 * any emoji). "No icon" goes back to the first letter.
 *
 * @param onPick the stored icon value, or null for no icon.
 */
@Composable
private fun ProfileIconPickerDialog(onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var showEmoji by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_icon_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !showEmoji,
                        onClick = { showEmoji = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.profile_icon_tab_icons)) }
                    SegmentedButton(
                        selected = showEmoji,
                        onClick = { showEmoji = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.profile_icon_tab_emoji)) }
                }
                if (showEmoji) {
                    PickerGrid(SUGGESTED_EMOJI) { emoji ->
                        PickerCell(onClick = { onPick(emoji) }) { Text(emoji, fontSize = 26.sp) }
                    }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = firstGrapheme(it) },
                        label = { Text(stringResource(R.string.profile_icon_custom)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PickerGrid(MATERIAL_ICONS.keys.toList()) { key ->
                        PickerCell(onClick = { onPick(MATERIAL_ICON_PREFIX + key) }) {
                            Icon(MATERIAL_ICONS.getValue(key), contentDescription = key, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showEmoji) {
                TextButton(onClick = { onPick(custom) }, enabled = custom.isNotBlank()) {
                    Text(stringResource(R.string.profile_icon_use_custom))
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.profile_icon_none)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun <T> PickerGrid(items: List<T>, cell: @Composable (T) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 48.dp),
        modifier = Modifier.heightIn(max = 280.dp),
    ) {
        items(items) { cell(it) }
    }
}

@Composable
private fun PickerCell(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun materialIconFor(icon: String?): ImageVector? =
    icon?.takeIf { it.startsWith(MATERIAL_ICON_PREFIX) }
        ?.let { MATERIAL_ICONS[it.removePrefix(MATERIAL_ICON_PREFIX)] }

/**
 * The first user-visible character, so a typed icon is always a single emoji. A multi-part emoji
 * such as 👨‍👩‍👧 or 🏋️ counts as one.
 */
private fun firstGrapheme(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    val end = iterator.next()
    return if (end == BreakIterator.DONE) trimmed else trimmed.substring(0, end)
}

/**
 * The built-in icons on offer. The key is what gets stored, so never rename a key: a profile
 * saved with it would lose its icon (it falls back to the first letter).
 */
private val MATERIAL_ICONS: Map<String, ImageVector> = linkedMapOf(
    "Bedtime" to Icons.Filled.Bedtime,
    "NightsStay" to Icons.Filled.NightsStay,
    "Hotel" to Icons.Filled.Hotel,
    "WbSunny" to Icons.Filled.WbSunny,
    "Alarm" to Icons.Filled.Alarm,
    "Timer" to Icons.Filled.Timer,
    "Work" to Icons.Filled.Work,
    "Business" to Icons.Filled.Business,
    "Computer" to Icons.Filled.Computer,
    "School" to Icons.Filled.School,
    "MenuBook" to Icons.AutoMirrored.Filled.MenuBook,
    "Psychology" to Icons.Filled.Psychology,
    "FitnessCenter" to Icons.Filled.FitnessCenter,
    "DirectionsRun" to Icons.AutoMirrored.Filled.DirectionsRun,
    "DirectionsBike" to Icons.AutoMirrored.Filled.DirectionsBike,
    "SelfImprovement" to Icons.Filled.SelfImprovement,
    "Spa" to Icons.Filled.Spa,
    "SportsSoccer" to Icons.Filled.SportsSoccer,
    "Restaurant" to Icons.Filled.Restaurant,
    "LocalCafe" to Icons.Filled.LocalCafe,
    "FamilyRestroom" to Icons.Filled.FamilyRestroom,
    "Favorite" to Icons.Filled.Favorite,
    "Pets" to Icons.Filled.Pets,
    "SportsEsports" to Icons.Filled.SportsEsports,
    "Tv" to Icons.Filled.Tv,
    "Movie" to Icons.Filled.Movie,
    "MusicNote" to Icons.Filled.MusicNote,
    "Brush" to Icons.Filled.Brush,
    "ShoppingCart" to Icons.Filled.ShoppingCart,
    "DirectionsCar" to Icons.Filled.DirectionsCar,
    "Flight" to Icons.Filled.Flight,
    "Home" to Icons.Filled.Home,
    "Park" to Icons.Filled.Park,
    "Star" to Icons.Filled.Star,
    "Lock" to Icons.Filled.Lock,
    "DoNotDisturbOn" to Icons.Filled.DoNotDisturbOn,
)

private val SUGGESTED_EMOJI = listOf(
    "🌙", "😴", "🛏️", "☀️", "🌅", "⏰",
    "💼", "🏢", "💻", "📚", "✏️", "🎓",
    "🧠", "🏋️", "🏃", "🧘", "🚴", "⚽",
    "🍽️", "☕", "🍳", "👨‍👩‍👧", "❤️", "🙏",
    "🎮", "📺", "🎬", "🎵", "🎨", "📖",
    "🚗", "✈️", "🏠", "🌳", "🎯", "🔒",
    "📵", "🔕",
)
