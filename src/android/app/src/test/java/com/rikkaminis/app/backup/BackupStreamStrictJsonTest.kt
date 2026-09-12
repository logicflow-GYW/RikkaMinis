package com.rikkaminis.app.backup

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * [T-backup-strict-json] Automated STRICT-parser gate for the streamed backup
 * document.
 *
 * Why this exists: lenient parsers (org.json, and even kotlinx
 * Json.parseToJsonElement, which parses bare-word values as unquoted
 * JsonLiterals) accept `{"format":openminis.config.backup,...}` — the tree
 * looks identical after a lenient parse, so parse-and-compare tests CANNOT
 * catch malformed emission. The 2026-09-10 fix added a byte-level pin for the
 * top-level String key; this test generalizes it: the WHOLE streamed frame
 * must parse under Jackson, which is strict at the same level as
 * `python json` / `jq` — the tools a user will actually open a backup file
 * with.
 *
 * Includes a NEGATIVE control: the pre-fix bare-word shape must be REJECTED
 * by the strict parser while the lenient path still accepts it — that
 * asymmetry is exactly why the byte-level gate alone was never enough. If a
 * future change makes the strict gate as lenient as org.json, the control
 * goes red instead of silently keeping a lenient gate.
 */
class BackupStreamStrictJsonTest {

    private val strict = ObjectMapper()

    /** Same emission shape as ConfigBackup.exportToWriter's frame callback. */
    private fun streamFrame(
        skeleton: JSONObject,
        bareWordFormat: Boolean = false,
    ): String {
        val sw = StringWriter()
        BackupStreamWriter.writeObjectFrame(
            sw,
            listOf(
                "format", "version", "createdAt", "includesSecrets", "fields",
                "providers", "chatSessions", "chatMessages", "chatTruncated",
            ),
        ) { w, key ->
            when (key) {
                "chatSessions" -> BackupStreamWriter.writeJsonArray(
                    w,
                    listOf(
                        JSONObject()
                            .put("id", "s1")
                            .put("title", "你好 \"引号\" \n换行\t制表"),
                    ),
                )
                "chatMessages" -> BackupStreamWriter.writeJsonArray(
                    w,
                    listOf(
                        JSONObject()
                            .put("id", "m1")
                            .put("partsJson", """[{"type":"text","value":"hi"}]"""),
                    ),
                )
                "chatTruncated" -> w.write("null")
                else -> {
                    val v = skeleton.opt(key)
                    when {
                        v == null -> w.write("null")
                        // Negative control: replicate the [fix-stream-quoting] bug —
                        // org.json's String.toString() emits a BARE word.
                        v is String && bareWordFormat -> w.write(v)
                        v is String -> w.write(JSONObject.quote(v))
                        else -> w.write(v.toString())
                    }
                }
            }
        }
        return sw.toString()
    }

    private fun skeleton(): JSONObject = JSONObject()
        .put("format", "openminis.config.backup")
        .put("version", 1)
        .put("createdAt", 123_456L)
        .put("includesSecrets", true)
        .put(
            "fields",
            JSONObject()
                .put("appearance.theme", "\"dark\"")   // value that itself is a quoted string
                .put("chat.returnKey", true)
                .put("runtime.maxConcurrent", 3)
                .put("chat.name", "中转站 \u26A1"),
        )
        .put(
            "providers",
            JSONArray().put(
                JSONObject()
                    .put("id", "p1")
                    .put("baseUrl", "https://relay.example/v1")
                    .put("nested", JSONObject().put("models", JSONArray().put("m1"))),
            ),
        )

    @Test
    fun `streamed document parses under a strict parser`() {
        val doc = streamFrame(skeleton())
        // Jackson is strict at python-json / jq level: unquoted values,
        // trailing commas, illegal escapes all throw.
        val root = strict.readTree(doc)
        assertEquals("openminis.config.backup", root.get("format").asText())
        assertEquals(1, root.get("version").asInt())
        assertTrue(root.get("includesSecrets").asBoolean())
        assertEquals(1, root.get("providers").size())
        assertEquals("https://relay.example/v1", root.get("providers").get(0).get("baseUrl").asText())
        assertEquals("m1", root.get("providers").get(0).get("nested").get("models").get(0).asText())
    }

    @Test
    fun `strict parser rejects the pre-fix bare-word shape`() {
        val doc = streamFrame(JSONObject().put("format", "openminis.config.backup"), bareWordFormat = true)
        assertThrows(
            "bare-word top-level string must be rejected by a strict parser",
            JsonParseException::class.java,
        ) { strict.readTree(doc) }
        // And the lenient path must still accept it — that asymmetry is exactly
        // why parse-and-compare tests alone were never enough.
        assertTrue(JSONObject(doc).has("format"))
    }

    @Test
    fun `escaped quotes newlines tabs and cjk survive the streamed frame`() {
        val doc = streamFrame(skeleton())
        val root = strict.readTree(doc)
        val title = root.get("chatSessions").get(0).get("title").asText()
        assertEquals("你好 \"引号\" \n换行\t制表", title)
        val theme = root.get("fields").get("appearance.theme").asText()
        assertEquals("\"dark\"", theme)
    }

    @Test
    fun `numbers booleans and nulls survive strict parsing`() {
        val doc = streamFrame(skeleton())
        val root = strict.readTree(doc)
        assertEquals(123_456L, root.get("createdAt").asLong())
        assertTrue("chatTruncated must be JSON null", root.get("chatTruncated").isNull)
        assertNotNull(root.get("chatMessages"))
    }
}
