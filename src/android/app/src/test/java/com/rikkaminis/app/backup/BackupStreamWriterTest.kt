package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking
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

    // ── audit-0917: size cap + frame-key guard ────────────────────────────

    @Test
    fun `cap writer passes through documents under the limit`() {
        val sw = StringWriter()
        val capped = BackupStreamWriter.CapEnforcingWriter(sw, maxChars = 1024)
        BackupStreamWriter.writeObjectFrame(capped, listOf("a", "b")) { w, key ->
            w.write(JSONObject.quote("v-$key"))
        }
        assertEquals("""{"a":"v-a","b":"v-b"}""", sw.toString())
    }

    @Test
    fun `cap writer fails closed once the limit is crossed`() {
        val sw = StringWriter()
        val capped = BackupStreamWriter.CapEnforcingWriter(sw, maxChars = 8)
        // The failure must surface at export time (actionable) rather than
        // producing a backup file import() will later hard-reject.
        try {
            BackupStreamWriter.writeObjectFrame(capped, listOf("key")) { w, _ ->
                w.write(JSONObject.quote("a value longer than the cap"))
            }
            fail("expected IllegalStateException for an oversize document")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Backup too large"))
        }
    }

    @Test
    fun `cap writer counts cumulatively across writes`() {
        val sw = StringWriter()
        val capped = BackupStreamWriter.CapEnforcingWriter(sw, maxChars = 10)
        capped.write("12345")
        // 5 + 6 > 10 — the second write must trip even though it is small.
        try {
            capped.write("678901")
            fail("expected IllegalStateException on the cumulative overflow")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `missingFrameKeys reports sections the hardcoded list would drop`() {
        val frame = listOf("format", "version", "providers")
        // Skeleton grew a section the frame list never learned about —
        // that is exactly the silent streaming-only omission.
        assertEquals(
            listOf("newSection"),
            BackupStreamWriter.missingFrameKeys(frame, listOf("format", "providers", "newSection")),
        )
        assertEquals(
            emptyList<String>(),
            BackupStreamWriter.missingFrameKeys(frame, listOf("format", "providers")),
        )
    }

    // ── [FIX-P1A / RT-S3] pre-restore snapshot write ─────────────────────
    //
    // The snapshot is the ROLLBACK point for a restore. It used to be written
    // straight to its final name, so an ENOSPC / a kill mid-stream left a
    // truncated `.json` that `listSnapshots` offered as the newest rollback
    // point, and the name was the clock at second precision, so two restores in
    // one second wrote the same path (the second truncating the first, i.e. a
    // failure there left ZERO usable rollback points). These arms run the real
    // production functions against real temp directories — no Android types
    // involved (kotlinx-coroutines' [runBlocking] supplies the suspend caller).

    private fun tempSnapshotDir(tag: String): java.io.File {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "snapshot-$tag-${System.nanoTime()}",
        )
        dir.mkdirs()
        return dir
    }

    @Test
    fun `failed snapshot write leaves no snapshot and no temp behind`() = runBlocking {
        val dir = tempSnapshotDir("fail")
        try {
            var threw = false
            try {
                ConfigBackup.writeSnapshotStreaming(dir) { w ->
                    w.write("{\"format\":\"openminis.config.backup\",")
                    w.flush()
                    throw java.io.IOException("ENOSPC")
                }
            } catch (_: java.io.IOException) {
                threw = true
            }
            assertTrue("the write failure must reach the caller", threw)
            assertEquals(
                "a half-written document must never become a rollback candidate",
                emptyList<String>(),
                ConfigBackup.listSnapshots(dir).map { it.name },
            )
            assertEquals(
                "the .tmp sidecar must not be left behind either",
                emptyList<String>(),
                (dir.listFiles() ?: emptyArray()).map { it.name },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the final name only appears after the writer closed cleanly`() = runBlocking {
        // Discriminator for the tmp+rename itself: writing straight to the
        // final name (and cleaning up in a catch) survives an in-process
        // IOException, but not a KILL — the process dies between the partial
        // write and the catch, leaving a valid-looking `.json`. Inside the
        // writer block the document must therefore exist ONLY under `.tmp`.
        val dir = tempSnapshotDir("inflight")
        try {
            var sawFinalName = false
            var tmpCount = -1
            ConfigBackup.writeSnapshotStreaming(dir) { w ->
                sawFinalName = ConfigBackup.listSnapshots(dir).isNotEmpty()
                tmpCount = (dir.listFiles() ?: emptyArray()).count { it.name.endsWith(".json.tmp") }
                w.write("{\"format\":\"openminis.config.backup\"}")
            }
            assertTrue(
                "the final *.json name must not exist while the document is still streaming — " +
                    "a kill here would leave a truncated file that the rollback list accepts",
                !sawFinalName,
            )
            assertEquals("the in-flight document must live in exactly one .json.tmp", 1, tmpCount)
            assertEquals(1, ConfigBackup.listSnapshots(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed snapshot leaves the previous rollback point intact`() = runBlocking {
        val dir = tempSnapshotDir("keep")
        try {
            val good = ConfigBackup.writeSnapshot(dir, "{\"good\":true}")
            assertEquals("{\"good\":true}", good.readText())
            try {
                ConfigBackup.writeSnapshotStreaming(dir) { w ->
                    w.write("{\"half\":")
                    throw java.io.IOException("killed")
                }
            } catch (_: java.io.IOException) {
            }
            val left = ConfigBackup.listSnapshots(dir)
            assertEquals("exactly the pre-existing snapshot survives", 1, left.size)
            assertEquals(good.name, left[0].name)
            assertEquals("{\"good\":true}", left[0].readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `two snapshots in the same second get distinct names`() {
        val dir = tempSnapshotDir("same-second")
        try {
            val now = 1_700_000_000_000L
            val first = ConfigBackup.allocateSnapshotFile(dir, now)
            first.writeText("first")
            val second = ConfigBackup.allocateSnapshotFile(dir, now)
            assertEquals(
                "the second name must be the first name plus a sequence suffix, never the " +
                    "same path (same path = the second write truncates the first)",
                first.name.removeSuffix(".json") + "-2.json",
                second.name,
            )
            second.writeText("second")
            assertEquals("first", first.readText())
            assertEquals("second", second.readText())
            // Third collision keeps counting instead of reusing "-2".
            val third = ConfigBackup.allocateSnapshotFile(dir, now)
            assertEquals(first.name.removeSuffix(".json") + "-3.json", third.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a clock rollback cannot prune the snapshot just written`() {
        val dir = tempSnapshotDir("rollback")
        try {
            // The previous snapshot carries a FUTURE mtime: exactly what a
            // restore looks like after the clock jumped backwards. Pruning by
            // mtime alone deleted the file this run had just produced.
            val older = ConfigBackup.writeSnapshot(dir, "{\"older\":true}")
            older.setLastModified(System.currentTimeMillis() + 3_600_000L)
            val fresh = ConfigBackup.writeSnapshot(dir, "{\"fresh\":true}")
            assertTrue("the snapshot just written must survive pruning", fresh.exists())
            assertEquals("{\"fresh\":true}", fresh.readText())
            assertEquals(
                listOf(fresh.name),
                ConfigBackup.listSnapshots(dir).map { it.name },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `temp sidecars are invisible to the listing and swept once stale`() {
        val dir = tempSnapshotDir("sweep")
        try {
            val stale = java.io.File(dir, "rikkaminis-snapshot-20200101-000000.json.tmp")
            stale.writeText("{half")
            stale.setLastModified(System.currentTimeMillis() - ConfigBackup.SNAPSHOT_TMP_STALE_MS - 1000L)
            val live = java.io.File(dir, "rikkaminis-snapshot-20200101-000001.json.tmp")
            live.writeText("{half")
            assertEquals(
                "a .json.tmp must never be listed as a rollback point, even when empty of data",
                emptyList<String>(),
                ConfigBackup.listSnapshots(dir).map { it.name },
            )
            ConfigBackup.writeSnapshot(dir, "{}")
            assertTrue("a killed write's stale temp is swept on the next write", !stale.exists())
            assertTrue("a fresh temp is never swept (a concurrent write may own it)", live.exists())
            assertEquals(1, ConfigBackup.listSnapshots(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
