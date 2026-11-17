package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.rikkaminis.app.ui.util.bringIntoViewOnFocus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.rikkaminis.app.R
import com.rikkaminis.app.config.collections.customUserAgentWritable
import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.SectionTextField

private const val TAG = "ProviderConnection"

/**
 * [T-provider-connection-screen] Full configuration page for a single AI
 * provider instance — label, credentials (API key), custom base URL,
 * API format, image endpoint, Azure mode. Opened from the "API & Connection"
 * row on ProviderDetailScreen; the detail screen itself stays focused on the
 * everyday stuff (enable toggle + model picker).
 */
@Composable
fun ProviderConnectionScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId } ?: run {
        // [F-2 sweep1013] composition-phase onBack() — wrap like
        // ModelEntryDetailScreen.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // [deep-audit-1039 B-04] Resolve the LATEST persisted instance at write
    // time. `instance` is the composition-time snapshot, so every write below
    // used to push it back wholesale: a focus-loss save that ran after a toggle
    // (or after an external minis-config / Debug RPC / backup-restore write)
    // silently reverted the newer fields to the values this frame composed
    // with. Same shape as ModelGroupDetailScreen's `currentGroup()`
    // ([audit-0917]); the `?: instance` arm only applies inside the window
    // between an external delete and the guard above composing again.
    fun currentInstance(): ProviderInstance =
        config.instances.find { it.id == instanceId } ?: instance

    // Label editing lives on the detail screen (its title). Here we show the
    // current label so the page has context; connection params are the focus.

    var storedKey by remember { mutableStateOf<String?>(null) }
    // [deep-audit-1041 B-01] Also key on `config.revision`. `loadApiKey` has no
    // revision counter of its own, but every path that writes a key also bumps
    // the config revision (instancesUpdate RPC / provider import / backup
    // restore), so it is the available "the stored credential may have moved"
    // signal — the API key lives in EncryptedSharedPreferences, NOT in a
    // `ProviderInstance` field, so no config field carries it. Without this an
    // external write left the previous key on screen, and the user's next
    // Edit → Save seeded the box from that stale snapshot and wrote it back
    // over the newer credential. Same form as ProviderDetailScreen's masked-key
    // re-read ([deep-audit-1040 B-07]).
    //
    // The re-read only refreshes `storedKey`; an in-progress draft
    // (`editKeyValue`) is deliberately NOT clobbered — the user's unsaved text
    // wins until they cancel or save (see the audit's 不得引入 note).
    LaunchedEffect(instanceId, config.revision) {
        storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
    }
    var isEditingKey by remember { mutableStateOf(false) }
    var editKeyValue by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    // [deep-audit-1033 I05] The key save below is a coroutine now.
    val scope = rememberCoroutineScope()

    // [da1023-C] Bind the connection states to instance.id: the old keyless
    // rememberSaveable leaked this instance's URL/UA state into the next
    // instance's screen when navigating between detail pages (same
    // destination, different argument → state survived the swap).
    // [da1026-C] Key on the FIELD VALUE as well as the id (same shape as
    // ProviderDetailScreen's label/isEnabled binding, [da1023-C]): with only
    // `instance.id` as the key, an external write to customBaseURL /
    // customUserAgent while this page stayed open (minis-config RPC, backup
    // restore, provider RPC) left the stale text on screen, and the
    // focus-loss saveBaseURLSettings() then wrote that stale value back over
    // the external change — the silent-revert shape da1023-C fixed for label.
    var customBaseURL by rememberSaveable(instance.id, instance.customBaseURL) { mutableStateOf(instance.customBaseURL ?: "") }
    var customUserAgent by rememberSaveable(instance.id, instance.customUserAgent) { mutableStateOf(instance.customUserAgent ?: "") }

    fun saveBaseURLSettings() {
        // [da1023-C] appendV1Suffix is NOT re-derived here: the instance's
        // stored value (set at creation from the provider type, and
        // independently writable via provider.instances.update /
        // providers.<id>.appendV1Suffix) used to be clobbered on every focus
        // loss with `providerType != gemini` — an OpenAI-compatible endpoint
        // configured with appendV1Suffix=false silently flipped back to true.
        // effectiveBaseURL guards double-append, so not touching the flag is
        // safe either way.
        //
        // [deep-audit-1039 B-04] Two changes on top of that: (a) the write is
        // now skip-if-unchanged, because Compose delivers `isFocused == false`
        // on the first frame after mounting, so merely opening this page used
        // to push two unconditional updateInstance writes (and two "Saved …"
        // log lines) with values nobody edited; (b) the cross-field write of
        // `customUserAgent` is GONE — the UA field has its own saver
        // (saveUserAgentSetting) and the base-URL block is hidden for
        // openRouter, so every UA edit path is still covered, while a
        // base-URL blur can no longer rewrite an unrelated field.
        val live = currentInstance()
        val pending = pendingConnectionFieldWrite(customBaseURL, live.customBaseURL) ?: return
        providerRepository.updateInstance(live.copy(customBaseURL = pending))
        AppLogger.info(
            TAG,
            "Saved base URL for ${live.id}: url='$pending', appendV1=${live.appendV1Suffix}",
        )
    }

    fun saveUserAgentSetting() {
        // [da1030-C ISSUE-04] Dedicated saver for the User-Agent field. The UA
        // field also renders for openRouter instances, where customBaseURL has
        // no editor and must not be rewritten as a side effect of an unrelated
        // edit.
        // [deep-audit-1039 B-04] Same skip-if-unchanged guard as the base-URL
        // saver: the UA field is mounted on every instance whose credential
        // type is writable, so the first-frame focus callback used to write —
        // and log — even when the user never touched the box.
        val live = currentInstance()
        val pending = pendingConnectionFieldWrite(customUserAgent, live.customUserAgent) ?: return
        providerRepository.updateInstance(live.copy(customUserAgent = pending))
        AppLogger.info(TAG, "Saved user agent for ${live.id}: ua='$pending'")
    }

    SettingsScaffold(
        title = instance.label,
        onBack = onBack,
    ) {
        // ─── Credential / API Key ───────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.provider_list_api_key),
            footer = stringResource(R.string.add_provider_multi_key_hint),
        ) {
            SettingsCardBlock {
                ApiKeyCredentialBlock(
                    storedKey = storedKey,
                    keyVisible = keyVisible,
                    onToggleVisibility = { keyVisible = !keyVisible },
                    isEditing = isEditingKey,
                    editValue = editKeyValue,
                    onEditValueChange = { editKeyValue = it },
                    onBeginEdit = {
                        isEditingKey = true
                        editKeyValue = storedKey ?: ""
                    },
                    onCancelEdit = {
                        isEditingKey = false
                        editKeyValue = ""
                        keyVisible = false
                    },
                    onSave = {
                        // [deep-audit-1033 I05] saveApiKey commits to
                        // EncryptedSharedPreferences — blocking keystore IO on
                        // the click's thread (the same defect the audit found
                        // on the three add-provider screens; this is the fourth
                        // save path for the same call). The key lands before
                        // the editor closes, same order as before.
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                providerRepository.saveApiKey(instanceId, editKeyValue)
                            }
                            storedKey = editKeyValue
                            AppLogger.info(TAG, "Saved API key for ${instance.id}")
                            isEditingKey = false
                        }
                    },
                )
            }
        }

        // ─── Custom Base URL ────────────────────────────────────────
        if (instance.providerType != ProviderType.openRouter) {
            // Provider-aware placeholder so the field suggests the real
            // default endpoint for the selected provider instead of the
            // generic example (mirrors AddProviderScreen.defaultUrl).
            val baseUrlPlaceholder = when (instance.providerType) {
                ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
                ProviderType.anthropic -> "https://api.anthropic.com"
                ProviderType.openAI -> "https://api.openai.com"
                else -> stringResource(R.string.provider_detail_https_api_example_placeholder)
            }
            SettingsSection(header = stringResource(R.string.provider_detail_custom_api_base)) {
                SettingsCardBlock {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SectionTextField(
                            value = customBaseURL,
                            onValueChange = { customBaseURL = it },
                            singleLine = true,
                            placeholder = baseUrlPlaceholder,
                            fieldModifier = Modifier
                                .bringIntoViewOnFocus()
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) saveBaseURLSettings()
                                },
                        )
                        // [T-audit-p2-4] Persistent warning when the endpoint uses
                        // unencrypted HTTP — API keys, messages and tool results
                        // would travel in cleartext over the network.
                        if (customBaseURL.trim().startsWith("http://", ignoreCase = true)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.provider_detail_http_endpoint_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        // ─── Custom User-Agent ──────────────────────────────────────
        // [da1030-C ISSUE-04] This field used to live INSIDE the
        // `!= openRouter` block above, so an openRouter instance could neither
        // see nor edit it — while ProviderFactory already hands
        // instance.customUserAgent to OpenAIProvider on that arm (openRouter
        // pins only its BASE URL), and the write contract
        // (ProvidersCollection.customUserAgentWritable) gates on credentialType
        // alone. The credentialType gate stays ([da1023-C]): the writer refuses
        // OAuth instances.
        if (customUserAgentWritable(instance.credentialType)) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_custom_user_agent),
            ) {
                SettingsCardBlock {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        SectionTextField(
                            value = customUserAgent,
                            onValueChange = { customUserAgent = it },
                            singleLine = true,
                            placeholder = stringResource(R.string.provider_detail_custom_user_agent_placeholder),
                            fieldModifier = Modifier
                                .bringIntoViewOnFocus()
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) saveUserAgentSetting()
                                },
                        )
                    }
                }
            }
        }

        // ─── API Format (OpenAI only) ───────────────────────────────
        if (instance.providerType == ProviderType.openAI) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_api_format),
                footer = if (instance.useResponsesAPI) {
                    stringResource(R.string.provider_detail_api_format_responses_footer)
                } else {
                    stringResource(R.string.provider_detail_api_format_chat_footer)
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !instance.useResponsesAPI,
                            onClick = {
                                if (instance.useResponsesAPI) {
                                    providerRepository.updateInstance(currentInstance().copy(useResponsesAPI = false))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                        SegmentedButton(
                            selected = instance.useResponsesAPI,
                            onClick = {
                                if (!instance.useResponsesAPI) {
                                    providerRepository.updateInstance(currentInstance().copy(useResponsesAPI = true))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_responses_api)) }
                    }
                }
            }
        }

        // ─── Azure OpenAI ───────────────────────────────────────────
        if (instance.supportsAzureMode) {
            SettingsSection(
                header = stringResource(R.string.provider_detail_azure_openai),
                footer = stringResource(R.string.provider_detail_azure_openai_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.provider_detail_azure_openai),
                    checked = instance.azureMode,
                    onCheckedChange = { on ->
                        providerRepository.updateInstance(currentInstance().copy(azureMode = on))
                        AppLogger.info(TAG, "Set azureMode=$on for ${instance.id}")
                    },
                    showDivider = false,
                )
            }
        }

        // ─── Image Generation Endpoint ──────────────────────────────
        if (instance.supportsImageEndpointSetting) {
            val mode = instance.imageEndpointMode
            SettingsSection(
                header = stringResource(R.string.provider_detail_image_generation),
                footer = when (mode) {
                    ImageEndpointMode.auto ->
                        if (instance.imageEndpointResolved != null) {
                            val resolved = if (instance.imageEndpointResolved ==
                                ImageEndpointMode.imagesGenerations
                            ) "/v1/images/generations" else "/v1/chat/completions"
                            stringResource(
                                R.string.provider_detail_image_endpoint_auto_footer_resolved,
                                resolved,
                            )
                        } else {
                            stringResource(R.string.provider_detail_image_endpoint_auto_footer)
                        }
                    ImageEndpointMode.imagesGenerations ->
                        stringResource(R.string.provider_detail_image_endpoint_images_footer)
                    ImageEndpointMode.chatCompletions ->
                        stringResource(R.string.provider_detail_image_endpoint_chat_footer)
                },
            ) {
                SettingsCardBlock {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = mode == ImageEndpointMode.auto,
                            onClick = {
                                if (mode != ImageEndpointMode.auto) {
                                    providerRepository.updateInstance(
                                        withImageEndpointMode(currentInstance(), ImageEndpointMode.auto),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        ) { Text(stringResource(R.string.provider_detail_image_endpoint_auto)) }
                        SegmentedButton(
                            selected = mode == ImageEndpointMode.imagesGenerations,
                            onClick = {
                                if (mode != ImageEndpointMode.imagesGenerations) {
                                    providerRepository.updateInstance(
                                        withImageEndpointMode(currentInstance(), ImageEndpointMode.imagesGenerations),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        ) { Text(stringResource(R.string.provider_detail_image_endpoint_images_api)) }
                        SegmentedButton(
                            selected = mode == ImageEndpointMode.chatCompletions,
                            onClick = {
                                if (mode != ImageEndpointMode.chatCompletions) {
                                    providerRepository.updateInstance(
                                        withImageEndpointMode(currentInstance(), ImageEndpointMode.chatCompletions),
                                    )
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        ) { Text(stringResource(R.string.provider_detail_image_endpoint_chat)) }
                    }
                }
            }
        }
    }
}

/**
 * [deep-audit-1039 B-04] The value a focus-loss save should persist, or null
 * when there is nothing to write.
 *
 * `edited` is the raw text buffer, `stored` the field's value on the instance
 * as it is persisted right now. Blank input normalises to null (clearing the
 * override) — that is the one case where a non-null edit legitimately becomes
 * a null write. Everything else is a plain inequality test, so the first-frame
 * `onFocusChanged(isFocused = false)` Compose delivers on mount (and every
 * later blur of an untouched field) becomes a no-op instead of an unconditional
 * updateInstance + "Saved …" log line.
 *
 * Kept a top-level pure function so the JVM test can drive the real decision
 * rather than a copy of it (same seam as `sliderStepsFor` / `VisibleOnlyFlag`).
 */
internal fun pendingConnectionFieldWrite(edited: String, stored: String?): String? {
    val normalized = edited.ifBlank { null }
    return if (normalized == stored) null else normalized
}
