package com.rikkaminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import android.widget.Toast
import com.rikkaminis.app.ui.theme.ProviderAccents
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.data.repository.ModelRefreshResult
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.rikkaminis.app.ui.components.MinisButton
import com.rikkaminis.app.ui.components.RowLabel
import com.rikkaminis.app.ui.components.SectionTextField

private enum class AddProviderStep {
    CHOOSE_TYPE,
    CONFIGURE,
}

@Composable
fun AddProviderScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var step by remember { mutableStateOf(AddProviderStep.CHOOSE_TYPE) }
    var selectedType by remember { mutableStateOf<ProviderType?>(null) }

    // Unified back handler: reuse each step's onBack so predictive-back gesture
    // and the top-bar arrow behave identically (go back to prior step, not exit).
    val handleBack: () -> Unit = {
        when (step) {
            AddProviderStep.CHOOSE_TYPE -> onBack()
            AddProviderStep.CONFIGURE -> {
                step = AddProviderStep.CHOOSE_TYPE
                selectedType = null
            }
        }
    }

    // Intercept system back on inner steps; let outer handler pop the route on step 0.
    BackHandler(enabled = step != AddProviderStep.CHOOSE_TYPE) { handleBack() }

    when (step) {
        AddProviderStep.CHOOSE_TYPE -> ChooseProviderScreen(
            onBack = handleBack,
            onSelect = { type ->
                selectedType = type
                step = AddProviderStep.CONFIGURE
            },
        )
        AddProviderStep.CONFIGURE -> ConfigureProviderScreen(
            providerType = selectedType!!,
            providerRepository = providerRepository,
            onBack = handleBack,
            onSaved = onSaved,
        )
    }
}

/** Display order matching iOS. */
private val providerDisplayOrder = listOf(
    ProviderType.openAI,
    ProviderType.anthropic,
    ProviderType.gemini,
    ProviderType.xAI,
    ProviderType.kimiCode,
    ProviderType.openRouter,
)

/** Icon and color per provider type, matching iOS SF Symbols. */
private fun providerIcon(type: ProviderType): Pair<ImageVector, Color> = when (type) {
    ProviderType.openAI -> Icons.Default.Hub to ProviderAccents.openAI             // green
    ProviderType.anthropic -> Icons.Default.AutoAwesome to ProviderAccents.anthropic // purple
    ProviderType.gemini -> Icons.Default.Diamond to ProviderAccents.gemini         // blue
    ProviderType.openRouter -> Icons.Default.AltRoute to ProviderAccents.openRouter // cyan
    ProviderType.xAI -> Icons.Default.FlashOn to ProviderAccents.xAI               // orange — Grok visual cue
    // [T-kimi-oauth] Indigo — matches iOS's Kimi accent.
    ProviderType.kimiCode -> Icons.Default.Terminal to ProviderAccents.kimiCode
}

// -- Step 1: Choose Provider Type --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseProviderScreen(
    onBack: () -> Unit,
    onSelect: (ProviderType) -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.provider_list_add_provider),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_choose_provider),
            footer = stringResource(R.string.add_provider_you_can_add_multiple_instances_of_the_sa),
        ) {
            providerDisplayOrder.forEachIndexed { index, type ->
                val displayTitle = when (type) {
                    ProviderType.openAI -> "OpenAI / Compatible API"
                    ProviderType.anthropic -> "Anthropic / Compatible API"
                    ProviderType.gemini -> "Google Gemini"
                    ProviderType.openRouter -> "OpenRouter"
                    ProviderType.xAI -> "xAI (Grok)"
                    ProviderType.kimiCode -> "Kimi Code"
                }
                // Describe which vendors each protocol supports, rather than a
                // raw built-in model count.
                val subtitleRes = when (type) {
                    ProviderType.openAI -> R.string.add_provider_subtitle_openai
                    ProviderType.anthropic -> R.string.add_provider_subtitle_anthropic
                    ProviderType.gemini -> R.string.add_provider_subtitle_gemini
                    ProviderType.openRouter -> R.string.add_provider_subtitle_openrouter
                    ProviderType.xAI -> R.string.add_provider_subtitle_xai
                    ProviderType.kimiCode -> R.string.add_provider_subtitle_kimi
                }
                val (icon, iconColor) = providerIcon(type)
                SettingsRow(
                    title = displayTitle,
                    subtitle = stringResource(subtitleRes),
                    icon = icon,
                    iconColor = iconColor,
                    onClick = { onSelect(type) },
                    showDivider = index < providerDisplayOrder.size - 1,
                )
            }
        }

        // [voice-removed] The Voice Chat Providers template section was removed
        // with the rest of the in-app voice UI. Voice-capable providers can
        // still be added as regular OpenAI/Anthropic-compatible instances.
        Spacer(Modifier.height(24.dp))
    }
}

// -- Step 2: Configure & Save --

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureProviderScreen(
    providerType: ProviderType,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    // Compute default label with auto-increment (e.g. "OpenAI", "OpenAI 2", ...)
    val config by providerRepository.config.collectAsState()
    val defaultLabel = remember(config) {
        val baseName = providerType.displayName
        val existingLabels = config.instances.map { it.label }.toSet()
        if (baseName !in existingLabels) baseName
        else {
            var n = 2
            while ("$baseName $n" in existingLabels) n++
            "$baseName $n"
        }
    }

    var label by remember { mutableStateOf(defaultLabel) }
    // [T-provider-name-chinese-34602, port iOS 7b283951] Flips true the
    // first time the user types into the label field. While `false`, the
    // LaunchedEffect below keeps `label` glued to the auto-incremented
    // default, so adding a second OpenAI instance picks up "OpenAI 2"
    // automatically. Once the user edits the label (even just to clear
    // it for typing — including non-ASCII like CJK / kana / emoji),
    // the seed is suppressed so further provider-list changes never
    // clobber what they typed. Without this gate the `remember(config)`
    // recomputation, combined with Compose tearing down + recreating
    // ConfigureProviderScreen on step navigation, makes the field
    // appear to reject Chinese — the iOS root cause Telegram 34602
    // reported, with the same Android equivalent here.
    var labelEdited by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(defaultLabel, labelEdited) {
        if (!labelEdited) label = defaultLabel
    }
    var apiKey by remember { mutableStateOf("") }
    var customBaseURL by remember { mutableStateOf("") }

    SettingsScaffold(
        title = stringResource(R.string.add_provider_configure_provider, providerType.displayName),
        onBack = onBack,
    ) {
        // Identity section — Label only. Each provider auto-suggests a
        // unique label so users don't have to type one for the common case.
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = stringResource(R.string.add_provider_the_label_is_shown_in_the_provider_list_),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.provider_detail_label))
                SectionTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        labelEdited = true
                    },
                    placeholder = providerType.displayName,
                    singleLine = true,
                )
            }
        }

        ApiKeyConfigSection(
            providerType = providerType,
            label = label,
            apiKey = apiKey,
            onApiKeyChange = { apiKey = it },
            customBaseURL = customBaseURL,
            onCustomBaseURLChange = { customBaseURL = it },
            providerRepository = providerRepository,
            onSaved = onSaved,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ColumnScope.ApiKeyConfigSection(
    providerType: ProviderType,
    label: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    customBaseURL: String,
    onCustomBaseURLChange: (String) -> Unit,
    providerRepository: ProviderRepository,
    onSaved: () -> Unit,
) {
    // [T-provider-save-refresh] The post-save model refresh MUST survive the
    // navigation away from this screen. rememberCoroutineScope() would cancel
    // the fetch the moment onSaved() pops the route (composition disposal),
    // leaving a freshly-added provider with an empty model list. The
    // application-scoped scope outlives composition (same pattern as the
    // WebDAV backup transfer in BackupSettingsScreen).
    val appContext = LocalContext.current.applicationContext
    var showApiKeyPlaintext by remember { mutableStateOf(false) }
    // /v1 is appended automatically for all non-Gemini providers (Gemini uses
    // v1beta full-path URLs). effectiveBaseURL already guards against double-append.
    val appendV1Suffix = providerType != ProviderType.gemini
    // OpenAI API Format: false = Chat Completions, true = Responses API
    var useResponsesAPI by remember { mutableStateOf(false) }

    // ── Credential ──────────────────────────────────────────────────────
    val keyPlaceholder = when (providerType) {
        ProviderType.anthropic -> "sk-ant-..."
        ProviderType.openAI -> "sk-..."
        ProviderType.gemini -> "Gemini API Key..."
        ProviderType.openRouter -> "sk-or-..."
        ProviderType.xAI -> "xai-..."
        ProviderType.kimiCode -> "sk-..."
    }
    SettingsSection(
        header = stringResource(R.string.add_provider_credential),
        footer = stringResource(R.string.add_provider_your_key_is_stored_securely_in_encrypted) +
            "\n" + stringResource(R.string.add_provider_multi_key_hint),
    ) {
        SettingsCardBlock {
            RowLabel(text = stringResource(R.string.provider_list_api_key))
            SectionTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                placeholder = keyPlaceholder,
                singleLine = true,
                visualTransformation = if (showApiKeyPlaintext) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKeyPlaintext = !showApiKeyPlaintext }) {
                        Icon(
                            if (showApiKeyPlaintext) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKeyPlaintext) "Hide" else "Show",
                        )
                    }
                },
            )
        }
    }

    // ── Endpoint (skip for OpenRouter — fixed base URL) ─────────────────
    if (providerType != ProviderType.openRouter) {
        val defaultUrl = when (providerType) {
            ProviderType.gemini -> "https://generativelanguage.googleapis.com/v1beta"
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.openAI -> "https://api.openai.com"
            else -> "https://api.example.com"
        }
        // T-mimo-anthropic-endpoint-android: Anthropic third-party
        // compatible services (e.g. Mimo) frequently host the Anthropic
        // surface under a path suffix like "/anthropic" rather than at
        // the host root. Users routinely paste just the host
        // (https://token-plan-cn.xiaomimimo.com) and hit 404 because
        // /v1/messages then resolves to the wrong route. Surface a hint
        // on the Anthropic endpoint footer so this can be discovered
        // without scraping issue threads. Default Anthropic + other
        // provider types keep their original footer copy.
        val baseUrlFooter = if (providerType == ProviderType.gemini) {
            "Leave empty to use the default Google endpoint. Enter the full base URL including version path."
        } else if (providerType == ProviderType.anthropic) {
            stringResource(R.string.add_provider_endpoint_anthropic_hint)
        } else {
            "Leave empty to use the default endpoint. \"/v1\" is appended automatically — enter the base host only."
        }
        SettingsSection(
            header = stringResource(R.string.add_provider_endpoint),
            footer = baseUrlFooter,
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_provider_custom_api_base_optional))
                SectionTextField(
                    value = customBaseURL,
                    onValueChange = onCustomBaseURLChange,
                    placeholder = defaultUrl,
                    singleLine = true,
                )
            }
        }
    }

    // ── API Format (OpenAI only) ────────────────────────────────────────
    if (providerType == ProviderType.openAI) {
        SettingsSection(
            header = stringResource(R.string.provider_detail_api_format),
            footer = if (useResponsesAPI) {
                "Uses /v1/responses endpoint format. Required for some Responses-API-only services."
            } else {
                "Standard /v1/chat/completions format. Compatible with most OpenAI-compatible services."
            },
        ) {
            SettingsCardBlock {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !useResponsesAPI,
                        onClick = { useResponsesAPI = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.provider_detail_chat_completions)) }
                    SegmentedButton(
                        selected = useResponsesAPI,
                        onClick = { useResponsesAPI = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.provider_detail_responses_api)) }
                }
            }
        }
    }

    // ── Save button (outside any section — terminal action) ────────────
    Spacer(Modifier.height(20.dp))
    MinisButton(
        onClick = {
            val trimmedBase = customBaseURL.trim()
            val instance = ProviderInstance(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { providerType.displayName },
                providerType = providerType,
                credentialType = ProviderCredential.apiKey,
                customBaseURL = trimmedBase.ifEmpty { null },
                appendV1Suffix = appendV1Suffix,
                // Only OpenAI-family providers expose the Responses API toggle.
                useResponsesAPI = providerType == ProviderType.openAI && useResponsesAPI,
            )
            providerRepository.addInstance(instance)
            providerRepository.saveApiKey(instance.id, apiKey.trim())
            // Auto-refresh models in background (fetches from API or falls back to models.dev).
            // Launched on the app-scoped scope so the fetch survives this screen's disposal.
            // forceRefresh=true bypasses the 7-day ProviderModelsCache so a brand-new provider
            // re-validates its URL+key immediately instead of reusing a stale cached result
            // and leaving the model list empty until the next daily auto-refresh.
            (appContext as? MinisApp)?.applicationScope?.launch {
                val result = providerRepository.refreshModels(instance, forceRefresh = true)
                if (result != ModelRefreshResult.SUCCESS_API) {
                    // [fix/audit-b22 / T6-L6] These three were hardcoded Chinese
                    // while the rest of this screen uses R.string — English
                    // users got a Chinese toast.
                    val msg = when (result) {
                        ModelRefreshResult.NO_KEY -> appContext.getString(R.string.provider_saved_no_api_key)
                        ModelRefreshResult.FAILURE -> appContext.getString(R.string.provider_saved_models_failed)
                        ModelRefreshResult.PRESERVED -> appContext.getString(R.string.provider_saved_models_unreachable)
                        else -> null
                    }
                    // [fix/addprovider-toast-looper] Toast 必须回主线程弹，
                    // applicationScope 跑在 Dispatchers.IO，无 Looper，直接
                    // Toast 会抛 "Can't toast on a thread that has not called
                    // Looper.prepare()" NPE 闪退（密钥填错/模型列表拉取失败
                    // 时必现）。
                    msg?.let {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, it, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            onSaved()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        enabled = apiKey.isNotBlank(),
    ) {
        Text(stringResource(R.string.provider_list_add_provider))
    }
}