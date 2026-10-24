package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppIconProvider
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.data.Situation
import com.fabiantorrestech.lockmyphone_extended.data.StarterProfiles

/** What the setup assistant hands back on Finish. */
data class SetupResult(
    val situations: Set<Situation>,
    /** Start and end minute of day for a nightly Sleep lock, or null for none. */
    val bedtime: Pair<Int, Int>?,
    val assistantPopup: Boolean,
)

private enum class AssistantStep { WELCOME, ESSENTIALS, SITUATIONS, APPS, BEDTIME, DONE }

/**
 * The setup assistant: welcome, the four permissions every lock needs, the situations you lock
 * your phone in (each becomes a ready-made profile), the apps that stay usable, an optional
 * nightly Sleep lock, done. Run again from Settings ([rerun]), it starts at the situations and can
 * be closed; profiles that already exist are kept.
 *
 * @param previewApps the apps the chosen situations would allow on this phone, Phone first.
 */
@Composable
fun SetupAssistantScreen(
    setup: SetupStatus,
    setupActions: SetupActions,
    previewApps: suspend (Set<Situation>) -> List<LaunchableApp>,
    iconProvider: AppIconProvider,
    initialAssistantPopup: Boolean,
    rerun: Boolean,
    onFinish: (SetupResult) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var chosen by rememberSaveable {
        mutableStateOf(if (rerun) emptySet() else setOf(Situation.SLEEP, Situation.FOCUS))
    }
    var stepName by rememberSaveable { mutableStateOf(if (rerun) AssistantStep.SITUATIONS.name else AssistantStep.WELCOME.name) }
    var bedtimeOn by rememberSaveable { mutableStateOf(true) }
    var bedStart by rememberSaveable { mutableIntStateOf(StarterProfiles.DEFAULT_BEDTIME_START) }
    var bedEnd by rememberSaveable { mutableIntStateOf(StarterProfiles.DEFAULT_BEDTIME_END) }
    var assistantPopup by rememberSaveable { mutableStateOf(initialAssistantPopup) }

    val step = AssistantStep.valueOf(stepName)
    val steps = buildList {
        if (!rerun) {
            add(AssistantStep.WELCOME)
            add(AssistantStep.ESSENTIALS)
        }
        add(AssistantStep.SITUATIONS)
        add(AssistantStep.APPS)
        if (Situation.SLEEP in chosen) add(AssistantStep.BEDTIME)
        add(AssistantStep.DONE)
    }
    val index = steps.indexOf(step).coerceAtLeast(0)
    val goBack: () -> Unit = {
        if (index > 0) stepName = steps[index - 1].name else onCancel?.invoke()
    }
    val goNext: () -> Unit = {
        if (step == AssistantStep.DONE) {
            onFinish(SetupResult(chosen, (bedStart to bedEnd).takeIf { bedtimeOn && Situation.SLEEP in chosen }, assistantPopup))
        } else {
            stepName = steps[index + 1].name
        }
    }
    BackHandler(enabled = index > 0 || onCancel != null, onBack = goBack)

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { (index + 1f) / steps.size },
                modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
            )
            if (onCancel != null) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
            } else {
                Spacer(Modifier.size(16.dp))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                AssistantStep.WELCOME -> WelcomeStep()
                AssistantStep.ESSENTIALS -> EssentialsStep(setup, setupActions, onSkip = goNext)
                AssistantStep.SITUATIONS -> SituationsStep(chosen) { situation ->
                    chosen = if (situation in chosen) chosen - situation else chosen + situation
                }
                AssistantStep.APPS -> AppsStep(
                    chosen = chosen,
                    previewApps = previewApps,
                    iconProvider = iconProvider,
                    assistantPopup = assistantPopup,
                    onAssistantPopupChange = { assistantPopup = it },
                    needsDnd = chosen.any { it.dnd } && !setup.dndAccessGranted,
                    onGrantDnd = setupActions.onGrantDnd,
                )
                AssistantStep.BEDTIME -> BedtimeStep(
                    on = bedtimeOn,
                    onChange = { bedtimeOn = it },
                    startMinute = bedStart,
                    endMinute = bedEnd,
                    onStartChange = { bedStart = it },
                    onEndChange = { bedEnd = it },
                )
                AssistantStep.DONE -> DoneStep(chosen, (bedStart to bedEnd).takeIf { bedtimeOn && Situation.SLEEP in chosen })
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index > 0 || onCancel != null) {
                    TextButton(onClick = goBack) { Text(stringResource(R.string.setup_back)) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = goNext,
                    enabled = step != AssistantStep.ESSENTIALS || (setup.adminActive && setup.accessibilityEnabled),
                ) {
                    Text(
                        stringResource(
                            when (step) {
                                AssistantStep.WELCOME -> R.string.assist_get_started
                                AssistantStep.DONE -> R.string.assist_finish
                                else -> R.string.setup_next
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String?) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeStep() {
    Spacer(Modifier.height(24.dp))
    Box(
        Modifier.size(88.dp).clip(CircleShape).padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
            }
        }
    }
    StepTitle(stringResource(R.string.assist_welcome_title), stringResource(R.string.assist_welcome_body))
    Text(stringResource(R.string.assist_welcome_body2), style = MaterialTheme.typography.bodyMedium)
}

/** The four permissions every lock needs. Next waits for the two it can't lock without. */
@Composable
private fun EssentialsStep(setup: SetupStatus, actions: SetupActions, onSkip: () -> Unit) {
    StepTitle(stringResource(R.string.assist_essentials_title), stringResource(R.string.assist_essentials_body))
    Card(Modifier.fillMaxWidth()) {
        PermissionStepRow(Icons.Filled.AdminPanelSettings, R.string.setup_admin, R.string.assist_admin_why, setup.adminActive, actions.onEnableAdmin)
        SectionDivider()
        PermissionStepRow(Icons.Filled.AccessibilityNew, R.string.setup_accessibility, R.string.assist_accessibility_why, setup.accessibilityEnabled, actions.onEnableAccessibility)
        SectionDivider()
        PermissionStepRow(Icons.Filled.Notifications, R.string.setup_notifications, R.string.assist_notifications_why, setup.notificationsGranted, actions.onRequestNotifications)
        SectionDivider()
        PermissionStepRow(Icons.Filled.BatteryChargingFull, R.string.perm_battery, R.string.assist_battery_why, setup.batteryUnrestricted, actions.onAllowBackground)
    }
    Text(stringResource(R.string.assist_restricted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!setup.adminActive || !setup.accessibilityEnabled) {
        TextButton(onClick = onSkip) { Text(stringResource(R.string.assist_skip_for_now)) }
    }
}

@Composable
private fun PermissionStepRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, why: Int, done: Boolean, onAllow: () -> Unit) {
    SettingsRow(
        icon = icon,
        title = stringResource(title),
        summary = stringResource(why),
        onClick = if (done) null else onAllow,
        trailing = {
            if (done) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.permission_allowed), tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onAllow) { Text(stringResource(R.string.permission_allow)) }
            }
        },
    )
}

@Composable
private fun SituationsStep(chosen: Set<Situation>, onToggle: (Situation) -> Unit) {
    StepTitle(stringResource(R.string.assist_situations_title), stringResource(R.string.assist_situations_body))
    Situation.entries.forEach { situation ->
        SituationCard(situation, selected = situation in chosen, onClick = { onToggle(situation) })
    }
    Text(stringResource(R.string.assist_situations_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SituationCard(situation: Situation, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(situation.icon, fontSize = 24.sp) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(situation.nameRes) + " · " + formatDurationLabel(situation.durationSec * 1_000),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(situation.descriptionRes), style = MaterialTheme.typography.bodyMedium)
            }
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }
    }
}

@Composable
private fun AppsStep(
    chosen: Set<Situation>,
    previewApps: suspend (Set<Situation>) -> List<LaunchableApp>,
    iconProvider: AppIconProvider,
    assistantPopup: Boolean,
    onAssistantPopupChange: (Boolean) -> Unit,
    needsDnd: Boolean,
    onGrantDnd: () -> Unit,
) {
    val apps by produceState<List<LaunchableApp>?>(null, chosen) { value = previewApps(chosen) }
    StepTitle(stringResource(R.string.assist_apps_title), stringResource(R.string.assist_apps_body))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            apps?.forEach { app -> AppRow(app, iconProvider, onClick = {}) }
        }
    }
    Text(stringResource(R.string.assist_apps_camera), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.assist_apps_always), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth()) {
        SwitchSettingsRow(
            icon = Icons.Filled.Assistant,
            title = stringResource(R.string.assistant_popup_title),
            summary = stringResource(R.string.assist_assistant_hint),
            checked = assistantPopup,
            onCheckedChange = onAssistantPopupChange,
        )
        if (needsDnd) {
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.DoNotDisturbOn,
                title = stringResource(R.string.setup_dnd),
                summary = stringResource(R.string.assist_dnd_why),
                onClick = onGrantDnd,
                trailing = { FilledTonalButton(onClick = onGrantDnd) { Text(stringResource(R.string.permission_allow)) } },
            )
        }
    }
}

@Composable
private fun BedtimeStep(
    on: Boolean,
    onChange: (Boolean) -> Unit,
    startMinute: Int,
    endMinute: Int,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    StepTitle(stringResource(R.string.assist_bedtime_title), stringResource(R.string.assist_bedtime_body))
    Card(Modifier.fillMaxWidth()) {
        SwitchSettingsRow(
            icon = Icons.Filled.Bedtime,
            title = stringResource(R.string.assist_bedtime_switch),
            summary = null,
            checked = on,
            onCheckedChange = onChange,
        )
        SectionDivider()
        SettingsRow(
            icon = Icons.Filled.Bedtime,
            title = stringResource(R.string.assist_bedtime_start),
            summary = formatMinuteOfDay(startMinute),
            onClick = { picking = true },
            enabled = on,
        )
        SectionDivider()
        SettingsRow(
            icon = Icons.Filled.WbSunny,
            title = stringResource(R.string.assist_bedtime_end),
            summary = formatMinuteOfDay(endMinute),
            onClick = { picking = false },
            enabled = on,
        )
    }
    Text(stringResource(R.string.assist_bedtime_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    picking?.let { start ->
        TimePickerDialog(
            initialMinuteOfDay = if (start) startMinute else endMinute,
            onConfirm = {
                if (start) onStartChange(it) else onEndChange(it)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun DoneStep(chosen: Set<Situation>, bedtime: Pair<Int, Int>?) {
    Spacer(Modifier.height(16.dp))
    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
    StepTitle(stringResource(R.string.assist_done_title), stringResource(R.string.assist_done_body))
    if (chosen.isNotEmpty() || bedtime != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.assist_done_creates), style = MaterialTheme.typography.titleSmall)
                Situation.entries.filter { it in chosen }.forEach { situation ->
                    Text("${situation.icon}  ${stringResource(situation.nameRes)} · ${formatDurationLabel(situation.durationSec * 1_000)}")
                }
                bedtime?.let { (start, end) ->
                    Text("🌙  " + stringResource(R.string.assist_done_bedtime, formatMinuteOfDay(start), formatMinuteOfDay(end)))
                }
            }
        }
    }
    Text(stringResource(R.string.assist_done_pro), style = MaterialTheme.typography.bodyMedium)
}
