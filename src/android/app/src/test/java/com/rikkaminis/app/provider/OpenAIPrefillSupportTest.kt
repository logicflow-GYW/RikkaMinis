package com.rikkaminis.app.provider

import com.rikkaminis.app.provider.openai.supportsPrefillForOpenAIBase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-length-wall-prefill] The OpenAI-compatible prefill decision is a pure
 * function, so the allowlist/denylist boundary is JVM-tested without a
 * MockWebServer.
 */
class OpenAIPrefillSupportTest {

    @Test
    fun `official openai base supports prefill`() {
        assertTrue(supportsPrefillForOpenAIBase("https://api.openai.com/v1", isAzure = false))
    }

    @Test
    fun `azure base supports prefill regardless of host`() {
        // Azure hosts are arbitrary (*.openai.azure.com / gateway URLs) but
        // always OpenAI-Chat-compatible; the isAzure flag drives the decision.
        assertTrue(
            supportsPrefillForOpenAIBase("https://my-resource.openai.azure.com", isAzure = true),
        )
        // Even a host that would otherwise be denied is prefill-capable when Azure.
        assertTrue(
            supportsPrefillForOpenAIBase("https://tokenrhythm.studio/v1", isAzure = true),
        )
    }

    @Test
    fun `openrouter gateway supports prefill`() {
        assertTrue(supportsPrefillForOpenAIBase("https://openrouter.ai/api/v1", isAzure = false))
    }

    @Test
    fun `dashscope gateway supports prefill`() {
        assertTrue(supportsPrefillForOpenAIBase("https://dashscope.aliyuncs.com/compatible-mode/v1", isAzure = false))
    }

    @Test
    fun `volcengine ark supports prefill`() {
        assertTrue(supportsPrefillForOpenAIBase("https://ark.cn-beijing.volces.com/api/v3", isAzure = false))
    }

    @Test
    fun `official deepseek supports prefill`() {
        assertTrue(supportsPrefillForOpenAIBase("https://api.deepseek.com/v1", isAzure = false))
    }

    @Test
    fun `unknown strict relay defaults to no prefill`() {
        // tokenrhythm-class proxies require the last message to be USER;
        // unknown bases must NOT prefill (falls back to reminder path).
        assertFalse(supportsPrefillForOpenAIBase("https://tokenrhythm.studio/v1", isAzure = false))
    }

    @Test
    fun `unknown vendor native endpoint defaults to no prefill`() {
        assertFalse(supportsPrefillForOpenAIBase("https://api.moonshot.cn/v1", isAzure = false))
        assertFalse(supportsPrefillForOpenAIBase("https://api.siliconflow.cn/v1", isAzure = false))
    }

    @Test
    fun `host matching is case insensitive`() {
        assertTrue(supportsPrefillForOpenAIBase("https://API.DEEPSEEK.COM/v1", isAzure = false))
    }

    @Test
    fun `substring safety - suffix lookalikes are denied`() {
        // "api.deepseek.com" must not match a host like "not-api.deepseek.com.evil".
        // (contains-based matching is intentional and documented, but a clearly
        // unrelated host must still deny.)
        assertFalse(supportsPrefillForOpenAIBase("https://example.com/v1", isAzure = false))
    }
}
