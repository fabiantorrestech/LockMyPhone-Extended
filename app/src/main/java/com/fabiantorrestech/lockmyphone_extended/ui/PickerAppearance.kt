package com.fabiantorrestech.lockmyphone_extended.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.FontChoice
import com.fabiantorrestech.lockmyphone_extended.data.FontLibrary
import com.fabiantorrestech.lockmyphone_extended.data.PickerStyle
import com.fabiantorrestech.lockmyphone_extended.data.SystemFont
import com.fabiantorrestech.lockmyphone_extended.data.ThemeMode
import com.fabiantorrestech.lockmyphone_extended.ui.theme.LockMyPhoneExtendedTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The date line under the clock is this much of the clock's size. */
private const val DATE_SIZE_RATIO = 0.3f

/** AM / PM next to the time is this much of the clock's size. */
private const val AM_PM_SIZE_RATIO = 0.35f

/** The Compose font for [choice]. An added font whose file is gone falls back to the default font. */
fun fontFamilyFor(choice: FontChoice, fonts: FontLibrary): FontFamily = when (choice) {
    is FontChoice.System -> choice.font.familyName
        ?.let { FontFamily(android.graphics.Typeface.create(it, android.graphics.Typeface.NORMAL)) }
        ?: FontFamily.Default
    is FontChoice.UserFile -> fonts.typeface(choice.fileName)?.let { FontFamily(it) } ?: FontFamily.Default
}

@Composable
fun rememberFontFamily(choice: FontChoice, fonts: FontLibrary): FontFamily =
    remember(choice) { fontFamilyFor(choice, fonts) }

/**
 * The time (12 or 24 hours, as the phone is set) with the date under it, for the top-left corner
 * of the allowed-apps picker and the lock countdown. It updates on each new minute.
 */
@Composable
fun PickerClock(style: PickerStyle, fontFamily: FontFamily, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    val time = DateFormat.getTimeFormat(context).format(Date(now))
    val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now).toString()
    val dateSizeSp = style.clockSizeSp * DATE_SIZE_RATIO
    Column(modifier) {
        Text(
            withSmallAmPm(time, now, (style.clockSizeSp * AM_PM_SIZE_RATIO).sp),
            fontSize = style.clockSizeSp.sp,
            lineHeight = (style.clockSizeSp * 1.1f).sp,
            fontFamily = fontFamily,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            date,
            fontSize = dateSizeSp.sp,
            lineHeight = (dateSizeSp * 1.2f).sp,
            fontFamily = fontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [time] with its AM/PM marker (12-hour phones) drawn at [amPmSize], so a big clock still fits on
 * one line next to the time left. A 24-hour time has no marker and is returned as is.
 */
private fun withSmallAmPm(time: String, nowMs: Long, amPmSize: TextUnit): AnnotatedString {
    val marker = SimpleDateFormat("a", Locale.getDefault()).format(Date(nowMs))
    val start = time.indexOf(marker)
    if (marker.isEmpty() || start < 0) return AnnotatedString(time)
    return buildAnnotatedString {
        append(time)
        addStyle(SpanStyle(fontSize = amPmSize), start, start + marker.length)
    }
}

/** "0:47:12 left", ticking each second, for the top-right of the allowed-apps list. */
@Composable
fun TimeLeft(endMs: Long, style: PickerStyle, fontFamily: FontFamily, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endMs) {
        while (now < endMs) {
            delay(1_000 - System.currentTimeMillis() % 1_000)
            now = System.currentTimeMillis()
        }
    }
    val sizeSp = style.clockSizeSp * DATE_SIZE_RATIO
    Text(
        stringResource(R.string.picker_time_left, formatDurationClock((endMs - now).coerceAtLeast(0))),
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * 1.2f).sp,
        fontFamily = fontFamily,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** "Exit", in place of the time left once the lock has ended, sized like the date line. */
@Composable
fun ExitButton(style: PickerStyle, fontFamily: FontFamily, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val sizeSp = style.clockSizeSp * DATE_SIZE_RATIO
    TextButton(onClick = onExit, modifier = modifier) {
        Text(stringResource(R.string.picker_exit), fontSize = sizeSp.sp, fontFamily = fontFamily)
    }
}

/**
 * Hides the status bar while this is shown and [hide] is true; it comes back when it leaves the
 * screen. A swipe down from the top still shows it for a moment, so the shade stays reachable.
 */
@Composable
fun HideStatusBar(hide: Boolean) {
    val view = LocalView.current
    DisposableEffect(hide) {
        val window = view.context.findActivity()?.window
        if (window == null || !hide) return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** State and actions for the "Allowed-apps screen" settings. */
class PickerAppearance(
    val style: PickerStyle,
    /** File names of the fonts the user added. */
    val userFonts: List<String>,
    val fontLibrary: FontLibrary,
    val onStyleChange: (PickerStyle) -> Unit,
    /** Adds the picked font file and selects it; reports failure itself. */
    val onAddFont: (Uri) -> Unit,
    val onRemoveFont: (String) -> Unit,
)

/**
 * Settings → Appearance → Allowed-apps screen: a live preview, the clock and app-name sizes, and
 * (under Advanced) the font used for both.
 */
@Composable
fun PickerAppearanceSection(appearance: PickerAppearance) {
    val style = appearance.style
    // Sliders move locally and save when let go, so dragging doesn't write the settings each frame.
    var clockSize by remember(style.clockSizeSp) { mutableFloatStateOf(style.clockSizeSp.toFloat()) }
    var nameSize by remember(style.appNameSizeSp) { mutableFloatStateOf(style.appNameSizeSp.toFloat()) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var choosingFont by remember { mutableStateOf(false) }
    val preview = style.copy(clockSizeSp = clockSize.roundToInt(), appNameSizeSp = nameSize.roundToInt())
    val fontFamily = rememberFontFamily(style.font, appearance.fontLibrary)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.picker_look_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.picker_look_hint), style = MaterialTheme.typography.bodySmall)
        PickerPreview(preview, fontFamily)
        SwitchRow(stringResource(R.string.picker_hide_status_bar), style.hideStatusBar) {
            appearance.onStyleChange(style.copy(hideStatusBar = it))
        }
        SwitchRow(stringResource(R.string.picker_show_time_left), style.showTimeLeft) {
            appearance.onStyleChange(style.copy(showTimeLeft = it))
        }

        Text(stringResource(R.string.picker_clock_size, preview.clockSizeSp))
        Slider(
            value = clockSize,
            onValueChange = { clockSize = it },
            onValueChangeFinished = { appearance.onStyleChange(style.copy(clockSizeSp = clockSize.roundToInt())) },
            valueRange = PickerStyle.CLOCK_SIZE_RANGE.first.toFloat()..PickerStyle.CLOCK_SIZE_RANGE.last.toFloat(),
        )
        Text(stringResource(R.string.picker_app_name_size, preview.appNameSizeSp))
        Slider(
            value = nameSize,
            onValueChange = { nameSize = it },
            onValueChangeFinished = { appearance.onStyleChange(style.copy(appNameSizeSp = nameSize.roundToInt())) },
            valueRange = PickerStyle.APP_NAME_SIZE_RANGE.first.toFloat()..PickerStyle.APP_NAME_SIZE_RANGE.last.toFloat(),
        )

        TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.setup_advanced_options)) }
        if (advanced) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.picker_font),
                summary = fontLabel(style.font),
                onClick = { choosingFont = true },
            )
        }
    }

    if (choosingFont) {
        FontDialog(
            appearance = appearance,
            onSelect = { appearance.onStyleChange(style.copy(font = it)); choosingFont = false },
            onDismiss = { choosingFont = false },
        )
    }
}

/** The picker's look on a black card: clock, date and one sample app name. */
@Composable
private fun PickerPreview(style: PickerStyle, fontFamily: FontFamily) {
    // The same OLED theme as the real screens, whatever the app's theme is.
    LockMyPhoneExtendedTheme(ThemeMode.OLED) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PickerClock(style, fontFamily)
            Text(
                stringResource(R.string.notif_action_phone),
                fontSize = style.appNameSizeSp.sp,
                fontFamily = fontFamily,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Every built-in font plus the added ones, each shown in itself; added fonts have ✕, and Add font… is at the end. */
@Composable
private fun FontDialog(appearance: PickerAppearance, onSelect: (FontChoice) -> Unit, onDismiss: () -> Unit) {
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) appearance.onAddFont(uri)
    }
    val choices = SystemFont.entries.map { FontChoice.System(it) } +
        appearance.userFonts.map { FontChoice.UserFile(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_font)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(choice) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == appearance.style.font, onClick = { onSelect(choice) })
                        Text(
                            fontLabel(choice),
                            fontFamily = rememberFontFamily(choice, appearance.fontLibrary),
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (choice is FontChoice.UserFile) {
                            IconButton(onClick = { appearance.onRemoveFont(choice.fileName) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.picker_font_remove))
                            }
                        }
                    }
                }
                OutlinedButton(
                    // Many file managers don't tag .ttf/.otf files with a font type, so any file can be
                    // picked; one that isn't a font is refused when it's added.
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.picker_font_add)) }
                Text(stringResource(R.string.picker_font_add_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun fontLabel(choice: FontChoice): String = when (choice) {
    is FontChoice.UserFile -> FontLibrary.displayName(choice.fileName)
    is FontChoice.System -> stringResource(
        when (choice.font) {
            SystemFont.DEFAULT -> R.string.font_default
            SystemFont.LIGHT -> R.string.font_light
            SystemFont.THIN -> R.string.font_thin
            SystemFont.CONDENSED -> R.string.font_condensed
            SystemFont.MEDIUM -> R.string.font_medium
            SystemFont.SERIF -> R.string.font_serif
            SystemFont.MONOSPACE -> R.string.font_monospace
            SystemFont.SERIF_MONO -> R.string.font_serif_mono
            SystemFont.CASUAL -> R.string.font_casual
            SystemFont.CURSIVE -> R.string.font_cursive
            SystemFont.SMALL_CAPS -> R.string.font_small_caps
        }
    )
}
