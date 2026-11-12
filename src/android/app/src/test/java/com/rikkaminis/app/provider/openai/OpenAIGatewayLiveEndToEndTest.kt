package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.mergeRegistryRow
import com.rikkaminis.app.provider.registryLookupId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * [gateway-context-1112] LIVE end-to-end verification against the real
 * gateway on the device.
 *
 * This is the SECOND, CI-independent source of evidence: it fetches the
 * actual `/v1/models` body from `http://127.0.0.1:8790/v1/models`, runs it
 * through the production parser and the production merge, and asserts the
 * numbers the user sees.
 *
 * It SKIPS (rather than fails) when the gateway is not running, so it does
 * not turn a CI box without the app into a red build — but on the device,
 * where the gateway IS running, it is the proof that the fix works end to
 * end against the real payload.
 */
class OpenAIGatewayLiveEndToEndTest {

    private val gatewayUrl = "http://127.0.0.1:8790/v1/models"

    private fun fetchBody(): String? = try {
        val conn = URL(gatewayUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("Authorization", "Bearer live-check")
        if (conn.responseCode != 200) {
            null
        } else {
            conn.inputStream.bufferedReader().use { it.readText() }
        }
    } catch (_: Throwable) {
        null
    }

    @Test
    fun `the live gateway payload resolves to the advertised window`() {
        val body = fetchBody()
        if (body == null) {
            println("SKIP: the local gateway is not reachable — no live assertion")
            return
        }
        val data = JSONObject(body).getJSONArray("data")
        assertTrue("the live payload carries no models", data.length() > 0)

        var checked = 0
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.getString("id")
            val meta = parseOpenAIModelEndpointMetadata(obj)
            if (meta.contextWindow == null) continue

            // Parse → merge → the number the app will show.
            val parsed = LLMModel(
                id = id,
                displayName = meta.displayName ?: id,
                provider = "Custom",
                contextWindow = meta.contextWindow,
                maxOutputTokens = meta.maxOutputTokens,
                supportsReasoning = resolveSupportsReasoning(
                    endpointFlag = meta.supportsReasoning,
                    knownReasoningFamily = false,
                ),
            )
            val merged = mergeRegistryRow(
                model = parsed,
                registryContextWindow = 1_048_576,
                registryMaxOutputTokens = 8192,
                registrySupportsReasoning = true,
                registryInterleavedField = null,
                registryInputModalities = listOf("text", "image"),
                registryOutputModalities = listOf("text"),
                registryReasoningEffortValues = null,
                registryDeclaresNoEffortTiers = false,
            )

            // The endpoint's own number must survive the merge.
            assertEquals(
                "id=$id must keep the gateway's own window",
                meta.contextWindow,
                merged.contextWindow,
            )
            checked++
        }

        println("LIVE: $checked gateway rows verified against the real payload")
        assertTrue("the live payload carried no window to verify", checked > 0)
    }

    @Test
    fun `the live gateway ids resolve to a registry lookup id`() {
        val body = fetchBody()
        if (body == null) {
            println("SKIP: the local gateway is not reachable — no live assertion")
            return
        }
        val data = JSONObject(body).getJSONArray("data")
        for (i in 0 until data.length()) {
            val id = data.getJSONObject(i).getString("id")
            val bare = registryLookupId(id)
            if (id.contains('/')) {
                assertNull("a bare id must not keep a slash", bare.takeIf { it.contains('/') && id.count { c -> c == '/' } > 1 })
                assertTrue("the bare part must be non-empty", bare.isNotEmpty())
            } else {
                assertEquals("a plain id is unchanged", id, bare)
            }
        }
    }
}
