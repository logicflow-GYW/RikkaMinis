package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1045 A-03] Boundary table for the shared "does this message
 * still carry anything worth putting on the wire?" predicate.
 *
 * The defect this pins: the three provider serializers each dropped messages
 * the orphan-tool-pairing sanitizer had emptied, using the predicate
 * `contentParts.isNotEmpty() || content.isNotEmpty()` — which answers "is
 * there TEXT?". A `minis-model-use` turn carrying only an image block or
 * only an audio block has no text, so the filter deleted the whole user turn
 * and the model never saw the bytes.
 *
 * Expected values are hand-written literals (never derived from the code
 * under test — see evidence-discipline).
 */
class MessagePayloadFilterTest {

    private fun user(
        content: String = "",
        contentParts: List<AgentContentPart> = emptyList(),
        audioParts: List<LLMMessage.AudioPart> = emptyList(),
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
    ) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = content,
        imageParts = imageParts,
        audioParts = audioParts,
        contentParts = contentParts,
    )

    @Test
    fun `a message with text is kept`() {
        assertTrue(user(content = "hello").hasWirePayload(false, 0))
    }

    @Test
    fun `a message with structured content parts is kept`() {
        val parts = listOf(AgentContentPart.Text("hi"))
        assertTrue(user(contentParts = parts).hasWirePayload(false, 0))
    }

    @Test
    fun `an audio-only message is kept`() {
        val audio = listOf(LLMMessage.AudioPart(format = "wav", base64Data = "AAA"))
        assertTrue(user(audioParts = audio).hasWirePayload(false, 0))
    }

    @Test
    fun `a message with per-message image parts is kept`() {
        val images = listOf(LLMMessage.ImagePart(data = byteArrayOf(1, 2), mimeType = "image/png"))
        assertTrue(user(imageParts = images).hasWirePayload(false, 0))
    }

    @Test
    fun `the last user turn is kept when the request carries turn images`() {
        // The providers attach the request-level image list to the last user
        // turn, so that turn must survive the filter for the bytes to land.
        assertTrue(user().hasWirePayload(isLastUserTurn = true, turnImageCount = 1))
    }

    @Test
    fun `a non-last user turn is dropped even when the request carries images`() {
        assertFalse(user().hasWirePayload(isLastUserTurn = false, turnImageCount = 1))
    }

    @Test
    fun `a truly empty message is dropped`() {
        assertFalse(user().hasWirePayload(false, 0))
    }

    // -- [r3-b08 F-09] "has parts" is not "has payload" ----------------------

    @Test
    fun `a message whose only part is empty text is dropped`() {
        // This is the persisted empty assistant bubble
        // (AgentLoopState.TerminalErrorPlacement writes exactly listOf(Text(""))).
        // Passing it on the wire emits {"type":"text","text":""}, which the
        // Anthropic Messages API rejects with a 400 on every later turn.
        val parts = listOf<AgentContentPart>(AgentContentPart.Text(""))
        assertFalse(user(contentParts = parts).hasWirePayload(false, 0))
    }

    @Test
    fun `empty text next to a tool use still counts as payload`() {
        val parts = listOf<AgentContentPart>(
            AgentContentPart.Text(""),
            AgentContentPart.ToolUse(id = "t1", name = "read_file", input = org.json.JSONObject()),
        )
        assertTrue(user(contentParts = parts).hasWirePayload(false, 0))
    }

    @Test
    fun `empty text next to an image part still counts as payload`() {
        val parts = listOf<AgentContentPart>(
            AgentContentPart.Text(""),
            AgentContentPart.ImageData(data = byteArrayOf(1, 2), mimeType = "image/png"),
        )
        assertTrue(user(contentParts = parts).hasWirePayload(false, 0))
    }

    @Test
    fun `a blank-but-not-empty text part still counts`() {
        // Deliberate: only a literally empty string is rejected by the API; a
        // single space is a valid block and some prompts rely on it.
        assertTrue(user(contentParts = listOf(AgentContentPart.Text(" "))).hasWirePayload(false, 0))
    }
}
