package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.ui.components.RowLabel
import com.rikkaminis.app.ui.components.SectionTextField
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add Custom Model — adopts the SettingsScaffold/SettingsSection toolkit
 * (T78). Single Identity section grouping the Model ID + Display Name
 * fields, with a footer caption explaining what the fields are for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomModelScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    // [deep-audit-1039 B-03] Subscribe to the config flow instead of reading a
    // one-shot snapshot (`providerRepository.instance(instanceId)`): the
    // snapshot never re-read, so when the parent instance was removed while
    // this screen was open (minis-config RPC, Debug RPC, backup restore) the
    // page did not recompose, the `instance == null` guard below never fired,
    // and Save minted a ModelEntry pointing at an id the instances table no
    // longer has (addEntry only checks for duplicates). This is the same
    // subscribe-then-derive shape the other 12 screens in ui/settings use.
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }
    val context = LocalContext.current

    if (instance == null) {
        // [audit-0917] Navigate AFTER composition (see ModelGroupDetailScreen).
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    // [deep-audit-1033 I05] The write below is a coroutine now; without this
    // the Save button stays live for the round trip and a double tap can add
    // the same custom model twice (addEntry has no uniqueness check).
    var saving by remember { mutableStateOf(false) }
    // [meta-audit-1028 E5 / B06 F-07] `addEntry` never signals refusal in its
    // type: it returns the id of the entry that already owned the
    // (providerInstanceId, modelId) composite key and writes NOTHING. Ignoring
    // that (the old code called it and then `onBack()` unconditionally) made a
    // rejected add indistinguishable from a saved one — the screen popped and
    // the model the user just typed was nowhere, with the sheet still showing
    // the pre-existing entry as if that was what they created.
    var addError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    SettingsScaffold(
        title = stringResource(R.string.provider_detail_add_custom_model),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = stringResource(R.string.add_custom_model_id_footer, instance.providerType.displayName),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_custom_model_model_id))
                SectionTextField(
                    value = modelId,
                    // [meta-audit-1028 E5 / B06 F-07] Typing is the user
                    // acting on the refusal, so clear it here; the next Save
                    // re-evaluates against fresh state.
                    onValueChange = { modelId = it; addError = null },
                    placeholder = stringResource(R.string.add_custom_model_eg_id_placeholder),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                // Refusal is surfaced under the field that caused it, not as a
                // toast: the user must still be looking at what to change.
                // (Local capture: a `by`-delegated property cannot smart-cast.)
                addError?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.add_custom_model_display_name_optional))
                SectionTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        MinisButton(
            onClick = {
                if (saving) return@MinisButton
                saving = true
                val name = displayName.ifBlank { modelId }
                val model = LLMModel(
                    id = modelId.trim(),
                    displayName = name.trim(),
                    provider = instance.providerType.displayName,
                )
                val entry = ModelEntry(
                    providerInstanceId = instanceId,
                    baseModel = model,
                    isCustom = true,
                )
                scope.launch {
                    try {
                        // [deep-audit-1039 B-03] The subscription above makes the
                        // guard fire on the next composition after an external
                        // delete, but the click can still land in the window
                        // between that delete and the IO hop below. Re-resolve
                        // the parent instance at write time and drop the write
                        // when it is gone: addEntry checks duplicates only, so a
                        // write here is an orphan ModelEntry by construction.
                        //
                        // [deep-audit-1033 I05] addEntry goes to SQLite on the
                        // caller's thread; saving a custom model used to block
                        // the frame that navigates back.
                        //
                        // [meta-audit-1028 E5 / B06 F-07] Snapshot the ids
                        // BEFORE the write: addEntry's refusal is only
                        // observable as "the id it reported was already one of
                        // ours", so the comparison needs the pre-write set.
                        val idsBefore = providerRepository.config.value.modelEntries
                            .mapTo(mutableSetOf()) { it.id }
                        val parentStillThere = providerRepository.config.value.instances
                            .any { it.id == instanceId }
                        if (!parentStillThere) {
                            onBack()
                            return@launch
                        }
                        val persistedId = withContext(Dispatchers.IO) {
                            providerRepository.addEntry(entry)
                        }
                        if (addEntryRejected(persistedId, idsBefore)) {
                            // Refused: nothing was written. Keep the form
                            // (and the typed values) on screen and say why.
                            addError = context.getString(
                                R.string.add_custom_model_error_model_id_exists,
                                modelId.trim(),
                            )
                            return@launch
                        }
                        onBack()
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = modelId.isNotBlank() && !saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.common_save))
        }
        Spacer(Modifier.height(20.dp))
    }
}
