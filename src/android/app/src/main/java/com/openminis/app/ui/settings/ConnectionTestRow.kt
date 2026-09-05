package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger

private const val TAG = "ConnectionTestRow"

/** Test session state: idle → running → done(result). One-shot per tap. */
private sealed interface TestUiState {
    data object Idle : TestUiState
    data object Running : TestUiState
    data class Done(val ok: Boolean, val message: String, val latencyMs: Long) : TestUiState
}

/**
 * [T-provider-connection-tester] Inline row: "Test connection" → live request
 * through the real provider path → ✓/✗ with message + latency. Lives right
 * under the API & Connection row on the provider detail screen.
 */
@Composable
fun ConnectionTestRow(
    instance: ProviderInstance,
    storedKey: String?,
    repository: ProviderRepository,
) {
    val context = LocalContext.current
    var state by remember(instance.id) { mutableStateOf<TestUiState>(TestUiState.Idle) }

    // Single-shot launcher: fires exactly once when the state flips to Running.
    LaunchedEffect(state) {
        if (state !is TestUiState.Running) return@LaunchedEffect
        val key = storedKey
        if (key.isNullOrBlank()) {
            state = TestUiState.Done(false, context.getString(R.string.provider_test_no_key), 0)
            return@LaunchedEffect
        }
        val modelId = ProviderConnectionTester.defaultTestModelId(instance, repository)
        if (modelId == null) {
            state = TestUiState.Done(false, context.getString(R.string.provider_test_no_model), 0)
            return@LaunchedEffect
        }
        val result = ProviderConnectionTester.test(instance, key, modelId, repository, context)
        AppLogger.info(TAG, "test ${instance.id} model=$modelId ok=${result.ok} code=${result.httpCode} ${result.message} ${result.latencyMs}ms")
        state = TestUiState.Done(result.ok, result.message, result.latencyMs)
    }

    when (val s = state) {
        is TestUiState.Idle -> SettingsRow(
            title = stringResource(R.string.provider_test_connection),
            subtitle = stringResource(R.string.provider_test_connection_hint),
            icon = Icons.Default.PlayArrow,
            iconColor = MaterialTheme.colorScheme.primary,
            onClick = { state = TestUiState.Running },
            showChevron = false,
            showDivider = false,
        )
        is TestUiState.Running -> SettingsRow(
            title = stringResource(R.string.provider_test_connection),
            subtitle = stringResource(R.string.provider_test_running),
            showDivider = false,
            trailing = {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            },
        )
        is TestUiState.Done -> SettingsRow(
            title = stringResource(R.string.provider_test_connection),
            subtitle = "${if (s.ok) "✓" else "✗"} ${s.message} (${s.latencyMs}ms)",
            icon = if (s.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            iconColor = if (s.ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            onClick = { state = TestUiState.Running },
            showChevron = false,
            showDivider = false,
        )
    }
}
