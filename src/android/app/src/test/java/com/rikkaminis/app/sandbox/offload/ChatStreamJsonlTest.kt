package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [rhub1105 F-05] The :modelservice stream JSONL is the ONLY production
 * streaming path (ProviderBoundary.enforce() forbids an in-process direct
 * call), so encode/decode here is the last place a provider's usage semantics
 * can survive before the main process persists them.
 *
 * AnthropicProvider.parseUsage deliberately uses `json.has(...)` to tell two
 * facts apart: "field absent = provider does not report cache tokens" (null)
 * and "field present and 0 = caching is enabled but this turn hit nothing" (0).
 * ChatTurnPartsJson.buildUsageJson then turns that into the `hasCacheRead` /
 * `hasCacheCreation` booleans on the message metadata. Before this fix the
 * codec collapsed both into the same integer (encode `?: 0`) and then back into
 * the same null (decode `.takeIf { it > 0 }`), so a genuine zero permanently
 * lost the "caching enabled" bit across the IPC hop.
 *
 * Expectations are literals on purpose — never derive them from the codec
 * under test (a self-referential expectation hides the mutation).
 */
class ChatStreamJsonlTest {

    private fun roundTrip(usage: LLMUsage): LLMUsage {
        val line = ChatStreamJsonl.encode(LLMStreamChunk.Usage(usage))
        return (ChatStreamJsonl.decode(line) as LLMStreamChunk.Usage).usage
    }

    private fun usageLine(read: Int?, creation: Int?): String =
        ChatStreamJsonl.encode(
            LLMStreamChunk.Usage(
                LLMUsage(
                    inputTokens = 120,
                    outputTokens = 30,
                    cacheReadInputTokens = read,
                    cacheCreationInputTokens = creation,
                    latestContextTokens = 150,
                )
            )
        )

    /** The counterfactual arm: 0 must NOT come back as null. */
    @Test
    fun zeroCacheTokensSurviveCrossProcessRoundTrip() {
        val rt = roundTrip(
            LLMUsage(
                inputTokens = 120,
                outputTokens = 30,
                cacheReadInputTokens = 0,
                cacheCreationInputTokens = 0,
                latestContextTokens = 150,
            )
        )
        assertEquals(0, rt.cacheReadInputTokens)
        assertEquals(0, rt.cacheCreationInputTokens)
        // Unrelated numeric fields must be untouched by the fix.
        assertEquals(120, rt.inputTokens)
        assertEquals(30, rt.outputTokens)
        assertEquals(150, rt.latestContextTokens)
    }

    /** Symmetry arm 1: "cache not enabled" keeps its null. */
    @Test
    fun absentCacheTokensStayAbsent() {
        val rt = roundTrip(LLMUsage(inputTokens = 120, outputTokens = 30))
        assertNull(rt.cacheReadInputTokens)
        assertNull(rt.cacheCreationInputTokens)
    }

    /** Symmetry arm 2: a real non-zero value is unchanged. */
    @Test
    fun nonZeroCacheTokensSurviveCrossProcessRoundTrip() {
        val rt = roundTrip(
            LLMUsage(
                inputTokens = 120,
                outputTokens = 30,
                cacheReadInputTokens = 4096,
                cacheCreationInputTokens = 512,
            )
        )
        assertEquals(4096, rt.cacheReadInputTokens)
        assertEquals(512, rt.cacheCreationInputTokens)
    }

    /**
     * Pin the wire contract itself: key PRESENCE is the discriminator, exactly
     * what `json.has(...)` on the provider side reads. A round-trip-only test
     * could be satisfied by a codec that encodes both facts differently but
     * still loses the information on the wire.
     */
    @Test
    fun keyPresenceIsTheCacheEnabledDiscriminatorOnTheWire() {
        val disabled = JSONObject(usageLine(null, null))
        assertFalse(disabled.has("cache_read"))
        assertFalse(disabled.has("cache_creation"))

        val enabledZero = JSONObject(usageLine(0, 0))
        assertTrue(enabledZero.has("cache_read"))
        assertTrue(enabledZero.has("cache_creation"))
        assertEquals(0, enabledZero.getInt("cache_read"))
        assertEquals(0, enabledZero.getInt("cache_creation"))
    }

    /**
     * A heterogeneous / older worker might write JSONObject.NULL instead of
     * omitting the key. Presence must not be confused with a value.
     */
    @Test
    fun explicitJsonNullIsDecodedAsAbsent() {
        val line = """{"t":"usage","in":120,"out":30,"cache_read":null,"cache_creation":null,"ctx":150}"""
        val rt = (ChatStreamJsonl.decode(line) as LLMStreamChunk.Usage).usage
        assertNull(rt.cacheReadInputTokens)
        assertNull(rt.cacheCreationInputTokens)
    }
}
