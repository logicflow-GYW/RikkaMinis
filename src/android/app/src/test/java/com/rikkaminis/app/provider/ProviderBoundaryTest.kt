package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TF-E] Runtime process-domain guard tests.
 *
 * The guard's decision logic is pure ([ProviderBoundary.enforce]) so it can be
 * exercised on the JVM without an Android runtime. We also verify the wiring:
 * a provider's network entry point (sendMessage / streamMessage) refuses to
 * run when the process context is not :modelservice.
 *
 * Note: under JVM unit tests `Application.getProcessName()` returns null
 * (unitTests.isReturnDefaultValues = true), so `currentProcessName()` is null
 * unless [ProviderBoundary.overrideProcessName] is set — enforcement then
 * passes through, which is the intended test escape hatch.
 */
class ProviderBoundaryTest {

    @Test
    fun `modelservice process is sanctioned`() {
        val pn = ProviderBoundary.enforce("com.rikkaminis.app:modelservice")
        assertEquals("com.rikkaminis.app:modelservice", pn)
    }

    @Test
    fun `any lab modelservice variant is sanctioned`() {
        // dual-appid: lab package runs as com.rikkaminis.app.lab:modelservice
        val pn = ProviderBoundary.enforce("com.rikkaminis.app.lab:modelservice")
        assertEquals("com.rikkaminis.app.lab:modelservice", pn)
    }

    @Test
    fun `null process name passes through`() {
        // JVM unit-test context (no Android runtime) → static guard owns it.
        assertNull(ProviderBoundary.enforce(null))
    }

    @Test
    fun `main app process is refused`() {
        val ex = assertThrows(ProviderBoundaryViolation.IllegalProcess::class.java) {
            ProviderBoundary.enforce("com.rikkaminis.app")
        }
        assertTrue(ex.message!!.contains(":modelservice"))
    }

    @Test
    fun `toolservice process is refused`() {
        assertThrows(ProviderBoundaryViolation::class.java) {
            ProviderBoundary.enforce("com.rikkaminis.app:toolservice")
        }
    }

    @Test
    fun `browserservice process is refused`() {
        assertThrows(ProviderBoundaryViolation::class.java) {
            ProviderBoundary.enforce("com.rikkaminis.app:browserservice")
        }
    }

    @Test
    fun `worker only needs suffix match (any applicationId)`() {
        // The rule keyed on the :modelservice *suffix* so a future dual-appid
        // variant can never be refused. Verify against an out-of-family name too.
        assertThrows(ProviderBoundaryViolation::class.java) {
            ProviderBoundary.enforce("some.other.app:modelservicex") // NOT a suffix match
        }
        assertEquals(
            "com.rikkaminis.app:modelservice",
            ProviderBoundary.enforce("com.rikkaminis.app:modelservice"),
        )
    }

    @Test
    fun `provider sendMessage refuses in a non-worker process`() = kotlinx.coroutines.test.runTest {
        val provider = FakeProvider()
        // Simulate a live main-process context (non-null, non-worker).
        val saved = ProviderBoundary.overrideProcessName
        try {
            ProviderBoundary.overrideProcessName = "com.rikkaminis.app"
            var threw = false
            var err: ProviderBoundaryViolation? = null
            try {
                provider.sendMessage(messages = emptyList(), systemPrompt = null, maxTokens = 16)
            } catch (e: ProviderBoundaryViolation) {
                threw = true
                err = e
            }
            assertTrue("expected ProviderBoundaryViolation, got none", threw)
            assertTrue(err!!.message!!.contains(":modelservice"))
        } finally {
            ProviderBoundary.overrideProcessName = saved
        }
    }

    @Test
    fun `provider sendMessage passes in modelservice process`() = kotlinx.coroutines.test.runTest {
        val provider = FakeProvider()
        val saved = ProviderBoundary.overrideProcessName
        try {
            ProviderBoundary.overrideProcessName = "com.rikkaminis.app:modelservice"
            val resp = provider.sendMessage(messages = emptyList(), systemPrompt = null, maxTokens = 16)
            assertEquals("ok", resp.text)
        } finally {
            ProviderBoundary.overrideProcessName = saved
        }
    }
}

/** Minimal provider whose network entry points are the default interface ones. */
private class FakeProvider : LLMProvider {
    override val name: String = "fake"
    override var model = com.rikkaminis.app.data.model.LLMModel(
        id = "fake-model",
        displayName = "fake",
        provider = "fake",
        contextWindow = 8192,
    )
    override var instanceContext: com.rikkaminis.app.data.model.ProviderInstance? = null

    override suspend fun sendMessageClamped(
        messages: List<com.rikkaminis.app.data.model.LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<com.rikkaminis.app.data.model.LLMMessage.ImagePart>,
        tools: List<com.rikkaminis.app.data.model.AgentToolDefinition>,
        thinkingLevel: com.rikkaminis.app.data.model.ThinkingLevel,
    ): com.rikkaminis.app.data.model.LLMResponse =
        com.rikkaminis.app.data.model.LLMResponse(text = "ok", stopReason = null, usage = null)

    override fun streamMessageClamped(
        messages: List<com.rikkaminis.app.data.model.LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<com.rikkaminis.app.data.model.LLMMessage.ImagePart>,
        tools: List<com.rikkaminis.app.data.model.AgentToolDefinition>,
        thinkingLevel: com.rikkaminis.app.data.model.ThinkingLevel,
    ) = kotlinx.coroutines.flow.flowOf(com.rikkaminis.app.data.model.LLMStreamChunk.Finished(stopReason = "stop"))
}