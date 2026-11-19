package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fullscan-1119 F-12] `estimateMessageTokensFull` counted only `part.content`
 * for an `AgentContentPart.ToolResult`, ignoring `part.imageData`.
 *
 * A tool that returns an image (read_image / screenshot) therefore cost ~0
 * tokens: the local budget chain (preflight / auto-compact / trim) read
 * "context still fits" while the wire request was already over the window and
 * the provider answered 400. The `ImageData` part branch and the legacy
 * `imageParts` branch both counted the base64 upper bound — the tool-result
 * branch was the odd one out.
 *
 * Expected values are LITERALS read off the formula, never derived from the
 * estimator under test (JVM 装置自指陷阱, 2026-09-26):
 *   1 MiB bytes → base64 ceil(4N/3) chars → chars/4 tokens
 *   1 048 576 * 4 / 3 = 1 398 101 (integer floor), +3 / 4 = 349 526
 */
class ToolResultImageEstimateTest {

    private val oneMiB = 1_048_576
    /** Literal expectation for a 1 MiB image payload. */
    private val oneMiBImageTokens = 349_526L

    private fun toolResult(data: ByteArray?) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ToolResult(
                id = "toolu_1",
                name = "read_image",
                content = "",
                imageData = data,
                imageMimeType = "image/png",
            ),
        ),
    )

    private fun imagePart(data: ByteArray) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(
            AgentContentPart.ImageData(data = data, mimeType = "image/png"),
        ),
    )

    @Test
    fun `a tool result image is counted, not just its text`() {
        val plain = ContextEstimator.estimateMessageTokensFull(toolResult(null))
        val withImage = ContextEstimator.estimateMessageTokensFull(toolResult(ByteArray(oneMiB)))
        assertEquals(
            "a 1 MiB tool image must add the base64 upper bound exactly",
            oneMiBImageTokens,
            withImage - plain,
        )
        assertTrue("a tool image must not be free", withImage > plain)
    }

    @Test
    fun `tool image and ImageData part estimate identically`() {
        // "One formula, no second copy" — this file exists so the parts image
        // path and the tool-result image path cannot drift apart.
        assertEquals(
            "the same bytes must cost the same on both content-part paths",
            ContextEstimator.estimateMessageTokensFull(imagePart(ByteArray(oneMiB))),
            ContextEstimator.estimateMessageTokensFull(toolResult(ByteArray(oneMiB))),
        )
    }

    @Test
    fun `legacy imageParts path uses the same formula`() {
        val legacy = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            imageParts = listOf(LLMMessage.ImagePart(data = ByteArray(oneMiB), mimeType = "image/png")),
        )
        assertEquals(
            "the legacy imageParts branch must stay on the shared formula",
            oneMiBImageTokens,
            ContextEstimator.estimateMessageTokensFull(legacy),
        )
    }
}
