package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.CustomBodyField
import com.openminis.app.data.model.CustomHeader
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository

/**
 * [T-provider-extra-headers] Advanced per-provider escape hatches (RikkaHub
 * parity): user-authored HTTP headers and chat body fields merged at request
 * build time. Deliberately kept inside an "Advanced" card with a warning —
 * same-name REPLACE semantics can break a provider that worked.
 */
@Composable
fun CustomKnobsSection(
    instance: ProviderInstance,
    repository: ProviderRepository,
) {
    var expanded by remember { mutableStateOf(false) }
    val headers = remember { mutableStateListOf<CustomHeader>().apply { addAll(instance.customHeaders) } }
    val bodies = remember { mutableStateListOf<CustomBodyField>().apply { addAll(instance.customBodyFields) } }
    var dirty by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.provider_custom_knobs_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (expanded) R.string.provider_custom_knobs_collapse else R.string.provider_custom_knobs_expand),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Text(
                stringResource(R.string.provider_custom_knobs_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))

            headers.forEachIndexed { i, h ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = h.name,
                        onValueChange = { headers[i] = h.copy(name = it); dirty = true },
                        label = { Text(stringResource(R.string.provider_custom_header_name)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = h.value,
                        onValueChange = { headers[i] = h.copy(value = it); dirty = true },
                        label = { Text(stringResource(R.string.provider_custom_header_value)) },
                        modifier = Modifier.weight(1.4f),
                    )
                    IconButton(onClick = { headers.removeAt(i); dirty = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "remove")
                    }
                }
            }
            Row(modifier = Modifier.clickable { headers.add(CustomHeader("", "")); dirty = true }.padding(4.dp)) {
                Icon(Icons.Default.Add, contentDescription = "add header")
                Text(stringResource(R.string.provider_custom_header_add))
            }

            Spacer(Modifier.height(12.dp))
            bodies.forEachIndexed { i, b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = b.key,
                        onValueChange = { bodies[i] = b.copy(key = it); dirty = true },
                        label = { Text(stringResource(R.string.provider_custom_body_key)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = b.valueJson,
                        onValueChange = { bodies[i] = b.copy(valueJson = it); dirty = true },
                        label = { Text(stringResource(R.string.provider_custom_body_value_json)) },
                        modifier = Modifier.weight(1.4f),
                    )
                    IconButton(onClick = { bodies.removeAt(i); dirty = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "remove")
                    }
                }
            }
            Row(modifier = Modifier.clickable { bodies.add(CustomBodyField("", "")); dirty = true }.padding(4.dp)) {
                Icon(Icons.Default.Add, contentDescription = "add body")
                Text(stringResource(R.string.provider_custom_body_add))
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val updated = instance.copy(
                        customHeaders = headers.filter { it.name.isNotBlank() },
                        customBodyFields = bodies.filter { it.key.isNotBlank() && it.valueJson.isNotBlank() },
                    )
                    repository.updateInstance(updated)
                    dirty = false
                },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.provider_custom_knobs_save))
            }
        }
    }
}
