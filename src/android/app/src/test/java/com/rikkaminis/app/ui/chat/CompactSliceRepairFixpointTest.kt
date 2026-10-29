package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r5-c1-compact-C03] `repairSliceShape` is the fixpoint wrapper around the
 * two existing repairs; [normalizeSliceRoleAlternation]'s merge now carries the
 * multimodal carriers too.
 *
 * Both defects are silent off-device: the slice is what the provider receives,
 * so a leading orphan tool_result shows up as a 400 on the next request and a
 * merged-away attachment shows up as "the model forgot the picture".
 */
class CompactSliceRepairFixpointTest {

    private fun u(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    private fun a(text: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    private fun toolUse(id: String) = AgentContentPart.ToolUse(
        id = id,
        name = "read_file",
        input = JSONObject("{}"),
    )

    private fun toolResult(id: String) = AgentContentPart.ToolResult(
        id = id,
        name = "read_file",
        content = "ok-$id",
    )

    private fun resultOnly(id: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "",
        contentParts = listOf(toolResult(id)),
    )

    private fun roles(list: List<LLMMessage>) =
        list.joinToString(",") { it.role.name.first().toString() }

    private fun texts(m: LLMMessage) =
        m.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("|") { it.text }

    @Test
    fun `a peel that orphans a tool_result is repaired by the next pass`() {
        // Reported shape (R5-C C-03①): the slice starts on a round-1 tool_result
        // whose tool_use fell outside the slice, followed by a round-2 pair.
        //   pass 1 sanitize → drops the round-1 orphan → head is ASSISTANT
        //   pass 1 normalize → peels that ASSISTANT → the round-2 tool_result
        //                      is now an orphan at the head  ← the defect
        //   pass 2 sanitize → drops it → head is ASSISTANT again
        //   pass 2 normalize → peels → "u-next" is the head
        val slice = mutableListOf(
            resultOnly("r1"),
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = "",
                contentParts = listOf(toolUse("t2")),
            ),
            resultOnly("t2"),
            a("answer"),
            u("u-next"),
        )
        repairSliceShape(slice)
        assertEquals("the repaired slice must start on a USER turn", "U", roles(slice))
        assertEquals("only the surviving user turn is left", "u-next", texts(slice[0]))
        assertEquals(1, slice.size)
    }

    @Test
    fun `the fixpoint is idempotent`() {
        val build = {
            mutableListOf(
                resultOnly("r1"),
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = "",
                    contentParts = listOf(toolUse("t2")),
                ),
                resultOnly("t2"),
                a("answer"),
                u("u-next"),
            )
        }
        val once = build()
        repairSliceShape(once)
        val twice = build()
        repairSliceShape(twice)
        repairSliceShape(twice)
        assertEquals(roles(once), roles(twice))
        assertEquals(once.map { it.content }, twice.map { it.content })
    }

    @Test
    fun `a legal slice is not rewritten at all`() {
        val slice = mutableListOf(u("u1"), a("a1"), u("u2"), a("a2"))
        val before = slice.map { it.content }
        repairSliceShape(slice)
        assertEquals("a legal slice must come out byte-identical", before, slice.map { it.content })
    }

    @Test
    fun `merging same role neighbors keeps both sides' image and audio parts`() {
        // [fix/r5-c1-compact-C03②] imageParts/audioParts are their own fields,
        // not contentParts: `a.copy()` used to inherit a's and silently drop b's.
        val img1 = LLMMessage.ImagePart(data = byteArrayOf(1), mimeType = "image/png")
        val img2 = LLMMessage.ImagePart(data = byteArrayOf(2), mimeType = "image/jpeg")
        val aud1 = LLMMessage.AudioPart(format = "wav", base64Data = "AAA")
        val aud2 = LLMMessage.AudioPart(format = "mp3", base64Data = "BBB")
        val slice = mutableListOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "with image",
                imageParts = listOf(img1),
                audioParts = listOf(aud1),
            ),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "with audio",
                imageParts = listOf(img2),
                audioParts = listOf(aud2),
            ),
            a("a1"),
        )
        normalizeSliceRoleAlternation(slice)
        assertEquals("U,A", roles(slice))
        assertEquals("both images must reach the provider", listOf(img1, img2), slice[0].imageParts)
        assertEquals("both audio clips must reach the provider", listOf(aud1, aud2), slice[0].audioParts)
        assertEquals("with image\nwith audio", slice[0].content)
    }

    @Test
    fun `a merge does not invent attachments`() {
        val img = LLMMessage.ImagePart(data = byteArrayOf(9), mimeType = "image/png")
        val slice = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "plain"),
            LLMMessage(role = LLMMessage.Role.USER, content = "pic", imageParts = listOf(img)),
            a("a1"),
        )
        normalizeSliceRoleAlternation(slice)
        assertEquals(listOf(img), slice[0].imageParts)
        assertTrue(slice[0].audioParts.isEmpty())
    }
}
