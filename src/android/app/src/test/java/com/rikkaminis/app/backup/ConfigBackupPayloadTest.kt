package com.rikkaminis.app.backup

import com.rikkaminis.app.tools.SkillInfo
import com.rikkaminis.app.tools.SubagentSkill
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

    // ── [feat/scheduled-tasks-l0] scheduledTasks section ─────────────────────

    @Test
    fun `scheduledTasks section round-trips through the payload shape`() {
        // The store hands the document over verbatim; the codec round-trip it
        // must survive is exactly ScheduledTasksCodec.parse(encode(..)) —
        // asserted with the real org.json artifact here.
        val doc = JSONObject(
            """
            {"version":1,"globalEnabled":true,
             "tasks":[{"id":"t-1","title":"早报","prompt":"p",
                       "window":{"start":"07:00","end":"23:00"},"enabled":false,
                       "budget":{"maxTurns":30,"deadlineSec":600,"maxEstimatedTokens":100000},
                       "lastRunDate":"2026-10-03","lastResult":"ok",
                       "failStreak":0,"suspendedByFuse":false}]}
            """.trimIndent()
        )
        assertTrue(doc.has("tasks"))
        assertEquals(true, doc.optBoolean("globalEnabled"))
        assertEquals("t-1", doc.optJSONArray("tasks")!!.optJSONObject(0)!!.optString("id"))
    }

    @Test
    fun `scheduledTasks section is omitted when the caller passes null`() {
        // Mirrors the streaming frameKeys invariant: a section present in the
        // skeleton MUST be in the frame list. Null sections are simply absent
        // from the skeleton, so old backups keep importing (asserted above).
        val skeleton = JSONObject().apply {
            put("format", "openminis.config.backup")
            put("version", 1)
            put("createdAt", 0L)
            put("includesSecrets", false)
            put("fields", JSONObject())
            put("providers", JSONArray())
        }
        assertFalse(skeleton.has("scheduledTasks"))
        assertNull(skeleton.optJSONObject("scheduledTasks"))
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
    fun `chat parts sanitizer drops the current mediaRef shape too`() {
        // [FIX-R3-D / F-15] Rows written today carry `mediaRef` (see
        // ChatMessageJson.buildMediaRefPartJson), not "image"/"video" — a
        // pointer into the EXPORTING device's media store. The attach filter
        // only knew the pre-migration names, so cross-device backups shipped
        // those absolute/relative paths in every affected message: the
        // receiving device cannot resolve them and the file leaks a private
        // path for no benefit (the media bytes never travel).
        val json = """
            [
              {"type":"text","value":"see this"},
              {"type":"mediaRef","mediaRef":{"id":"m-1","kind":"image",
                 "mimeType":"image/png","relativePath":"Pictures/secret-shot.png",
                 "linuxPath":"/storage/emulated/0/Pictures/secret-shot.png"}},
              {"type":"toolResult","value":{
                  "toolUseId":"call_1","name":"read_image","success":true,"output":"ok",
                  "mediaRef":{"id":"m-2","kind":"image","linuxPath":"/data/x.png"}
              }}
            ]
        """.trimIndent()

        val out = ConfigBackup.sanitizeChatParts(json)!!
        val arr = JSONArray(out)
        assertEquals(2, arr.length())
        assertTrue(
            "no mediaRef part may survive",
            (0 until arr.length()).none { arr.getJSONObject(it).optString("type") == "mediaRef" },
        )
        // The tool result itself stays (its text is the evidence), only the
        // unresolvable pointer goes.
        val tool = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .first { it.optString("type") == "toolResult" }
        assertFalse(tool.getJSONObject("value").has("mediaRef"))
        assertEquals("ok", tool.getJSONObject("value").optString("output"))
        assertFalse("no private media path may ride the payload", out.contains("secret-shot.png"))
    }

    @Test
    fun `chat parts sanitizer returns null for media-only or unparseable`() {
        assertNull(ConfigBackup.sanitizeChatParts("""[{"type":"image","image_base64":"x"}]"""))
        assertNull(ConfigBackup.sanitizeChatParts("""[{"type":"video","url":"x"}]"""))
        assertNull(
            ConfigBackup.sanitizeChatParts(
                """[{"type":"mediaRef","mediaRef":{"id":"m-1","linuxPath":"/data/x.png"}}]""",
            ),
        )
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

    // ── MCP secret gating (sweep1013-D1/F-3) ─────────────────────────────

    @Test
    fun `stripMcpSecretValues blanks header and env values but keeps keys`() {
        val server = JSONObject()
            .put("command", "python3")
            .put("args", JSONArray().put("server.py"))
            .put(
                "env",
                JSONObject()
                    .put("GITHUB_TOKEN", "ghp_literal_secret")
                    .put("NODE_ENV", "production"),
            )
            .put(
                "headers",
                JSONObject()
                    .put("Authorization", "Bearer sk-literal-secret")
                    .put("X-Api-Key", "key-12345"),
            )
            .put("note", "my server")
            .put("enabled", true)

        ConfigBackup.stripMcpSecretValues(server)

        // Key names survive (so the server still imports and the user sees
        // what to refill), values are blanked.
        val env = server.getJSONObject("env")
        assertEquals("", env.getString("GITHUB_TOKEN"))
        assertEquals("", env.getString("NODE_ENV"))
        val headers = server.getJSONObject("headers")
        assertEquals("", headers.getString("Authorization"))
        assertEquals("", headers.getString("X-Api-Key"))
        // Non-secret fields untouched.
        assertEquals("python3", server.getString("command"))
        assertEquals("my server", server.getString("note"))
        assertTrue(server.getBoolean("enabled"))
    }

    @Test
    fun `stripMcpSecretValues leaves absent sections absent`() {
        val server = JSONObject().put("url", "https://example.com/sse").put("enabled", true)
        ConfigBackup.stripMcpSecretValues(server)
        assertFalse(server.has("headers"))
        assertFalse(server.has("env"))
        assertEquals("https://example.com/sse", server.getString("url"))
    }

    // ── [review D-3] credential carriers beyond headers/env ──────────────

    @Test
    fun `stripMcpSecretValues blanks token-shaped args but keeps benign args`() {
        val server = JSONObject()
            .put("command", "npx")
            .put(
                "args",
                JSONArray()
                    .put("--token=ghp_literal_secret")
                    .put("--port=8080")
                    .put("-y")
                    .put("--API_KEY=abc123def"),
            )
        ConfigBackup.stripMcpSecretValues(server)
        val args = server.getJSONArray("args")
        // Credential-shaped flags keep the flag name, lose the value —
        // the restored entry still shows WHAT to refill.
        assertEquals("--token=", args.getString(0))
        assertEquals("--port=8080", args.getString(1))
        assertEquals("-y", args.getString(2))
        assertEquals("--API_KEY=", args.getString(3))
    }

    @Test
    fun `stripMcpSecretValues redacts sensitive url query values keeps benign`() {
        val server = JSONObject()
            .put("url", "https://gateway.example.com/sse?key=supersecret&client=app#frag")
        ConfigBackup.stripMcpSecretValues(server)
        assertEquals(
            "https://gateway.example.com/sse?key=[redacted:11]&client=app#frag",
            server.getString("url"),
        )
    }

    @Test
    fun `stripMcpSecretValues leaves url without query untouched`() {
        val server = JSONObject().put("url", "https://example.com/sse")
        ConfigBackup.stripMcpSecretValues(server)
        assertEquals("https://example.com/sse", server.getString("url"))
    }

    // ── [review G1] MCP export wrapper shape (stripExportedMcpSecrets) ───

    @Test
    fun `stripExportedMcpSecrets strips the nested wrapper exportServerJSON emits`() {
        // exportServerJSON emits {"mcpServers":{"<id>":{…}}} — the entry with
        // headers/env/args/url sits ONE level down. Stripping the wrapper
        // itself (the old call) was a no-op: every secret survived into the
        // backup payload even with includeSecrets=false. This pins the shape
        // the export path actually passes.
        val entry = JSONObject()
            .put("url", "https://gw.example.com/sse?key=supersecret&client=app")
            .put("headers", JSONObject().put("Authorization", "Bearer sekrit"))
            .put("env", JSONObject().put("GITHUB_TOKEN", "ghp_sekrit"))
            .put("args", JSONArray().put("--token=ghp_arg"))
        val wrapper = JSONObject().put("mcpServers", JSONObject().put("ctx7", entry))

        ConfigBackup.stripExportedMcpSecrets(wrapper)

        val inner = wrapper.getJSONObject("mcpServers").getJSONObject("ctx7")
        assertEquals(
            "https://gw.example.com/sse?key=[redacted:11]&client=app",
            inner.getString("url"),
        )
        assertEquals("", inner.getJSONObject("headers").getString("Authorization"))
        assertEquals("", inner.getJSONObject("env").getString("GITHUB_TOKEN"))
        assertEquals("--token=", inner.getJSONArray("args").getString(0))
    }

    @Test
    fun `stripExportedMcpSecrets falls back to bare entry like the import side`() {
        val bare = JSONObject()
            .put("env", JSONObject().put("GITHUB_TOKEN", "ghp_sekrit"))
            .put("headers", JSONObject().put("Authorization", "Bearer sekrit"))

        ConfigBackup.stripExportedMcpSecrets(bare)

        assertEquals("", bare.getJSONObject("env").getString("GITHUB_TOKEN"))
        assertEquals("", bare.getJSONObject("headers").getString("Authorization"))
    }

    // ── Skill frontmatter fallback (sweep1013-D1/F-8) ────────────────────

    private fun skillInfoFor(frontmatter: String, body: String): SkillInfo = object : SkillInfo {
        override val name = "demo"
        override val description = "d"
        override val body = body
        override val frontmatter = frontmatter
    }

    @Test
    fun `frontmatter rejoined to body keeps subagent-only keys parseable`() {
        // The export fallback carries the raw frontmatter block alongside the
        // frontmatter-STRIPPED body; the restore branch re-joins them exactly
        // as `frontmatter + "\n" + body` before importFromContent re-parses.
        // A skill whose `subagent: true` / `max_turns` exist ONLY in the
        // frontmatter must survive that re-join.
        val frontmatter = "---\nname: demo\ndescription: d\nsubagent: true\nmax_turns: 7\n---"
        val body = "You are a fix executor."
        val joined = frontmatter + "\n" + body // mirror of the restore branch

        val parsed = SubagentSkill.parseSubagentConfig(skillInfoFor(joined, joined))
        assertTrue("subagent flag must survive the re-join", parsed.isSubagent)
        assertEquals(7, parsed.maxTurns)
    }

    @Test
    fun `body-only legacy fallback still parses without frontmatter`() {
        // Older backups carry body only (no "frontmatter" key) — the restore
        // branch treats frontmatter as absent and hands the body through.
        val parsed = SubagentSkill.parseSubagentConfig(skillInfoFor("", "plain skill body"))
        assertFalse("no frontmatter means not a subagent", parsed.isSubagent)
    }
}
