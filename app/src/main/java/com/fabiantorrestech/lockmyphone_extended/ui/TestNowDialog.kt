package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.TestLine
import com.fabiantorrestech.lockmyphone_extended.trigger.TestMark
import com.fabiantorrestech.lockmyphone_extended.trigger.TestReport

/**
 * Test now's result: one line per check (✓ / ✗ / ? / •), then the verdict. A real 30 s lock with
 * the automation's lock can be started from here.
 *
 * @param report null while it's still checking.
 * @param extraLine a live result added after the report, e.g. the NFC tag just held to the phone.
 * @param canStartLock false without Device admin.
 */
@Composable
fun TestNowDialog(
    name: String,
    report: TestReport?,
    extraLine: TestLine?,
    canStartLock: Boolean,
    onStartTestLock: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_title, name.ifBlank { stringResource(R.string.test_now) })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (report == null) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.test_checking))
                } else {
                    (report.lines + listOfNotNull(extraLine)).forEach { line -> TestLineRow(line) }
                    Text(report.verdict, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    stringResource(R.string.test_unsaved_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onStartTestLock, enabled = canStartLock) { Text(stringResource(R.string.test_start_lock)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun TestLineRow(line: TestLine) {
    val (symbol, color) = when (line.mark) {
        TestMark.PASS -> "✓" to MaterialTheme.colorScheme.primary
        TestMark.FAIL -> "✗" to MaterialTheme.colorScheme.error
        TestMark.UNKNOWN -> "?" to MaterialTheme.colorScheme.onSurfaceVariant
        TestMark.INFO -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(symbol, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(line.text, style = MaterialTheme.typography.bodyMedium)
    }
}
