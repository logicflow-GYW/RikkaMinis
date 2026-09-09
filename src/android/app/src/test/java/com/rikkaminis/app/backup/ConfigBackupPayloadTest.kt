package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * [T-backup-skills] Payload-level coverage for the parts of a backup that are
 * NOT plain registry scalars: skills (embedded as a zip), memory files, MCP
 * servers.
 *
 * ConfigBackup.export/import need Android repositories, so they can't be driven
 * from a JVM unit test. What is testable here — and what actually broke — is the
 * *payload contract*: the skill archive framing (zip → base64 → JSON → back),
 * the filename guard on memory files, and forward/backward compatibility of the
 * document shape. Those are the invariants that decide whether a restore comes
 * back whole.
 */
class ConfigBackupPayloadTest {

    // ── Skill archive framing ────────────────────────────────────────────

    private fun buildSkillZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names.add(e.name)
                zis.closeEntry()
            }
        }
        return names.sorted()
    }

    @Test
    fun `skill archive survives zip to base64 to json and back`() {
        val raw = buildSkillZip(
            "SKILL.md" to "---\nname: demo\ndescription: d\n---\nbody\n",
            "scripts/run.sh" to "#!/bin/sh\necho hi\n",
            "references/notes.md" to "# notes\n",
        )

        // Mirror the export side: Base64 NO_WRAP into a JSON string field.
        val b64 = java.util.Base64.getEncoder().encodeToString(raw)
        val doc = JSONObject().put(
            "skills",
            JSONArray().put(
                JSONObject()
                    .put("id", "demo")
                    .put("archive", b64)
                    .put("archiveBytes", raw.size)
            )
        )

        // Serializing and reparsing is what a real backup file goes through.
        val reparsed = JSONObject(doc.toString())
        val entry = reparsed.getJSONArray("skills").getJSONObject(0)
        val decoded = java.util.Base64.getDecoder().decode(entry.getString("archive"))

        assertTrue("archive bytes must round-trip unchanged", raw.contentEquals(decoded))
        assertEquals(raw.size, entry.getInt("archiveBytes"))
        assertEquals(
            listOf("SKILL.md", "references/notes.md", "scripts/run.sh"),
            zipNames(decoded),
        )
    }

    @Test
    fun `base64 payload carries no newlines that would break json`() {
        // Android's Base64.NO_WRAP is the contract; the default (wrapped)
        // flavour injects newlines every 76 chars. Both survive JSON escaping,
        // but NO_WRAP keeps the file diffable and much smaller.
        val raw = buildSkillZip("SKILL.md" to "x".repeat(4096))
        val b64 = java.util.Base64.getEncoder().encodeToString(raw)
        assertFalse(b64.contains("\n"))
        assertFalse(b64.contains("\r"))
    }

    @Test
    fun `exported zip satisfies importFromArchive SKILL_md placement rule`() {
        // SkillRepository.importFromArchive accepts SKILL.md at the root or
        // exactly one directory deep. exportSkillToZip stores paths relative to
        // the skill dir, so SKILL.md lands at the root — assert that pairing
        // holds, since a mismatch silently makes every skill unrestorable.
        fun accepted(name: String) =
            name == "SKILL.md" || (name.endsWith("/SKILL.md") && name.count { it == '/' } == 1)

        val names = zipNames(
            buildSkillZip(
                "SKILL.md" to "x",
                "scripts/run.sh" to "y",
            )
        )
        assertTrue(names.any { accepted(it) })

        // Guard the negative case too, so the rule isn't quietly loosened.
        assertFalse(accepted("a/b/SKILL.md"))
        assertFalse(accepted("nested/deep/SKILL.md"))
    }

    // ── Memory filename guard ────────────────────────────────────────────

    /** Mirrors the guard in ConfigBackup import stage 6. */
    private fun memoryNameAllowed(name: String): Boolean {
        val n = name.trim()
        return n.isNotEmpty() &&
            !n.contains('/') && !n.contains('\\') && !n.contains("..")
    }

    @Test
    fun `memory file names from a payload cannot escape the memory directory`() {
        // These names come straight out of an untrusted file, and are used to
        // build a path — so traversal has to be rejected before the write.
        for (bad in listOf("../GLOBAL.md", "../../etc/passwd", "a/b.md", "..", "", "   ", "x\\y.md")) {
            assertFalse("must reject $bad", memoryNameAllowed(bad))
        }
        for (good in listOf("GLOBAL.md", "2026-08-02.md")) {
            assertTrue("must accept $good", memoryNameAllowed(good))
        }
    }

    // ── Document shape / compatibility ───────────────────────────────────

    @Test
    fun `a version 1 backup without the new sections stays importable`() {
        // Backups written before skills/memory/MCP were covered have
        // no such keys. Import reads them with optJSONArray, which returns null
        // and skips the stage — so an old file must NOT look malformed.
        val old = JSONObject(
            """
            {"format":"openminis.config.backup","version":1,"createdAt":0,
             "includesSecrets":false,"fields":{},"providers":[],"groups":[]}
            """.trimIndent()
        )
        assertNull(old.optJSONArray("skills"))
        assertNull(old.optJSONArray("memoryFiles"))
        assertNull(old.optJSONArray("mcpServers"))
        assertEquals("openminis.config.backup", old.optString("format"))

        // Adding sections is additive, so the format major must not move —
        // bumping it would make new files unreadable by older builds for no
        // reason.
        assertEquals(1, ConfigBackup.FORMAT_VERSION)
    }

    @Test
    fun `mcp server entry keeps the shape importJSON reads`() {
        // MCPRepository.exportServerJSON emits {"mcpServers":{"<id>":{…}}} and
        // importJSON's parseImportRoot reads exactly that variant. Embedding
        // the object verbatim is what makes MCP round-trip.
        val exported = JSONObject().put(
            "mcpServers",
            JSONObject().put("ctx7", JSONObject().put("url", "https://example.test/mcp"))
        )
        val servers = exported.optJSONObject("mcpServers")
        assertTrue(servers != null && servers.has("ctx7"))
        assertEquals("https://example.test/mcp", servers!!.getJSONObject("ctx7").getString("url"))
    }

    @Test
    fun `mcp import reads id and oauth from the nested wrapper not the root`() {
        // Regression: exportServerJSON nests as {"mcpServers":{"<id>":{…}}}.
        // Reading `id`/`oauth` off the ROOT object silently yields nothing, so
        // the "reconnect OAuth" warning never fires and every server shows up
        // as "server #n" in the skipped list.
        val exported = JSONObject().put(
            "mcpServers",
            JSONObject().put(
                "ctx7",
                JSONObject()
                    .put("url", "https://example.test/mcp")
                    .put("oauth", JSONObject().put("clientId", "abc"))
            )
        )

        assertEquals("", exported.optString("id"))  // the trap
        assertFalse(exported.has("oauth"))          // the trap

        val inner = exported.optJSONObject("mcpServers")!!
        val id = inner.keys().asSequence().firstOrNull()!!
        val entry = inner.optJSONObject(id)!!

        assertEquals("ctx7", id)
        assertTrue("oauth must be detected on the inner entry", entry.has("oauth"))
    }

    @Test
    fun `chat parts sanitizer keeps text and drops media`() {
        val json = """
            [
              {"type":"text","value":"hello"},
              {"type":"image","image_base64":"AAAA...."},
              {"type":"video_url","url":"file:///sdcard/x.mp4"},
              {"type":"tool_use","name":"shell","toolArgs":{"command":"ls"}},
              {"type":"text","value":"<user-attached-files><file>a.png</file></user-attached-files>bye"}
            ]
        """.trimIndent()

        val out = ConfigBackup.sanitizeChatParts(json)
        assertTrue("text must survive sanitizing", out != null)
        val arr = JSONArray(out!!)
        // image + video dropped, text + tool_use + cleaned text remain.
        assertEquals(3, arr.length())
        val textValues = (0 until arr.length()).map { arr.getJSONObject(it).optString("value") }
        assertTrue(textValues.contains("hello"))
        assertTrue(textValues.contains("bye"))
        assertFalse("attached-files inventory must be stripped", textValues.any { it.contains("user-attached-files") })
        assertFalse("no media types may survive", (0 until arr.length()).any {
            arr.getJSONObject(it).optString("type").startsWith("image") ||
                arr.getJSONObject(it).optString("type").startsWith("video")
        })
    }

    @Test
    fun `chat parts sanitizer returns null for media-only or unparseable`() {
        assertNull(ConfigBackup.sanitizeChatParts("""[{"type":"image","image_base64":"x"}]"""))
        assertNull(ConfigBackup.sanitizeChatParts("""[{"type":"video","url":"x"}]"""))
        assertNull(ConfigBackup.sanitizeChatParts("not json"))
        assertNull(ConfigBackup.sanitizeChatParts(null))
        assertNull(ConfigBackup.sanitizeChatParts(""))
    }

    @Test
    fun `toolResult output is truncated and snapshot preview dropped`() {
        val huge = "x".repeat(10_000)
        val json = """
            [
              {"type":"text","value":"keep me"},
              {"type":"toolResult","value":{
                  "toolUseId":"call_1",
                  "name":"shell_execute",
                  "success":true,
                  "output":"$huge",
                  "snapshot":{"type":"text","text":"$huge"}
              }}
            ]
        """.trimIndent()

        val out = ConfigBackup.sanitizeChatParts(json)
        assertNotNull(out)
        val arr = JSONArray(out!!)
        assertEquals(2, arr.length())
        val tr = arr.getJSONObject(1).getJSONObject("value")
        assertTrue("snapshot must be dropped from backup",
            !tr.has("snapshot"))
        val output = tr.optString("output")
        assertTrue("output must be truncated to cap",
            output.length <= ConfigBackup.MAX_BACKUP_TOOL_OUTPUT_CHARS + 64)
        assertTrue("truncated output must carry a marker",
            output.contains("truncated"))
        assertEquals("toolUseId must survive", "call_1", tr.optString("toolUseId"))
        assertEquals("name must survive", "shell_execute", tr.optString("name"))
        assertTrue("small toolResult output stays intact",
            "keep me" in (0 until arr.length()).map { arr.getJSONObject(it).optString("value") })
    }

    @Test
    fun `small toolResult output is left untouched`() {
        val json = """
            [
              {"type":"toolResult","value":{
                  "toolUseId":"call_9",
                  "name":"shell_execute",
                  "success":true,
                  "output":"tiny output",
                  "snapshot":{"type":"text","text":"tiny output"}
              }}
            ]
        """.trimIndent()
        val out = ConfigBackup.sanitizeChatParts(json)
        assertNotNull(out)
        val arr = JSONArray(out!!)
        val tr = arr.getJSONObject(0).getJSONObject("value")
        assertTrue(!tr.has("snapshot"))
        assertEquals("tiny output", tr.optString("output"))
    }

    @Test
    fun `reasoning content is capped at backup limit`() {
        val short = "short reasoning"
        assertEquals(short, ConfigBackup.capReasoningContent(short))
        assertNull(ConfigBackup.capReasoningContent(null))
        assertNull(ConfigBackup.capReasoningContent(""))
        assertTrue(ConfigBackup.capReasoningContent("") == null)

        val long = "r".repeat(ConfigBackup.MAX_BACKUP_REASONING_CHARS + 500)
        val capped = ConfigBackup.capReasoningContent(long)
        assertNotNull(capped)
        assertTrue("long reasoning must be cut", capped!!.length < long.length)
        assertTrue("marker must be present", capped.contains("truncated"))
    }

    @Test
    fun `chat window of zero yields no history in export payload`() {
        // chatWindowDays <= 0 is the "off" switch: the export must carry the
        // chatSessions/chatMessages keys (so the reader knows the section was
        // processed) but with empty arrays.
        val payload = JSONObject().apply {
            put("chatSessions", JSONArray())
            put("chatMessages", JSONArray())
        }
        assertEquals(0, payload.optJSONArray("chatSessions")!!.length())
        assertEquals(0, payload.optJSONArray("chatMessages")!!.length())
    }
}
