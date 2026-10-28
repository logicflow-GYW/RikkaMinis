package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [r3-b08] Wiring shape for the B08 findings whose defect was NOT "a pure
 * function got the boundary wrong" but "the call site never consumed the
 * signal": the fixes are one-line branches inside long methods, so what has to
 * be pinned is that those branches exist and stay wired.
 *
 * Source-shape based (the pure logic lives in the per-finding tests next door).
 * Every assertion is written so the PRE-FIX tree fails it. Comments are stripped
 * before matching so a KDoc mention cannot satisfy an assertion.
 */
class MetaR3B08WiringTest {

    @Test
    fun `F-10 a non-ConfigError write failure is reported as write_failed`() {
        val code = code(CONFIG_BRIDGE)
        // Anchor on the write path's LOG LINE, not on "catch (e: Throwable)":
        // ConfigBridge has a second Throwable arm on the READ path
        // (`read_failed`, line ~215) that a first-match search would test instead.
        val at = code.indexOf("AppLogger.error(TAG, \"write failed for ")
        assertTrue("write-path Throwable arm not found in $CONFIG_BRIDGE", at >= 0)
        val block = code.substring(maxOf(0, at - 900), minOf(code.length, at + 200))
        assertTrue(
            "the Throwable arm still only logs — an IllegalState/SQLite failure falls "
                + "through to the `applied.length() == 0` branch and is reported to the "
                + "user as all_rejected / \"User rejected every row.\"",
            "earlyError = JSONObject()" in block,
        )
        assertTrue(
            "the failure must carry its own error code, not reuse all_rejected",
            "write_failed" in block,
        )
    }

    @Test
    fun `F-11 a draft-without-row write fails instead of reporting ok`() {
        val code = code(CONFIG_BUILTINS)
        val at = code.indexOf("onDraftWithoutRow = {")
        assertTrue("onDraftWithoutRow not found in $CONFIG_BUILTINS", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 1600))
        assertTrue(
            "a session write that can land nowhere is still only logged, so "
                + "`minis-config set session.*` answers ok:true / \"Settings updated.\" "
                + "for a value that evaporates",
            "throw ConfigError.InvalidValue(" in block,
        )
    }

    @Test
    fun `F-05 the non-streaming OpenAI entry point refuses a truncated reply`() {
        val code = code(OPENAI)
        val at = code.indexOf("override suspend fun sendMessageClamped(")
        assertTrue("sendMessageClamped not found in $OPENAI", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 4000))
        assertTrue(
            "Finished.truncated is still dropped — minis-model-use returns half an "
                + "answer with exit_code 0",
            "truncated = chunk.truncated" in block,
        )
        assertTrue(
            "the truncation verdict is collected but never raised",
            "throw LLMError.TransientError(" in block,
        )
    }

    @Test
    fun `F-08 the Responses builder resolves its effort through the shared clamp`() {
        val code = code(OPENAI)
        assertEquals(
            "clampResponsesEffort must be defined AND used by buildResponsesAPIBody",
            2,
            Regex("clampResponsesEffort\\(").findAll(code).count(),
        )
        assertTrue(
            "the Responses path must read the learned/declared tier set, not just "
                + "the family clamp",
            "EffortTierLearner.learned(" in code.substring(
                code.indexOf("val effort = if (thinkingLevel.isEnabled)"),
            ),
        )
    }

    @Test
    fun `F-13 all three stream parsers read SSE frames through the shared reader`() {
        for ((label, rel) in listOf("openai" to OPENAI, "anthropic" to ANTHROPIC, "gemini" to GEMINI)) {
            val code = code(rel)
            assertTrue(
                "$label still hand-rolls the `data: ` prefix (ignores the optional space)",
                "sseDataPayload(l)" in code,
            )
            assertTrue(
                "$label still contains a strict `data: ` prefix check",
                !code.contains("startsWith(\"data: \")") && !code.contains("startsWith(\"data:\")"),
            )
        }
    }

    @Test
    fun `F-03 the Gemini stream raises the inline error frame`() {
        val code = code(GEMINI)
        assertTrue(
            "a `data: {\"error\":{…}}` frame still falls through to EOF and is "
                + "re-signalled as a truncation (continuation retry of a refused request)",
            "optJSONObject(\"error\")" in code && "geminiStreamErrorStatus(" in code,
        )
    }

    @Test
    fun `F-09 the Anthropic serializer skips an empty text block`() {
        val code = code(ANTHROPIC)
        val at = code.indexOf("is AgentContentPart.Text ->")
        assertTrue("no Text branch in $ANTHROPIC", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 600))
        assertTrue(
            "an empty text block still reaches the wire — the Messages API answers "
                + "400 \"text content blocks must be non-empty\"",
            "part.text.isNotEmpty()" in block,
        )
    }

    @Test
    fun `F-12 Doubao surfaces a non-zero frame code`() {
        val code = code(VOICE)
        assertTrue("doubaoFrameError is defined but unused", "doubaoFrameError(" in code)
        val at = code.indexOf("override suspend fun synthesize(")
        val doubao = code.indexOf("class DoubaoVoiceProvider")
        val synthesizeAt = code.indexOf("override suspend fun synthesize(", doubao)
        assertTrue("Doubao synthesize not found in $VOICE", synthesizeAt > 0)
        assertTrue(
            "Doubao still `continue`s on an error frame (and $at exists as a decoy)",
            "doubaoFrameError(" in code.substring(synthesizeAt, minOf(code.length, synthesizeAt + 2000)),
        )
    }

    @Test
    fun `F-04 ModelsCollection writes the explicit numbers into overrides`() {
        val code = code(MODELS_COLLECTION)
        val at = code.indexOf("val entry = ModelEntry(")
        assertTrue("ModelEntry construction not found in $MODELS_COLLECTION", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 900))
        assertTrue(
            "the add payload's context_window/max_output_tokens/supports_reasoning "
                + "still live only on baseModel, where models.dev enrichment overwrites them",
            "overrides = ModelOverrides(" in block &&
                "contextWindow = contextWindow" in block &&
                "maxOutputTokens = maxOut" in block &&
                "supportsReasoning = supportsReasoning" in block,
        )
    }

    @Test
    fun `F-07 the Azure adapter hands over the raw base url`() {
        val code = code(ADAPTERS)
        assertTrue(
            "the OpenAI models adapter still passes effectiveBaseURL for Azure, which "
                + "appends /v1 INSIDE the api-version query value",
            "if (instance.azureMode) instance.customBaseURL else instance.effectiveBaseURL" in code,
        )
    }

    @Test
    fun `F-06 and F-07 the models apis call the extracted decisions`() {
        assertTrue(
            "OpenAI models api does not consult the auth-failure decision",
            "usesBuiltinCatalogOnFailure(isCustomBase, response.code)" in code(OPENAI_MODELS),
        )
        assertTrue(
            "OpenAI models api does not use the azure-aware url builder",
            "buildModelsURL(baseURL, azureMode)" in code(OPENAI_MODELS),
        )
        assertTrue(
            "Anthropic models api still answers every failure with the builtin catalog",
            "usesBuiltinCatalogOnFailure(isCustomEndpoint, lastFailureCode)" in code(ANTHROPIC_MODELS),
        )
        assertTrue(
            "Gemini models api still answers a relay failure with the builtin catalog",
            "isOfficialGeminiBase(baseUrl)" in code(GEMINI_MODELS),
        )
    }

    // -- helpers ------------------------------------------------------------

    private fun code(relative: String): String = stripComments(readRepoFile(relative))

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}"
        )
    }

    /** Comments out, literals kept (a string may legitimately carry the shape). */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    sb.append(c)
                }
                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i += 1
                }
                inString -> {
                    sb.append(c)
                    if (c == '\\') {
                        sb.append(next)
                        i += 1
                    } else if (c == '"') {
                        inString = false
                    }
                }
                else -> when {
                    c == '"' -> {
                        inString = true
                        sb.append(c)
                    }
                    c == '/' && next == '/' -> {
                        inLine = true
                        i += 1
                    }
                    c == '/' && next == '*' -> {
                        inBlock = true
                        i += 1
                    }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }

    private companion object {
        const val BASE = "src/android/app/src/main/java/com/rikkaminis/app/"
        const val CONFIG_BRIDGE = BASE + "config/ConfigBridge.kt"
        const val CONFIG_BUILTINS = BASE + "config/ConfigBuiltins.kt"
        const val OPENAI = BASE + "provider/openai/OpenAIProvider.kt"
        const val OPENAI_MODELS = BASE + "provider/openai/OpenAIModelsApi.kt"
        const val ANTHROPIC = BASE + "provider/anthropic/AnthropicProvider.kt"
        const val ANTHROPIC_MODELS = BASE + "provider/anthropic/AnthropicModelsApi.kt"
        const val GEMINI = BASE + "provider/gemini/GeminiProvider.kt"
        const val GEMINI_MODELS = BASE + "provider/gemini/GeminiModelsApi.kt"
        const val VOICE = BASE + "provider/voice/VoiceProviderVendors.kt"
        const val ADAPTERS = BASE + "provider/ModelListProviderAdapters.kt"
        const val MODELS_COLLECTION = BASE + "config/collections/ModelsCollection.kt"
    }
}
