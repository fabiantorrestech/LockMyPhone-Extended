package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockStyle

/**
 * A profile's lock style: Locked (only allowed apps) or Limited (any app in short sessions with
 * growing cooldowns). Simple: the style, the session length and the first cooldown. Pro: how much
 * longer each cooldown gets, the longest one, and the free peeks.
 */
@Composable
fun LimitedLockSection(draft: LockProfile, onDraftChange: (LockProfile) -> Unit) {
    SettingsSection(stringResource(R.string.lock_style_title)) {
        Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LockStyle.entries.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = draft.lockStyle == style,
                        onClick = { onDraftChange(draft.copy(lockStyle = style)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = LockStyle.entries.size),
                    ) {
                        Text(stringResource(if (style == LockStyle.LOCKED) R.string.lock_style_locked else R.string.lock_style_limited))
                    }
                }
            }
            Text(
                stringResource(if (draft.lockStyle == LockStyle.LOCKED) R.string.lock_style_locked_hint else R.string.lock_style_limited_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (draft.lockStyle == LockStyle.LIMITED && draft.limitPeekCount > 0) {
                Text(
                    stringResource(R.string.lock_style_limited_peeks, draft.limitPeekSeconds, draft.limitPeekCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (draft.lockStyle == LockStyle.LIMITED) {
                val minutes = @Composable { value: Int -> stringResource(R.string.limit_minutes, value) }
                ChoiceChips(stringResource(R.string.limit_session_length), SESSION_MINUTES, draft.limitSessionMinutes, minutes) {
                    onDraftChange(draft.copy(limitSessionMinutes = it))
                }
                ChoiceChips(stringResource(R.string.limit_first_cooldown), COOLDOWN_MINUTES, draft.limitCooldownMinutes, minutes) {
                    onDraftChange(draft.copy(limitCooldownMinutes = it))
                }
                if (isPro()) {
                    ChoiceChips(stringResource(R.string.limit_cooldown_step), STEP_MINUTES, draft.limitCooldownStepMinutes, minutes) {
                        onDraftChange(draft.copy(limitCooldownStepMinutes = it))
                    }
                    ChoiceChips(stringResource(R.string.limit_max_cooldown), MAX_COOLDOWN_MINUTES, draft.limitMaxCooldownMinutes, minutes) {
                        onDraftChange(draft.copy(limitMaxCooldownMinutes = it))
                    }
                    ChoiceChips(stringResource(R.string.limit_peeks), PEEK_COUNTS, draft.limitPeekCount, { it.toString() }) {
                        onDraftChange(draft.copy(limitPeekCount = it))
                    }
                    ChoiceChips(
                        stringResource(R.string.limit_peek_length), PEEK_SECONDS, draft.limitPeekSeconds,
                        { stringResource(R.string.limit_seconds, it) },
                    ) { onDraftChange(draft.copy(limitPeekSeconds = it)) }
                }
            }
        }
    }
}

/** A label and one-tap choices; a saved value that isn't among them still shows as selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(title: String, options: List<Int>, selected: Int, label: @Composable (Int) -> String, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (options + selected).distinct().sorted().forEach { value ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) })
            }
        }
    }
}

private val SESSION_MINUTES = listOf(3, 5, 10, 15)
private val COOLDOWN_MINUTES = listOf(1, 2, 3, 5)
private val STEP_MINUTES = listOf(0, 1, 2, 5)
private val MAX_COOLDOWN_MINUTES = listOf(5, 10, 15, 30)
private val PEEK_COUNTS = listOf(0, 3, 5)
private val PEEK_SECONDS = listOf(15, 30, 60)
