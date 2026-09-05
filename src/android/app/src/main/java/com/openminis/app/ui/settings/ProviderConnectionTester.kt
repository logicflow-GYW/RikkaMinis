package com.openminis.app.ui.settings

import android.content.Context
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * [T-provider-connection-tester] Fire one real, minimal request against a
 * provider instance to verify URL + credential + model selection all work
 * before the user commits it to daily driving (RikkaHub
 * ProviderConnectionTester parity).
 *
 * Deliberately SHARES the real provider path ([ProviderFactory.create]) so a
 * passing test proves the exact code path chat uses — the test is worthless
 * if it re-implements request building.
 */
object ProviderConnectionTester {

    /** Result of one test round. Never throws. */
    data class Result(
        val ok: Boolean,
        val httpCode: Int?,
        val message: String,
        val latencyMs: Long,
    )

    suspend fun test(
        instance: ProviderInstance,
        apiKey: String,
        testModelId: String,
        repository: ProviderRepository,
        context: Context,
    ): Result = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        fun latency() = System.currentTimeMillis() - started
        try {
            val model = repository.entriesFor(instance.id).firstOrNull { it.model.id == testModelId }?.model
                ?: LLMModel(
                    id = testModelId,
                    displayName = testModelId,
                    provider = instance.providerType.displayName,
                )
            val provider = withTimeoutOrNull(15_000) {
                ProviderFactory.create(instance, apiKey, model, context)
            } ?: return@withContext Result(false, null, "Provider construction timed out", latency())
            val response = withTimeoutOrNull(45_000) {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "ping")),
                    systemPrompt = null,
                    maxTokens = 16,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = ThinkingLevel.OFF,
                )
            } ?: return@withContext Result(
                false, null,
                "Timed out after 45s — endpoint accepted the connection but never answered",
                latency(),
            )
            val content = response.content.trim().ifEmpty { "(empty response)" }
            Result(true, null, "OK · ${content.take(80)}", latency())
        } catch (t: CancellationException) {
            throw t
        } catch (t: TimeoutCancellationException) {
            Result(false, null, "Timed out — endpoint accepted the connection but never answered", latency())
        } catch (t: SocketTimeoutException) {
            Result(false, null, "Socket timeout — host unreachable or dropped the connection", latency())
        } catch (t: UnknownHostException) {
            Result(false, null, "Unknown host — check the base URL", latency())
        } catch (t: ConnectException) {
            Result(false, null, "Connection refused — host not listening on that port", latency())
        } catch (t: SSLException) {
            Result(false, null, "TLS failure — certificate mismatch or proxy interference", latency())
        } catch (t: LLMError.InvalidApiKey) {
            Result(false, 401, "Invalid API key — ${t.detail}".take(140), latency())
        } catch (t: LLMError.RateLimited) {
            Result(false, 429, "Rate limited by the provider", latency())
        } catch (t: LLMError.ProviderError) {
            Result(false, null, t.detail.take(140), latency())
        } catch (t: LLMError.NetworkError) {
            Result(false, null, t.message?.take(140) ?: "Network error", latency())
        } catch (t: Exception) {
            Result(false, null, "${t.javaClass.simpleName}: ${t.message ?: "unknown error"}", latency())
        }
    }

    /** Pick the instance's default model id (first visible entry, else first entry, else built-in hint). */
    fun defaultTestModelId(instance: ProviderInstance, repository: ProviderRepository): String? {
        val entries = repository.visibleEntries(instance.id).ifEmpty { repository.entriesFor(instance.id) }
        return entries.firstOrNull()?.model?.id
            ?: when (instance.providerType) {
                ProviderType.openAI -> "gpt-4o-mini"
                ProviderType.anthropic -> "claude-sonnet-4-5"
                ProviderType.gemini -> "gemini-2.5-flash"
                else -> null
            }
    }
}
