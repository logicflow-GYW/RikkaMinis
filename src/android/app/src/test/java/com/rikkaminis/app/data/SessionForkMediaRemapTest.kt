package com.rikkaminis.app.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the [rv1014-D1] parts_json media-reference remap:
 * [remapForkedPartsJson]. Without the remap, a duplicated session's media
 * references keep pointing at the SOURCE session's files — shared while the
 * source exists, dangling "File not found" once it is deleted — and the media
 * tree that copySessionArtifacts copies to the new session is never
 * referenced (pure dead weight).
 *
 * The expected reference shapes are taken from the real producers:
 *  - user mediaRef part: `{"type":"mediaRef","value":{"id",…,"relativePath":
 *    "yyyy/MM/dd/<sid>/<id>.<ext>","linuxPath":"/var/minis/attachments/uploads/…"}}`
 *    (ChatMessageJson.buildMediaRefPartJson / MediaStore.saveMedia);
 *  - tool result part: `{"type":"toolResult","value":{…,"mediaRef":{…}}}`.
 */
class SessionForkMediaRemapTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val srcSid = "11111111-2222-3333-4444-555555555555"
    private val newSid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val mediaId = "6f1c2b3d-4e5f-4a60-9b71-0c8d9e0f1a2b"

    private fun userMediaRefPartsJson(): String {
        val ref = """{"type":"mediaRef","value":{"id":"$mediaId",""" +
            """"relativePath":"2026/10/14/$srcSid/$mediaId.jpg",""" +
            """"mimeType":"image/jpeg","linuxPath":"/var/minis/attachments/uploads/photo.jpg"}}"""
        val text = """{"type":"text","value":"look at this"}"""
        return "[$text,$ref]"
    }

    private fun toolResultMediaRefPartsJson(): String {
        val part = """{"type":"toolResult","value":{"toolUseId":"t1","output":"saved",""" +
            """"success":true,"mediaRef":{"id":"$mediaId",""" +
            """"relativePath":"2026/10/14/$srcSid/$mediaId.png","mimeType":"image/png"}}}"""
        return "[$part]"
    }

    @Test
    fun privateReplayIsRemovedBeforeForkMediaAndTextReplacement() {
        val public = JSONArray(userMediaRefPartsJson())
        public.put(org.json.JSONObject().put("type", "anthropicReplayV1").put("value",
            org.json.JSONObject().put("signature", "SIG_SYNTH_D05-$srcSid").put("data", "DATA_SYNTH_D05-$srcSid")))
        public.put(org.json.JSONObject().put("type", "text").put("value", "source=$srcSid"))
        val out = remapForkedPartsJson(public.toString(), srcSid, newSid)
        assertFalse(out.contains("SIG_SYNTH_D05"))
        assertFalse(out.contains("DATA_SYNTH_D05"))
        val parts = JSONArray(out)
        assertEquals(3, parts.length())
        assertEquals("2026/10/14/$newSid/$mediaId.jpg", parts.getJSONObject(1).getJSONObject("value").getString("relativePath"))
        assertEquals("source=$newSid", parts.getJSONObject(2).getString("value"))
    }

    @Test
    fun forkKeepsOrdinaryUnknownAndRejectsMalformedPrivateRow() {
        val unknown = """[{"type":"futurePart","value":"anthropicReplayV1"}]"""
        assertEquals(unknown, remapForkedPartsJson(unknown, srcSid, newSid))
        val bad = """[{"type":"anthropicReplayV1","value":{"data":"DATA_SYNTH_D05"}"""
        assertEquals("[]", remapForkedPartsJson(bad, srcSid, newSid))
        assertEquals("legacy $newSid [", remapForkedPartsJson("legacy $srcSid [", srcSid, newSid))
    }

    @Test
    fun `user mediaRef relativePath is remapped to the new session`() {
        val out = remapForkedPartsJson(userMediaRefPartsJson(), srcSid, newSid)

        assertFalse("source session id must be gone", out.contains(srcSid))
        val ref = JSONArray(out).getJSONObject(1).getJSONObject("value")
        assertEquals(
            "2026/10/14/$newSid/$mediaId.jpg",
            ref.getString("relativePath"),
        )
        // Sandbox paths without a session id are untouched.
        assertEquals("/var/minis/attachments/uploads/photo.jpg", ref.getString("linuxPath"))
    }

    @Test
    fun `nested toolResult mediaRef is remapped`() {
        val out = remapForkedPartsJson(toolResultMediaRefPartsJson(), srcSid, newSid)

        assertFalse(out.contains(srcSid))
        val mediaRef = JSONArray(out).getJSONObject(0).getJSONObject("value").getJSONObject("mediaRef")
        assertEquals("2026/10/14/$newSid/$mediaId.png", mediaRef.getString("relativePath"))
        assertEquals("image/png", mediaRef.getString("mimeType"))
    }

    @Test
    fun `multiple references in one message are all remapped`() {
        val parts = "[" +
            """{"type":"mediaRef","value":{"id":"m1","relativePath":"2026/01/02/$srcSid/a.jpg","mimeType":"image/jpeg"}},""" +
            """{"type":"toolResult","value":{"toolUseId":"t","output":"o","success":true,"mediaRef":{"id":"m2","relativePath":"2026/03/04/$srcSid/b.png","mimeType":"image/png"}}},""" +
            """{"type":"text","value":"see /var/minis/workspace/notes.md"}]"""

        val out = remapForkedPartsJson(parts, srcSid, newSid)

        assertFalse(out.contains(srcSid))
        val arr = JSONArray(out)
        assertEquals("2026/01/02/$newSid/a.jpg", arr.getJSONObject(0).getJSONObject("value").getString("relativePath"))
        assertEquals(
            "2026/03/04/$newSid/b.png",
            arr.getJSONObject(1).getJSONObject("value").getJSONObject("mediaRef").getString("relativePath"),
        )
        assertEquals("see /var/minis/workspace/notes.md", arr.getJSONObject(2).getString("value"))
    }

    @Test
    fun `partsJson without the source session id is returned unchanged`() {
        val parts = """[{"type":"text","value":"no media here"}]"""
        assertEquals(parts, remapForkedPartsJson(parts, srcSid, newSid))
    }

    @Test
    fun `output stays valid json`() {
        val out = remapForkedPartsJson(userMediaRefPartsJson(), srcSid, newSid)
        // JSONArray(...) throws on invalid JSON — the remap must never
        // corrupt the syntax (UUID characters include no JSON structure).
        assertEquals(2, JSONArray(out).length())
    }

    @Test
    fun `remapped relativePath resolves to the copied media file`() {
        // Simulate what copySessionArtifacts lays out: media/<yyyy>/<MM>/<dd>/<newSid>/
        val mediaBase = tmp.newFolder("media")
        val copiedDir = File(File(File(mediaBase, "2026"), "10"), "14/$newSid")
        copiedDir.mkdirs()
        File(copiedDir, "$mediaId.jpg").writeText("bytes")

        val out = remapForkedPartsJson(userMediaRefPartsJson(), srcSid, newSid)
        val relativePath = JSONArray(out).getJSONObject(1).getJSONObject("value").getString("relativePath")

        // The reference MediaStore.loadMedia resolves (File(mediaBaseDir, relativePath)).
        assertTrue(File(mediaBase, relativePath).exists())
    }

    @Test
    fun `verbatim copy would keep pointing at the source session`() {
        // The negative shape (pre-fix behaviour): verbatim parts_json resolves
        // into the SOURCE session's media dir, not the copy.
        val relativePath = JSONArray(userMediaRefPartsJson()).getJSONObject(1)
            .getJSONObject("value").getString("relativePath")
        assertTrue(relativePath.contains(srcSid))
        assertFalse(File(tmp.root, relativePath.replace(srcSid, newSid)).exists())
    }
}
