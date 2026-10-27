package com.rikkaminis.app.debug

import com.rikkaminis.app.data.db.MessageEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1032-B / ISSUE-03] The debug RPC view of a persisted turn is built by
 * `ChatDebugMethods.messageToJson`, which classifies each `parts_json` block
 * by its `type` key. That key is the `@SerialName` of
 * [com.rikkaminis.app.data.model.ContentPart] — `text` / `mediaRef` /
 * `toolUse` / `toolResult` — while the classifier only knew the legacy
 * underscore spellings, so every real tool call and attachment vanished from
 * `chat.messages.list` (both arrays permanently empty).
 *
 * Two layers, each with its own device:
 *
 *  1. *Behaviour, really executed*: [ChatDebugMethods.messageToJson] is pure —
 *     it takes a `MessageEntity` and returns the RPC JSON — so the arms below
 *     feed it real rows and assert the classification, with expectations
 *     written as literals (never derived from the production constants).
 *  2. *Wiring, as source shape*: `chat.sessions.get` must publish the same
 *     session shape as `chat.sessions.list` and the composite model-reference
 *     predicate must exist once. Both are Context/File-bound call sites the
 *     JVM cannot drive, so — the house pattern for such sites (see
 *     ScreenshotCaptureShapeTest / SoulStoreWriteVersionTest) — the shipped
 *     source is read and probed, and every probe carries an in-test negative
 *     control: the same probe that accepts the shipped source must reject a
 *     mutated copy of it.
 *
 * probe/jvm/ compiles this exact file against hand-written stubs plus a
 * verbatim slice of `messageToJson` (`extract.py` is anchor-anchored and
 * fail-closed), so layer 1 runs offline as well as in CI.
 */
class ChatDebugMessagePartsTest {

    // ── layer 1: behaviour, really executed ──────────────────────────────────

    private fun msg(parts: String) = MessageEntity(
        id = "msg_1",
        sessionId = "s1",
        role = "assistant",
        partsJson = parts,
        createdAt = 1_700_000_000_000L,
        sortOrder = 0,
    )

    /** The exact shapes the writers emit (ChatTurnPartsJson / ChatMessageJson). */
    private val realTurnParts = """
        [
          {"type":"text","value":"running it "},
          {"type":"toolUse","value":{"toolUseId":"tool_1","name":"shell_execute","input":"{\"command\":\"ls\"}","description":"List","pageURL":"","imageFilePath":"","thoughtSignature":null}},
          {"type":"toolResult","value":{"toolUseId":"tool_1","name":"shell_execute","output":"ok","success":true}},
          {"type":"mediaRef","value":{"id":"m1","relativePath":"uploads/photo.jpg","mimeType":"image/jpeg"}}
        ]
    """.trimIndent()

    @Test
    fun `real serial names reach their RPC buckets`() {
        val out = ChatDebugMethods.messageToJson(msg(realTurnParts), includeTools = true, includeReasoning = false)

        assertEquals("running it ", out.getString("content"))
        assertEquals(2, out.getJSONArray("toolCalls").length())
        assertEquals(1, out.getJSONArray("attachments").length())

        val toolUse = out.getJSONArray("toolCalls").getJSONObject(0)
        assertEquals("toolUse", toolUse.getString("type"))
        assertEquals("shell_execute", toolUse.getJSONObject("value").getString("name"))

        val toolResult = out.getJSONArray("toolCalls").getJSONObject(1)
        assertEquals("toolResult", toolResult.getString("type"))
        assertEquals("ok", toolResult.getJSONObject("value").getString("output"))

        val attachment = out.getJSONArray("attachments").getJSONObject(0)
        assertEquals("mediaRef", attachment.getString("type"))
        assertEquals("uploads/photo.jpg", attachment.getJSONObject("value").getString("relativePath"))
    }

    @Test
    fun `includeTools=false drops tool blocks but keeps the attachment`() {
        val out = ChatDebugMethods.messageToJson(msg(realTurnParts), includeTools = false, includeReasoning = false)

        assertFalse(out.has("toolCalls"))
        assertEquals(1, out.getJSONArray("attachments").length())
    }

    @Test
    fun `legacy underscore rows still classify`() {
        val legacy = """
            [
              {"type":"tool_call","value":{"name":"shell_execute"}},
              {"type":"tool_use","value":{"name":"shell_execute"}},
              {"type":"tool_result","value":{"output":"ok"}},
              {"type":"image","value":{"relativePath":"x.png"}},
              {"type":"file","value":{"relativePath":"y.pdf"}},
              {"type":"attachment","value":{"relativePath":"z.txt"}}
            ]
        """.trimIndent()
        val out = ChatDebugMethods.messageToJson(msg(legacy), includeTools = true, includeReasoning = false)

        assertEquals(3, out.getJSONArray("toolCalls").length())
        assertEquals(3, out.getJSONArray("attachments").length())
    }

    @Test
    fun `unknown types are ignored and malformed rows fall back to raw content`() {
        val odd = """[{"type":"reasoning","value":"hidden"}]"""
        val out = ChatDebugMethods.messageToJson(msg(odd), includeTools = true, includeReasoning = false)

        assertFalse(out.has("toolCalls"))
        assertFalse(out.has("attachments"))
        assertFalse(out.has("content"))
        assertEquals(1, out.getJSONArray("parts").length())

        val broken = ChatDebugMethods.messageToJson(msg("not json"), includeTools = true, includeReasoning = false)
        assertEquals("not json", broken.getString("content"))
    }

    // ── layer 2: wiring, as source shape (with negative controls) ─────────────

    private val debugRel = "src/android/app/src/main/java/com/rikkaminis/app/debug/ChatDebugMethods.kt"
    private val mutationRel = "src/android/app/src/main/java/com/rikkaminis/app/debug/ChatMutationMethods.kt"

    /** Gradle sets user.dir to the module dir; a sandbox JVM run walks up. */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    // ── comment stripping (so a KDoc mention cannot satisfy a probe) ──────────

    private fun codeOnly(source: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    val nl = source.indexOf('\n', i)
                    i = if (nl < 0) source.length else nl
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    val stop = if (end < 0) source.length else end + 2
                    // keep newlines so line-locality stays meaningful
                    out.append(source.substring(i, stop).map { if (it == '\n') '\n' else ' ' }.joinToString(""))
                    i = stop
                }
                c == '"' -> {
                    val end = stringEnd(source, i)
                    out.append(source, i, end)
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun stringEnd(source: String, start: Int): Int {
        if (source.startsWith("\"\"\"", start)) {
            val end = source.indexOf("\"\"\"", start + 3)
            return if (end < 0) source.length else end + 3
        }
        var i = start + 1
        while (i < source.length) {
            if (source[i] == '\\') i += 2
            else if (source[i] == '"') return i + 1
            else i++
        }
        return source.length
    }

    /** Body of `sig`, by paren-then-brace matching on comment-free source. */
    private fun functionBody(source: String, sig: String): String? {
        val start = source.indexOf(sig)
        if (start < 0) return null
        val paren = source.indexOf('(', start)
        if (paren < 0) return null
        var depth = 0
        var j = paren
        while (j < source.length) {
            when (source[j]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            j++
        }
        val brace = source.indexOf('{', j)
        if (brace < 0) return null
        depth = 0
        var k = brace
        while (k < source.length) {
            when (source[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, k + 1)
                }
            }
            k++
        }
        return null
    }

    /**
     * The two session RPCs must publish one shape, produced by one mapper, and
     * neither caller may re-inline a shared field (that is exactly how the two
     * views drifted apart the first time).
     */
    private fun sessionViewsShareOneMapper(source: String): Boolean {
        val code = codeOnly(source)
        val list = functionBody(code, "fun sessionsList(") ?: return false
        val get = functionBody(code, "fun sessionsGet(") ?: return false
        val mapper = functionBody(code, "fun sessionCoreJson(") ?: return false
        val routed = "sessionCoreJson(" in list && "sessionCoreJson(" in get
        val carried = "put(\"pinnedAt\"" in mapper && "put(\"lastMessagePreview\"" in mapper
        val reInlined = "put(\"pinnedAt\"" in get || "put(\"lastMessagePreview\"" in get
        return routed && carried && !reInlined
    }

    /** The composite model-id predicate must live in exactly one debug file. */
    private fun compositeResolverIsSingle(debugSource: String, mutationSource: String): Boolean {
        val debug = codeOnly(debugSource)
        val mutation = codeOnly(mutationSource)
        val definition = functionBody(debug, "fun resolveModelDisplay(") ?: return false
        if ("it.baseModel.id == modelId || it.id == modelId" !in definition) return false
        if (Regex("modelEntries\\.firstOrNull\\s*\\{[^}]*baseModel\\.id\\s*==").containsMatchIn(mutation)) return false
        val calls = Regex("resolveModelDisplay\\(").findAll(debug).count() - 1 +
            Regex("resolveModelDisplay\\(").findAll(mutation).count()
        return calls >= 2
    }

    @Test
    fun `sessions RPCs share one session shape`() {
        val debug = readRepoFile(debugRel)
        assertTrue("sessionsList/sessionsGet no longer share sessionCoreJson", sessionViewsShareOneMapper(debug))

        // negative control: the pre-fix shape (sessionsGet builds its own JSON)
        val inlineCopy = debug.replace(
            """        return sessionCoreJson(session, provRepo).apply {
            put("messageCount", messageCount)
        }""",
            """        return JSONObject().apply {
            put("messageCount", messageCount)
        }""",
        )
        assertTrue("negative control did not mutate the source", inlineCopy != debug)
        assertFalse("probe accepted a sessionsGet that builds its own object", sessionViewsShareOneMapper(inlineCopy))

        // negative control: a caller re-inlining a field the mapper already emits
        val reInlined = debug.replace(
            """        return sessionCoreJson(session, provRepo).apply {
            put("messageCount", messageCount)
        }""",
            """        return sessionCoreJson(session, provRepo).apply {
            put("pinnedAt", session.pinnedAt ?: JSONObject.NULL)
            put("messageCount", messageCount)
        }""",
        )
        assertFalse("probe accepted a re-inlined session field", sessionViewsShareOneMapper(reInlined))
    }

    @Test
    fun `composite model predicate exists once and is reached from both sides`() {
        val debug = readRepoFile(debugRel)
        val mutation = readRepoFile(mutationRel)
        assertTrue(
            "resolveModelDisplay no longer single-sourced",
            compositeResolverIsSingle(debug, mutation),
        )

        // negative control: the mutation side keeps its own bare-id copy
        val localCopy = mutation.replace(
            "return ChatDebugMethods.resolveModelDisplay(app(context).providerRepository, modelId)",
            "val cfg = app(context).providerRepository.config.value\n" +
                "        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId } ?: return modelId\n" +
                "        return entry.model.displayName",
        )
        assertTrue("negative control did not mutate the source", localCopy != mutation)
        assertFalse(
            "probe accepted a re-derived mutation-side predicate",
            compositeResolverIsSingle(debug, localCopy),
        )

        // negative control: the composite arm silently dropped from the resolver
        val bareOnly = debug.replace("it.baseModel.id == modelId || it.id == modelId", "it.baseModel.id == modelId")
        assertFalse("probe accepted a bare-id-only resolver", compositeResolverIsSingle(bareOnly, mutation))
    }

    /** Regression anchor: the read side keeps classifying the legacy buckets. */
    @Test
    fun `legacy bucket literals are not removed by the widening`() {
        val debug = codeOnly(readRepoFile(debugRel))
        val body = functionBody(debug, "fun messageToJson(")
        assertTrue("messageToJson not found", body != null)
        for (legacy in listOf("\"tool_call\"", "\"tool_use\"", "\"tool_result\"", "\"image\"", "\"file\"", "\"attachment\"")) {
            assertTrue("legacy bucket $legacy dropped from messageToJson", legacy in body!!)
        }
    }

    /** The serial names the classifier must know are the real ones, not ours. */
    @Test
    fun `classification follows the ContentPart serial names`() {
        val part = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/data/model/ContentPart.kt")
        val declared = Regex("@SerialName\\(\"([a-zA-Z]+)\"\\)").findAll(part).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("text", "mediaRef", "toolUse", "toolResult"), declared)

        val body = functionBody(codeOnly(readRepoFile(debugRel)), "fun messageToJson(")!!
        for (name in declared) {
            assertTrue("messageToJson does not classify \"$name\"", "\"$name\"" in body)
        }
    }

    /** A KDoc mention of a serial name must not count as handling it. */
    @Test
    fun `comment stripping hides doc-only mentions`() {
        val fake = """
            fun messageToJson(m: MessageEntity): JSONObject {
                // handles "mediaRef" below
                /* and "toolUse" too */
                return JSONObject().apply { put("kind", "text") }
            }
        """.trimIndent()
        val body = functionBody(codeOnly(fake), "fun messageToJson(")!!
        assertFalse("\"mediaRef\" in stripped body", "\"mediaRef\"" in body)
        assertFalse("\"toolUse\" in stripped body", "\"toolUse\"" in body)
        assertTrue("code survives stripping", "put(\"kind\", \"text\")" in body)
    }

    // ══ [fix/dv23-a5] the session-list emptiness probe ════════════════════════
    //
    // `chat.sessions.list` used to answer "does this session have any message?"
    // by calling `repo.dao.lastMessageParts(session.id)` — a raw
    // `SELECT parts_json … LIMIT 1` per session. Two defects rode on that one
    // line: the string (up to MAX_MESSAGE_PARTS_JSON_BYTES) was materialised
    // only to be compared with null, and the read bypassed the repository's
    // CursorWindow-tolerant ceiling, so one >2 MB historical row aborted the
    // whole RPC with SQLiteBlobTooBigException (Issue #17).
    //
    // Behaviour for the fixed shape is executed offline by
    // probe/jvm/SessionsListProbeTest (verbatim slice + counting fake DAO:
    // PRE reds on the crash and on the wasted reads, POST green). What CI can
    // still catch without a device is the SHAPE drifting back, so the probes
    // below read the shipped source and every one carries a negative control:
    // the same probe must reject a mutated copy of the file it just accepted.

    private fun sessionsListBody(source: String): String? =
        functionBody(codeOnly(source), "fun sessionsList(")

    /** DAO queries that can pull `parts_json`, derived from ChatDao's own @Query
     *  bodies — never a hand-kept list, so a new blob read is covered the day
     *  it lands. `SELECT *` over messages counts too: the star expands onto
     *  every column of that row. */
    private fun partsJsonReaderNames(daoSource: String): Set<String> {
        val code = codeOnly(daoSource)
        val names = LinkedHashSet<String>()
        var i = 0
        while (true) {
            val at = code.indexOf("@Query", i)
            if (at < 0) break
            val paren = code.indexOf('(', at)
            if (paren < 0) break
            var depth = 0
            var j = paren
            while (j < code.length) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                }
                j++
            }
            if (depth != 0) break
            val query = code.substring(paren + 1, j)
            val funAt = Regex("fun\\s+(\\w+)\\s*\\(").find(code.substring(j + 1))
            i = at + 6
            if (funAt == null) continue
            val name = funAt.groupValues[1]
            val readsBlob = "parts_json" in query ||
                Regex("SELECT\\s+\\*\\s+.*?FROM\\s+messages", RegexOption.IGNORE_CASE)
                    .containsMatchIn(query)
            if (readsBlob) names.add(name)
        }
        return names
    }

    /** Every `if (…)` guard in `code`, as (condition, span-start, span-end). */
    private fun guards(code: String): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var i = 0
        while (true) {
            val at = code.indexOf("if (", i)
            if (at < 0) break
            val paren = code.indexOf('(', at)
            var depth = 0
            var j = paren
            while (j < code.length) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                }
                j++
            }
            if (depth != 0) break
            val cond = code.substring(paren + 1, j)
            var k = j + 1
            while (k < code.length && code[k].isWhitespace()) k++
            var end: Int
            if (k < code.length && code[k] == '{') {
                var d = 0
                var m = k
                while (m < code.length) {
                    when (code[m]) {
                        '{' -> d++
                        '}' -> { d--; if (d == 0) break }
                    }
                    m++
                }
                end = m + 1
            } else {
                val nl = code.indexOf('\n', k)
                end = if (nl < 0) code.length else nl
            }
            out.add(Triple(cond, at, end))
            i = at + 3
        }
        return out
    }

    /** The emptiness probe is a COUNT, and it is skipped outright when the
     *  caller asked for empty sessions too — asking a question whose answer is
     *  discarded is the exact waste the fix removed. */
    private fun probeIsCountAndSkippedWhenIncludingEmpty(body: String): Boolean {
        val probes = Regex("messageCount\\s*\\(").findAll(body).toList()
        if (probes.isEmpty()) return false
        val gs = guards(body)
        return probes.all { p ->
            gs.any { g -> p.range.first in g.second until g.third && "includeEmpty" in g.first }
        }
    }

    /** The `if (… includeEmpty …) continue` statement that gates emission, as
     *  text, or null when the loop no longer has one. Paren-balanced, so a
     *  condition containing a call (`repo.messageCount(session.id) == 0`) is
     *  read whole. */
    private fun emptyFilterGuard(body: String): String? {
        var i = 0
        while (true) {
            val at = body.indexOf("continue", i)
            if (at < 0) return null
            i = at + 8
            val ifAt = body.lastIndexOf("if (", at)
            if (ifAt < 0) continue
            val paren = body.indexOf('(', ifAt)
            var depth = 0
            var j = paren
            while (j < at) {
                when (body[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            }
            if (depth != 0) continue
            val cond = body.substring(paren + 1, j - 1)
            if ("includeEmpty" in cond) return body.substring(ifAt, i)
        }
    }

    @Test
    fun `sessions list answers emptiness with a count, never the blob`() {
        val source = readRepoFile(debugRel)
        val dao = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/data/db/ChatDao.kt")
        val body = sessionsListBody(source)
        assertTrue("sessionsList not found", body != null)
        requireNotNull(body) { "sessionsList body" }

        val readers = partsJsonReaderNames(dao)
        assertTrue("no parts_json reader derived from ChatDao — probe went blind", readers.isNotEmpty())
        val rawReads = readers.filter { ".dao.$it(" in body }
        assertTrue("sessionsList reads parts_json through $rawReads", rawReads.isEmpty())

        assertTrue(
            "sessionsList no longer answers 'is it empty?' with a COUNT",
            probeIsCountAndSkippedWhenIncludingEmpty(body),
        )

        // The two negative controls below mutate the loop by replacing its
        // per-session filter. Locate that filter structurally (paren-balanced
        // `if (…) continue` whose condition mentions includeEmpty) rather than
        // by assuming a spelling — and treat "not found" as RED, since that
        // means the shape moved away from what this probe claims to guard.
        val guardText = emptyFilterGuard(body)
        assertTrue("sessionsList has no includeEmpty-guarded `continue`", guardText != null)
        val guard = guardText!!

        // negative control 1: the shipped pre-fix shape
        val blobProbe = body.replace(
            guard,
            "val lastParts = repo.dao.lastMessageParts(session.id)\n" +
                "            if (!includeEmpty && lastParts == null) continue",
        )
        assertTrue("negative control 1 did not mutate the source", blobProbe != body)
        assertTrue(
            "probe accepted a raw lastMessageParts read",
            readers.any { ".dao.$it(" in blobProbe },
        )

        // negative control 2: the probe survives but runs unconditionally
        val unguarded = body.replace(
            guard,
            "val n = repo.messageCount(session.id)\n" +
                "            if (!includeEmpty && n == 0) continue",
        )
        assertTrue("negative control 2 did not mutate the source", unguarded != body)
        assertFalse(
            "probe accepted an unconditional COUNT — includeEmpty=true would pay for it",
            probeIsCountAndSkippedWhenIncludingEmpty(unguarded),
        )
    }

    @Test
    fun `a new blob-reading DAO query is covered without editing the test`() {
        val dao = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/data/db/ChatDao.kt")
        val before = partsJsonReaderNames(dao)
        val grown = dao.replace(
            """    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")""",
            """    @Query("SELECT * FROM messages WHERE role = :role")
    suspend fun messagesByRole(role: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")""",
        )
        assertTrue("negative control did not mutate ChatDao", grown != dao)
        val after = partsJsonReaderNames(grown)
        assertTrue(
            "derivation missed a newly added SELECT * FROM messages query: +${after - before}",
            "messagesByRole" in after,
        )
        // ... and a COUNT over the same table is still NOT a blob read
        assertFalse(
            "COUNT(*) was mistaken for a blob read",
            "messageCountForSession" in after,
        )
    }
}
