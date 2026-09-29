package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfile
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfiles

/**
 * Radio list of device profiles. "Automatic" (a null id) follows the detected manufacturer.
 *
 * @param detected the profile picked from `Build.MANUFACTURER`.
 * @param selectedId the user's choice, or null for automatic.
 */
@Composable
fun DeviceProfilePicker(
    manufacturer: String,
    model: String,
    detected: OemProfile,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.device_detected, manufacturer, model),
            style = MaterialTheme.typography.bodyMedium,
        )
        ProfileRow(
            label = stringResource(R.string.device_automatic, detected.displayName),
            selected = selectedId == null,
            onClick = { onSelect(null) },
        )
        OemProfiles.ALL.forEach { profile ->
            ProfileRow(
                label = if (profile.verified) {
                    profile.displayName
                } else {
                    stringResource(R.string.device_unverified, profile.displayName)
                },
                selected = selectedId == profile.id,
                onClick = { onSelect(profile.id) },
            )
        }
    }
}

@Composable
private fun ProfileRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
