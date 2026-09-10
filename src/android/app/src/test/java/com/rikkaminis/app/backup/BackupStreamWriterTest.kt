package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * [T-backup-streaming-export] The streaming writer must emit documents that
 * parse IDENTICALLY to what a JSONObject-based assembly would produce —
 * an import must not be able to tell a streamed backup from a legacy one.
 *
 * Byte-identity strategy: build the same sections twice — once through the
 * JSONObject assembly (mirroring export()'s final step) and once through
 * BackupStreamWriter — then parse both and compare the full JSON trees.
 */
class BackupStreamWriterTest {

    private fun skeletonSections(
        chatSessions: List<JSONObject>,
        chatMessages: List<JSONObject>,
        chatTruncated: JSONObject?,
    ): Triple<JSONObject, List<JSONObject>, List<JSONObject>> {
        val skeleton = JSONObject()
            .put("format", "openminis.config.backup")
            .put("version", 1)
            .put("createdAt", 123_456L)
            .put("includesSecrets", true)
            .put("fields", JSONObject().put("appearance.theme", "\"dark\""))
            .put("providers", JSONArray().put(JSONObject().put("id", "p1")))
        return Triple(skeleton, chatSessions, chatMessages)
    }

    @Test
    fun `streamed document parses identically to object assembly`() {
        val sessions = listOf(
            JSONObject().put("id", "s1").put("title", "hello"),
            JSONObject().put("id", "s2").put("title", "world"),
        )
        val messages = listOf(
            JSONObject().put("id", "m1").put("partsJson", """[{"type":"text","value":"hi"}]"""),
            JSONObject().put("id", "m2").put("partsJson", """[{"type":"text","value":"yo"}]"""),
        )
        val truncated = JSONObject().put("sessionsDropped", 3).put("messagesDropped", 4)

        // Assembly A: full JSONObject document (what export() builds)
        val docA = JSONObject()
            .put("format", "openminis.config.backup")
            .put("version", 1)
            .put("createdAt", 123_456L)
            .put("includesSecrets", true)
            .put("fields", JSONObject().put("appearance.theme", "\"dark\""))
            .put("providers", JSONArray().put(JSONObject().put("id", "p1")))
            .put("chatSessions", JSONArray().apply { sessions.forEach { put(it) } })
            .put("chatMessages", JSONArray().apply { messages.forEach { put(it) } })
            .put("chatTruncated", truncated)

        // Assembly B: streamed through BackupStreamWriter
        val (skeleton, _, _) = skeletonSections(sessions, messages, truncated)
        val sw = StringWriter()
        BackupStreamWriter.writeObjectFrame(
            sw,
            listOf(
                "format", "version", "createdAt", "includesSecrets", "fields",
                "providers", "chatSessions", "chatMessages", "chatTruncated",
            ),
        ) { w, key ->
            when (key) {
                "chatSessions" -> BackupStreamWriter.writeJsonArray(w, sessions)
                "chatMessages" -> BackupStreamWriter.writeJsonArray(w, messages)
                "chatTruncated" -> w.write(truncated.toString())
                else -> w.write(skeleton.opt(key)?.toString() ?: "null")
            }
        }
        val docB = JSONObject(sw.toString())

        // Full-tree comparison: same keys, same values, same array contents.
        assertEquals(docA.keySet(), docB.keySet())
        for (key in docA.keySet()) {
            assertEquals("key $key must match", docA.get(key).toString(), docB.get(key).toString())
        }
    }

    @Test
    fun `top-level string values are emitted quoted, not as bare words`() {
        // [fix-stream-quoting] Regression pin. org.json's lenient parser
        // accepts {"format":bare.word,...}, so the parse-and-compare tests
        // above CANNOT catch a missing quote — the tree looks identical after
        // a lenient parse. Strict parsers (python json, jq,
        // kotlinx-serialization) reject the document, so a backup whose
        // "format" string is unquoted is a file only this app can read.
        // Pin the BYTES: a streamed top-level String must be JSON-quoted.
        val skeleton = JSONObject().put("format", "openminis.config.backup")
        val sw = StringWriter()
        // Same emission shape as ConfigBackup.exportToWriter's frame callback.
        BackupStreamWriter.writeObjectFrame(
            sw,
            listOf("format", "chatTruncated"),
        ) { w, key ->
            when (key) {
                "chatTruncated" -> w.write("null")
                else -> {
                    val v = skeleton.opt(key)
                    if (v is String) w.write(JSONObject.quote(v))
                    else w.write(v?.toString() ?: "null")
                }
            }
        }
        assertEquals(
            "{\"format\":\"openminis.config.backup\",\"chatTruncated\":null}",
            sw.toString(),
        )
    }

    @Test
    fun `null optional sections are written as json null`() {
        val sw = StringWriter()
        BackupStreamWriter.writeObjectFrame(
            sw,
            listOf("chatTruncated", "readFailures"),
        ) { w, key ->
            when (key) {
                "chatTruncated" -> w.write("null")
                "readFailures" -> w.write("null")
                else -> w.write("null")
            }
        }
        val doc = JSONObject(sw.toString())
        assertTrue(!doc.has("chatTruncated") || doc.isNull("chatTruncated"))
        assertTrue(!doc.has("readFailures") || doc.isNull("readFailures"))
    }

    @Test
    fun `empty arrays stream as json empty arrays`() {
        val sw = StringWriter()
        BackupStreamWriter.writeJsonArray(sw, emptyList())
        assertEquals("[]", sw.toString())
    }

    @Test
    fun `lazy array writes the same as materialized array`() {
        val elements = listOf(
            JSONObject().put("n", 1),
            JSONObject().put("n", 2),
            JSONObject().put("n", 3),
        )
        val materialized = StringWriter()
        BackupStreamWriter.writeJsonArray(materialized, elements)
        val lazy = StringWriter()
        BackupStreamWriter.writeJsonArrayLazy(lazy, elements.size) { w, i ->
            // writeJsonArrayLazy handles the separators; the emitter only
            // writes the element itself.
            w.write(elements[i].toString())
        }
        assertEquals(materialized.toString(), lazy.toString())
    }

    @Test
    fun `strings with quotes and newlines survive the frame round trip`() {
        // Message content is the tricky case: partsJson embeds escaped quotes
        // and newlines. element.toString() must re-escape them correctly.
        val tricky = JSONObject()
            .put("partsJson", """[{"type":"text","value":"line1\nline2 \"quoted\""}]""")
        val sw = StringWriter()
        BackupStreamWriter.writeJsonArray(sw, listOf(tricky))
        val reparsed = JSONArray(sw.toString()).getJSONObject(0)
        assertEquals(tricky.getString("partsJson"), reparsed.getString("partsJson"))
    }
}
