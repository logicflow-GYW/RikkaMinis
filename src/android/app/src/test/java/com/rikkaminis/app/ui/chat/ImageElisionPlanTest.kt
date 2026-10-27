package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [W-1027 W-05] The elision rewrite that `applyRequestImageBudget` used to
 * inline, pinned on the real production function.
 *
 * The old arms in `ChatImageRestoreTest` exercised `filterElidedImageParts`
 * with a hand-built id set — they proved the filter, not the collection of the
 * set. Flipping `if (replaced !== original)` to `===` therefore kept every test
 * green while `imageParts` silently stopped being filtered (all three providers
 * re-send `+ msg.imageParts`) — the 413 shape `b9f6df75f` had removed.
 *
 * Every expectation below is a literal: nothing is derived from the
 * production constants, so a mutation cannot move the goalposts with it.
 */
class ImageElisionPlanTest {

    private fun placeholder(path: String?): String = "ELIDED[$path]"

    private fun userMsg(
        parts: List<AgentContentPart>,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
    ) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        imageParts = imageParts,
        contentParts = parts,
    )

    private fun hash(bytes: ByteArray): Int = System.identityHashCode(bytes)

    /** Arm 1 — the elided part loses its bytes in BOTH carriers and is recorded. */
    @Test
    fun `an elided image is replaced in contentParts and recorded against its message`() {
        val big = ByteArray(16) { 7 }
        val msg = userMsg(
            listOf(AgentContentPart.ImageData(big, "image/jpeg", linuxPath = "/var/minis/a.jpg")),
            imageParts = listOf(LLMMessage.ImagePart(big, "image/jpeg", linuxPath = "/var/minis/a.jpg")),
        )

        val out = planImageElision(
            messages = listOf(msg),
            droppedIds = setOf(hash(big)),
            placeholder = ::placeholder,
            pathFor = { "/var/minis/a.jpg" },
        )

        assertEquals(1, out.messages.size)
        assertEquals(setOf(hash(big)), out.droppedIdsByMessage[0])
        // contentParts: bytes gone, placeholder carries the re-fetch address.
        val text = out.messages[0].contentParts.single()
        assertEquals(AgentContentPart.Text("ELIDED[/var/minis/a.jpg]"), text)
        // imageParts: the second carrier every provider appends to.
        assertEquals(0, out.messages[0].imageParts.size)
        assertTrue(out.mutated)
    }

    /**
     * Arm 2 — the anti-over-drop needle: a part that was NOT budgeted keeps its
     * id out of the recorded set and keeps its bytes + instance.
     */
    @Test
    fun `a part under budget is untouched and never recorded as dropped`() {
        val small = ByteArray(4) { 1 }
        val smallImagePart = LLMMessage.ImagePart(small, "image/png")
        val msg = userMsg(
            listOf(AgentContentPart.ImageData(small, "image/png")),
            imageParts = listOf(smallImagePart),
        )
        // A different, dropped image exists elsewhere in the request — this is
        // what makes the arm meaningful: the plan is non-empty, yet THIS part
        // must not be rewritten nor recorded.
        val other = ByteArray(8) { 2 }
        val otherMsg = userMsg(listOf(AgentContentPart.ImageData(other, "image/jpeg")))

        val out = planImageElision(
            messages = listOf(msg, otherMsg),
            droppedIds = setOf(hash(other)),
            placeholder = ::placeholder,
            pathFor = { null },
        )

        assertEquals(emptySet<Int>(), out.droppedIdsByMessage[0])
        assertEquals(setOf(hash(other)), out.droppedIdsByMessage[1])
        val keptPart = out.messages[0].contentParts.single()
        assertSame(small, (keptPart as AgentContentPart.ImageData).data)
        assertSame(smallImagePart, out.messages[0].imageParts.single())
        // The untouched message is handed back as the SAME instance.
        assertSame(msg, out.messages[0])
    }

    /** Arm 3 — per-message decision: byte-equal copies are different images. */
    @Test
    fun `byte-equal arrays on two messages are decided per message`() {
        val first = ByteArray(6) { 3 }
        val second = ByteArray(6) { 3 }  // equal content, different instance
        val msgA = userMsg(
            listOf(AgentContentPart.ImageData(first, "image/jpeg")),
            imageParts = listOf(LLMMessage.ImagePart(first, "image/jpeg")),
        )
        val msgB = userMsg(
            listOf(AgentContentPart.ImageData(second, "image/jpeg")),
            imageParts = listOf(LLMMessage.ImagePart(second, "image/jpeg")),
        )

        val out = planImageElision(
            messages = listOf(msgA, msgB),
            droppedIds = setOf(hash(first)),
            placeholder = ::placeholder,
            pathFor = { null },
        )

        assertEquals(setOf(hash(first)), out.droppedIdsByMessage[0])
        assertEquals(emptySet<Int>(), out.droppedIdsByMessage[1])
        assertEquals(1, out.messages[1].imageParts.size)
        assertSame(second, out.messages[1].imageParts[0].data)
    }

    /** Arm 4 — no drops: same list instance, empty per-message sets. */
    @Test
    fun `an empty drop set returns the input list untouched`() {
        val msg = userMsg(
            listOf(AgentContentPart.ImageData(ByteArray(2), "image/jpeg")),
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(2), "image/jpeg")),
        )
        val messages = listOf(msg)

        val out = planImageElision(
            messages = messages,
            droppedIds = emptySet(),
            placeholder = ::placeholder,
            pathFor = { "never-asked" },
        )

        assertSame(messages, out.messages)
        assertEquals(emptyList<Set<Int>>(), out.droppedIdsByMessage)
        assertEquals(false, out.mutated)
    }

    /** Arm 5 — a tool-result screenshot: bytes stripped, structure + content kept. */
    @Test
    fun `a tool result keeps its shape while its image bytes are elided`() {
        val shot = ByteArray(32) { 9 }
        val msg = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(
                AgentContentPart.ToolResult(
                    id = "t1", name = "screenshot", content = "shot taken",
                    imageData = shot, imageMimeType = "image/png",
                )
            ),
        )

        val out = planImageElision(
            messages = listOf(msg),
            droppedIds = setOf(hash(shot)),
            placeholder = ::placeholder,
            pathFor = { "/var/minis/shots/1.png" },
        )

        assertEquals(setOf(hash(shot)), out.droppedIdsByMessage[0])
        val part = out.messages[0].contentParts.single() as AgentContentPart.ToolResult
        assertEquals("t1", part.id)
        assertEquals("screenshot", part.name)
        assertNull(part.imageData)
        assertNull(part.imageMimeType)
        assertEquals("shot taken\nELIDED[/var/minis/shots/1.png]", part.content)
    }

    /**
     * Arm 6 — the recorded id is the one production's `ImagePartId.of` computes,
     * and the placeholder receives the caller-resolved path (not the raw part's).
     */
    @Test
    fun `the recorded identity matches the byte-identity key and carries the resolved path`() {
        val bytes = ByteArray(5) { 4 }
        var seenPath = "unset"
        val msg = userMsg(
            listOf(AgentContentPart.ImageData(bytes, "image/jpeg", linuxPath = "/stale/path.jpg")),
            imageParts = listOf(LLMMessage.ImagePart(bytes, "image/jpeg")),
        )

        val out = planImageElision(
            messages = listOf(msg),
            droppedIds = setOf(hash(bytes)),
            placeholder = { p -> seenPath = p ?: "null"; placeholder(p) },
            pathFor = { "/spillover/fresh.jpg" },
        )

        assertEquals("/spillover/fresh.jpg", seenPath)
        assertEquals("ELIDED[/spillover/fresh.jpg]", (out.messages[0].contentParts.single() as AgentContentPart.Text).text)
        assertEquals(setOf(System.identityHashCode(bytes)), out.droppedIdsByMessage[0])
        assertEquals(0, out.messages[0].imageParts.size)
    }
}
