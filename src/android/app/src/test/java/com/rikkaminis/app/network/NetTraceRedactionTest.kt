package com.rikkaminis.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rikkaminis.app.debug.LLMRequestLog

/**
 * Regression guard for the credential leak fixed alongside this branch.
 *
 * Why this exists: [OkHttpNetTraceListener] logs `call.request().url` for every
 * provider call, and some routes carry the API key *in the URL* (Gemini uses
 * `?key=<API_KEY>`; a user-supplied OpenAI-compatible base URL can too). This
 * listener is attached to all three provider routes, so an unredacted URL means
 * "every Gemini request writes a live key into logcat", and into
 * the app log files (filesDir/logs) whenever logging is enabled.
 *
 * The call site must route through [LLMRequestLog.redactURL]; these assertions pin
 * the property that makes that safe (no secret survives), so a future change to
 * the redaction set that drops `key` fails here instead of on a user's device.
 */
class NetTraceRedactionTest {

    @Test
    fun redactURL_stripsGeminiQueryKey() {
        val raw = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:streamGenerateContent" +
            "?alt=sse&key=AIzaSySUPERSECRETvalue1234567890"
        val redacted = LLMRequestLog.redactURL(raw)
        assertFalse("URL 里的 key 必须被抹掉", redacted.contains("AIzaSySUPERSECRETvalue1234567890"))
        assertTrue("非敏感参数要保留（否则日志失去诊断价值）", redacted.contains("alt=sse"))
        assertTrue("主机名要保留", redacted.contains("generativelanguage.googleapis.com"))
    }

    @Test
    fun redactURL_coversTheOtherSecretShapedQueryNames() {
        val raw = "https://relay.example.com/v1/chat/completions" +
            "?api_key=sk-live-AAA&access_token=BBB&authorization=CCC&token=DDD&password=EEE"
        val redacted = LLMRequestLog.redactURL(raw)
        listOf("sk-live-AAA", "BBB", "CCC", "DDD", "EEE").forEach {
            assertFalse("敏感值 $it 不应出现在日志里", redacted.contains(it))
        }
    }

    @Test
    fun redactURL_leavesKeylessURLsAlone() {
        val raw = "https://api.openai.com/v1/chat/completions"
        assertTrue("无敏感参数的 URL 不应被改写", LLMRequestLog.redactURL(raw).contains(raw))
    }
}
