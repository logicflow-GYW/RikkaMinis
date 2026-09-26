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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.rikkaminis.app.ui.util.bringIntoViewOnFocus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.ImageEndpointMode
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
        onBack()
        return
    }

    // Label editing lives on the detail screen (its title). Here we show the
    // current label so the page has context; connection params are the focus.

    var storedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(instanceId) {
        storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
    }
    var isEditingKey by remember { mutableStateOf(false) }
    var editKeyValue by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    var customBaseURL by rememberSaveable { mutableStateOf(instance.customBaseURL ?: "") }
    var customUserAgent by rememberSaveable { mutableStateOf(instance.customUserAgent ?: "") }

    fun saveBaseURLSettings() {
        // /v1 is appended automatically for all non-Gemini providers (Gemini
        // uses v1beta full-path URLs). effectiveBaseURL guards double-append.
        val appendV1 = instance.providerType != ProviderType.gemini
        providerRepository.updateInstance(
            instance.copy(
                customBaseURL = customBaseURL.ifBlank { null },
                appendV1Suffix = appendV1,
                customUserAgent = customUserAgent.ifBlank { null },
            )
        )
        AppLogger.info(
            TAG,
            "Saved base URL for ${instance.id}: url='${customBaseURL.ifBlank { "<default>" }}', appendV1=$appendV1, ua='${customUserAgent.ifBlank { "<default>" }}'",
        )
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
                        providerRepository.saveApiKey(instanceId, editKeyValue)
                        storedKey = editKeyValue
                        AppLogger.info(TAG, "Saved API key for ${instance.id}")
                        isEditingKey = false
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
                    val showUserAgentField = instance.providerType == ProviderType.openAI ||
                        instance.providerType == ProviderType.anthropic
                    if (showUserAgentField) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.provider_detail_custom_user_agent),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(4.dp))
                            SectionTextField(
                                value = customUserAgent,
                                onValueChange = { customUserAgent = it },
                                singleLine = true,
                                placeholder = stringResource(R.string.provider_detail_custom_user_agent_placeholder),
                                fieldModifier = Modifier
                                    .bringIntoViewOnFocus()
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused) saveBaseURLSettings()
                                    },
                            )
                        }
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
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = false))
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                        SegmentedButton(
                            selected = instance.useResponsesAPI,
                            onClick = {
                                if (!instance.useResponsesAPI) {
                                    providerRepository.updateInstance(instance.copy(useResponsesAPI = true))
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
                        providerRepository.updateInstance(instance.copy(azureMode = on))
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
                                        instance.copy(imageEndpointMode = ImageEndpointMode.auto),
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
                                        instance.copy(imageEndpointMode = ImageEndpointMode.imagesGenerations),
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
                                        instance.copy(imageEndpointMode = ImageEndpointMode.chatCompletions),
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