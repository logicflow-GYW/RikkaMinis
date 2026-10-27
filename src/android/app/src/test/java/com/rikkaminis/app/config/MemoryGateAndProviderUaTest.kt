package com.rikkaminis.app.config

import com.rikkaminis.app.config.collections.CUSTOM_USER_AGENT_OAUTH_MESSAGE
import com.rikkaminis.app.config.collections.customUserAgentWritable
import com.rikkaminis.app.config.collections.resolveAddCustomUserAgent
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.provider.voice.VoiceInputRequest
import com.rikkaminis.app.provider.voice.VoiceOutputRequest
import com.rikkaminis.app.provider.voice.VoiceProvider
import com.rikkaminis.app.provider.voice.VoiceProviderFactory
import com.rikkaminis.app.ui.chat.MEMORY_DISABLED_TOOL_TITLE
import com.rikkaminis.app.ui.chat.memoryToolDisabledMessage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [fix/da1020-memory-gate-provider-fields] Covers the two pure pieces this
 * fix added:
 *  - the memory tool gate ([memoryToolDisabledMessage]) shared by the
 *    memory_write / memory_rollup executors, and
 *  - the provider `add` customUserAgent resolution
 *    ([resolveAddCustomUserAgent] + [customUserAgentWritable]), which reuses
 *    the per-instance write field's credential rule.
 *
 * The wiring (rollup executor + wrapper call sites, `add` →
 * ProviderInstance, ConfigBuiltins providers summary) is asserted by the
 * static gate `scripts/scan/memory_gate_provider_fields_check.py`; the
 * executor/bridge paths need a live ViewModel / registry + Context and are
 * exercised on-device via minis-config.
 *
 * [deep-audit-1029 ISSUE-05] Second half: the **voice** family
 * (`VoiceProvider` / `VoiceProviderFactory` / `VoiceProviderVendors`) was the
 * one outbound subsystem with zero `customUserAgent` wiring — chat, model
 * lists and the connectivity probe all honoured it, so a relay with a
 * UA allow-list worked for chat and 403'd every ASR/TTS call. The voice tests
 * below follow the `provider/ProviderUserAgentParityTest.kt` precedent
 * (deep-audit-1019 ISSUE-04, the same "one link of the family kept the
 * override" defect): a MockWebServer round-trip observes the User-Agent that
 * actually reaches the socket, and source pins cover what a JVM test cannot
 * drive (the Xunfei TTS WebSocket, the factory's post-construction
 * injection).
 *
 * Expected values are hardcoded literals (NOT derived from the constants
 * under test) so a constant change can't silently mask a regression.
 */
class MemoryGateAndProviderUaTest {

    // ── voice family: the same per-instance override (da1029 ISSUE-05) ──

    /** Deliberately not the app's default UA — a pass must not be accidental. */
    private val voiceCustomUa = "Minis-Voice-Unit-Test/7.7 (opaque)"

    private lateinit var voiceServer: MockWebServer
    private lateinit var voiceBaseUrl: String

    @Before
    fun startVoiceServer() {
        voiceServer = MockWebServer()
        voiceServer.start()
        voiceBaseUrl = voiceServer.url("/").toString().trimEnd('/')
    }

    @After
    fun stopVoiceServer() {
        voiceServer.shutdown()
    }

    private fun voiceProvider(customUserAgent: String? = null): VoiceProvider =
        VoiceProvider("voice-unit-test", voiceBaseUrl, "test-key").also {
            it.customUserAgent = customUserAgent
        }

    @Test
    fun voice_tts_request_carries_the_instance_custom_user_agent() = runBlocking {
        voiceServer.enqueue(MockResponse().setBody("AUDIO-BYTES"))

        val audio = voiceProvider(voiceCustomUa).synthesize(VoiceOutputRequest(input = "hello"))

        assertEquals("AUDIO-BYTES", String(audio, Charsets.UTF_8))
        // Before the fix this header read "okhttp/4.12.0" on every voice call.
        assertEquals(voiceCustomUa, voiceServer.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun voice_asr_request_carries_the_instance_custom_user_agent() = runBlocking {
        voiceServer.enqueue(MockResponse().setBody("""{"text":"hi"}"""))

        val result = voiceProvider(voiceCustomUa)
            .transcribe(VoiceInputRequest(audioData = ByteArray(16)))

        assertEquals("hi", result.text)
        assertEquals(voiceCustomUa, voiceServer.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun voice_request_without_an_override_falls_back_to_the_branded_default() = runBlocking {
        voiceServer.enqueue(MockResponse().setBody("AUDIO-BYTES"))

        voiceProvider().synthesize(VoiceOutputRequest(input = "hello"))

        // Consistency with the chat link: a null/blank override means
        // MinisUserAgent.DEFAULT, not "leave OkHttp's own UA in place".
        val ua = voiceServer.takeRequest().getHeader("User-Agent")
        assertTrue("expected the branded Minis UA, got $ua", ua != null && ua.startsWith("Minis/"))
    }

    @Test
    fun voice_factory_injects_the_instance_custom_user_agent() {
        val provider = VoiceProviderFactory.make(
            voiceInstance(customUserAgent = voiceCustomUa),
            "test-key",
        )

        assertNotNull(provider)
        assertEquals(voiceCustomUa, provider?.customUserAgent)
    }

    @Test
    fun voice_factory_keeps_recording_a_null_override_as_null() {
        val provider = VoiceProviderFactory.make(voiceInstance(customUserAgent = null), "test-key")

        assertNotNull(provider)
        assertNull(provider?.customUserAgent)
    }

    @Test
    fun voice_factory_still_returns_null_for_kimi_code() {
        // The post-construction injection must not turn a "no voice here"
        // answer into a wrapper object.
        val instance = ProviderInstance(
            id = "inst-kimi-voice",
            label = "Kimi",
            providerType = ProviderType.kimiCode,
            credentialType = ProviderCredential.apiKey,
        )

        assertNull(VoiceProviderFactory.make(instance, "test-key"))
    }

    private fun voiceInstance(customUserAgent: String?): ProviderInstance = ProviderInstance(
        id = "inst-voice",
        label = "Voice Test",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://api.groq.com/openai",
        customUserAgent = customUserAgent,
    )

    // ── voice family: the wiring a JVM test cannot drive ──────────────────

    @Test
    fun voice_provider_applies_the_override_in_its_http_chokepoint() {
        val src = readRepoFile(VOICE_PROVIDER_REL)

        // Hand-written literal: exactly one application, inside
        // executeRequest — the single HTTP door every ASR/TTS request in the
        // family goes through.
        assertEquals(1, countOf(src, "applyUserAgentOverride(customUserAgent)"))
        val execAt = src.indexOf("suspend fun executeRequest(request: Request): ByteArray")
        val applyAt = src.indexOf("applyUserAgentOverride(customUserAgent)")
        assertTrue("executeRequest declaration not found", execAt > 0)
        assertTrue("the override is applied outside executeRequest", applyAt > execAt)
    }

    @Test
    fun xunfei_tts_websocket_carries_the_override() {
        val src = readRepoFile(VOICE_VENDORS_REL)

        // Xunfei TTS is the one request that bypasses executeRequest
        // (httpClient.newWebSocket), so it needs its own application.
        val urlAt = src.indexOf(".url(signedUrl)")
        assertTrue("Xunfei WebSocket request not found", urlAt > 0)
        val buildAt = src.indexOf(".build()", urlAt)
        assertTrue("Xunfei WebSocket request statement unbalanced", buildAt > urlAt)
        assertTrue(
            "the Xunfei TTS WebSocket request drops the UA override",
            src.substring(urlAt, buildAt).contains(".applyUserAgentOverride("),
        )
    }

    @Test
    fun voice_factory_has_exactly_one_post_construction_ua_injection() {
        val src = readRepoFile(VOICE_FACTORY_REL)

        // Literal literal: one assignment covering every branch (including the
        // two early `return`s and the null arms) — the 1019-ISSUE-04 shape
        // (per-branch forwarding) is what silently drops new arms.
        assertEquals(1, countOf(src, "customUserAgent = instance.customUserAgent"))
        assertTrue(
            "the factory no longer wraps every arm in one injection",
            src.contains(".also { it?.customUserAgent = instance.customUserAgent }"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count += 1
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val VOICE_PROVIDER_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/voice/VoiceProvider.kt"
        const val VOICE_VENDORS_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/voice/VoiceProviderVendors.kt"
        const val VOICE_FACTORY_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/voice/VoiceProviderFactory.kt"
    }

    // ── memory tool gate ─────────────────────────────────────────────

    @Test
    fun gate_disabled_returns_the_rejection_message() {
        // Same wording memory_write answered with before the fix; rollup
        // must answer identically (同形).
        assertEquals(
            "Memory writes are disabled for this session (user toggled /memory off). Reads remain available.",
            memoryToolDisabledMessage(false),
        )
    }

    @Test
    fun gate_enabled_returns_null() {
        assertNull(memoryToolDisabledMessage(true))
    }

    @Test
    fun disabled_tool_title_is_shared() {
        assertEquals("Memory (disabled)", MEMORY_DISABLED_TOOL_TITLE)
    }

    // ── provider add: customUserAgent ────────────────────────────────

    @Test
    fun ua_writable_for_api_key_only() {
        assertTrue(customUserAgentWritable(ProviderCredential.apiKey))
        assertEquals(false, customUserAgentWritable(ProviderCredential.oauth))
    }

    @Test
    fun add_ua_payload_resolves_verbatim_for_api_key() {
        assertEquals("MyAgent/1.0", resolveAddCustomUserAgent("MyAgent/1.0", ProviderCredential.apiKey))
    }

    @Test
    fun add_ua_absent_or_blank_resolves_to_null() {
        assertNull(resolveAddCustomUserAgent(null, ProviderCredential.apiKey))
        assertNull(resolveAddCustomUserAgent("", ProviderCredential.apiKey))
        assertNull(resolveAddCustomUserAgent("   ", ProviderCredential.apiKey))
        // Absent on an OAuth create must keep working (no override, no throw).
        assertNull(resolveAddCustomUserAgent(null, ProviderCredential.oauth))
        assertNull(resolveAddCustomUserAgent("", ProviderCredential.oauth))
    }

    @Test
    fun add_ua_on_oauth_credential_throws_the_write_field_wording() {
        try {
            resolveAddCustomUserAgent("MyAgent/1.0", ProviderCredential.oauth)
            fail("expected ConfigError.InvalidValue")
        } catch (e: ConfigError.InvalidValue) {
            // Hardcoded literal = the exact wording the per-instance write
            // field threw before this fix (invalid_value: prefix added by
            // the ConfigError envelope).
            assertEquals(
                "invalid_value: Custom User-Agent is only supported for third-party API-key providers. " +
                    "OAuth providers (Anthropic/Codex) use their own authentication UA and cannot be overridden.",
                e.message,
            )
            assertEquals(
                "invalid_value: $CUSTOM_USER_AGENT_OAUTH_MESSAGE",
                e.message,
            )
        }
    }
}
