package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [meta-audit-1028 F-08 / F-09 / F-10] Three defect surfaces, three layers of
 * evidence:
 *
 *  1. **behaviour, pure** — the record decisions ([memoryWriteRecordContent])
 *     and the screenshot filename are pure functions, so the exact truth table
 *     is asserted directly;
 *  2. **behaviour, IO split** — [readAutoInjectedFiles] takes its reads as
 *     functions, so the read SET and the today/yesterday derivation run on the
 *     JVM with a fake map (the two parts that break silently: a wrong date
 *     window labels yesterday's log "today", a dropped read hides a file);
 *  3. **wiring** — the halves that cannot be driven without a Compose runtime
 *     (the snapshot read must leave composition; the save failure must reach
 *     the editor; every op-log row must carry its own label) are asserted
 *     against the compiled source text, the `OffloadPermissionResetTest` probe
 *     shape. Every probe has a negative control built from the PRE-fix snippet:
 *     without one, a deleted line shows up as a green test that checks nothing.
 *
 * Every expected value is a literal, never derived from the code under test.
 */
class MemoryRecordScreenshotAndSheetIoTest {

    // ── F-09 ①: the body a write record may claim it wrote ────────────────

    @Test
    fun `writtenContent is kept only for a successful non-blank write`() {
        assertEquals("note body", memoryWriteRecordContent(success = true, content = "note body"))
        assertNull(memoryWriteRecordContent(success = true, content = ""))
        assertNull(memoryWriteRecordContent(success = true, content = "   \n\t "))
        assertNull(memoryWriteRecordContent(success = false, content = "note body"))
        assertNull(memoryWriteRecordContent(success = false, content = ""))
    }

    @Test
    fun `a failed write has no body for Edit or Revoke to target`() {
        // The sheet's gate is `canEditOrRevoke = writtenContent != null`, so a
        // null body is what hides both buttons — no UI-side special case. The
        // failure used to land a success-shaped row whose Save/Revoke then hit
        // a body that was never on disk → NotFound.
        assertNull(memoryWriteRecordContent(false, "the note that never landed"))
    }

    // ── F-09 ②: each op kind names itself ────────────────────────────────

    @Test
    fun `op labels are three distinct constants`() {
        assertEquals("memory_write", MEMORY_TOOL_WRITE)
        assertEquals("memory_get", MEMORY_TOOL_GET)
        assertEquals("memory_rollup", MEMORY_TOOL_ROLLUP)
    }

    /** The op-log record, assembled from the same pieces the executors use. */
    private fun recordFor(toolName: String, isWrite: Boolean, written: String?): MemoryToolRecord =
        MemoryToolRecord(
            title = "t",
            isWrite = isWrite,
            preview = "",
            output = "o",
            writtenContent = written,
            toolName = toolName,
        )

    @Test
    fun `rollup row keeps the read-only routing flag but is labelled rollup`() {
        val rollup = recordFor(MEMORY_TOOL_ROLLUP, isWrite = false, written = null)
        // isWrite = false is the ROUTING discriminator (Write detail would be an
        // empty page — rollup has no per-entry body); the LABEL must not follow
        // it, or the panel claims a read-only search for a tool that rewrote
        // MEMORY-ROLLUP.md.
        assertFalse(rollup.isWrite)
        assertEquals("memory_rollup", rollup.toolName)

        val write = recordFor(MEMORY_TOOL_WRITE, isWrite = true, written = "note")
        val get = recordFor(MEMORY_TOOL_GET, isWrite = false, written = null)
        val labels = listOf(write.toolName, get.toolName, rollup.toolName)
        // Three rows, three pills — the pre-fix shape collapsed rollup into get.
        assertEquals(labels.toSet().size, labels.size)
        assertEquals(listOf("memory_write", "memory_get", "memory_rollup"), labels)
    }

    // ── F-10: screenshot filenames never alias ───────────────────────────

    @Test
    fun `screenshot filename is millisecond timestamp plus sequence`() {
        assertEquals(
            "screenshot_1772000000123_7.jpg",
            browserScreenshotFilename(1_772_000_000_123L, 7L),
        )
    }

    @Test
    fun `two screenshots in the same millisecond get different names`() {
        val ts = 1_772_000_000_000L
        val first = browserScreenshotFilename(ts, nextBrowserScreenshotSequence())
        val second = browserScreenshotFilename(ts, nextBrowserScreenshotSequence())
        assertTrue("same-millisecond screenshots collided: $first", first != second)
        // …and the counter is strictly increasing, so even a clock that steps
        // backwards cannot re-alias an older file.
        val a = nextBrowserScreenshotSequence()
        val b = nextBrowserScreenshotSequence()
        assertTrue("sequence is not monotonic: $a then $b", b > a)
    }

    @Test
    fun `screenshot filename embeds milliseconds not seconds`() {
        val name = browserScreenshotFilename(1_772_000_000_123L, 1L)
        // 13 digits = epoch millis. The old shape was "/ 1000" → 10 digits, and
        // two calls inside one second produced ONE file.
        assertEquals("1772000000123", name.removePrefix("screenshot_").substringBefore('_'))
        assertTrue("not a .jpg: $name", name.endsWith(".jpg"))
    }

    // ── F-08: the snapshot read set + date window ────────────────────────

    /** Local-time start-of-day + 10 min, so "today"/"yesterday" are TZ-stable. */
    private fun localMillis(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    @Test
    fun `reads exactly the four snapshot files for the current local day`() {
        val names = mutableListOf<String>()
        val files = readAutoInjectedFiles(
            readFile = { name ->
                names.add(name)
                "body of $name"
            },
            readGlobalMd = { "global body" },
            nowMillis = localMillis(2026, 3, 1) + 600_000L,
            maxInjectLines = 120,
        )
        // Literal read set: SOUL first, then today, then yesterday — no other
        // file may be dragged onto the IO pass, and none may be dropped.
        assertEquals(listOf("SOUL.md", "2026-03-01.md", "2026-02-28.md"), names)
        assertEquals("body of SOUL.md", files.soulMd)
        assertEquals("global body", files.globalMd)
        assertEquals("2026-03-01", files.todayName)
        assertEquals("2026-02-28", files.yesterdayName)
        assertEquals("body of 2026-03-01.md", files.todayContent)
        assertEquals("body of 2026-02-28.md", files.yesterdayContent)
    }

    @Test
    fun `date window crosses the month boundary and carries the injection cap`() {
        val files = readAutoInjectedFiles(
            readFile = { "" },
            readGlobalMd = { "" },
            nowMillis = localMillis(2026, 3, 1) + 600_000L,
            maxInjectLines = 0,
        )
        // Not "2026-03-00": the window is calendar-correct across a month edge.
        assertEquals("2026-03-01", files.todayName)
        assertEquals("2026-02-28", files.yesterdayName)
        // The cap is read ON THE IO PASS and travels with the snapshot, so the
        // assembly step never reads prefs (or anything else) on the main thread.
        assertEquals(0, files.maxInjectLines)

        val leap = readAutoInjectedFiles(
            readFile = { "" },
            readGlobalMd = { "" },
            nowMillis = localMillis(2028, 3, 1) + 600_000L,
            maxInjectLines = 120,
        )
        assertEquals("2028-02-29", leap.yesterdayName)
        assertEquals(120, leap.maxInjectLines)
    }

    // ── F-08 / F-09 wiring: the sources themselves ─────────────────────────

    private fun readSource(rel: String): String {
        val f = File(rel)
        assertTrue("missing source (cwd = ${File(".").absolutePath}): $rel", f.exists())
        return f.readText()
    }

    private val sheetSource: String by lazy {
        readSource("src/main/java/com/rikkaminis/app/ui/chat/SessionMemorySheet.kt")
    }

    private val executorSource: String by lazy {
        readSource("src/main/java/com/rikkaminis/app/ui/chat/ChatToolExecutors.kt")
    }

    /** The PRE-fix shapes, kept verbatim as the negative controls' payload. */
    private val preFixCompositionRead =
        "    val autoItems = remember(memoryRepository, context, autoItemsRefreshTick) {\n" +
            "        buildAutoInjectedItems(context, memoryRepository)\n" +
            "    }\n"

    private val preFixSilentCatch =
        "                        } catch (_: Exception) { " +
            "/* fall through; UI toast omitted on failure */ }\n"

    private val preFixPillFromIsWrite =
        "    val opLabel = if (record.isWrite) \"memory_write\" else \"memory_get\"\n"

    private fun ioProbe(src: String): List<String> {
        val problems = mutableListOf<String>()
        if (src.contains(preFixCompositionRead)) {
            problems.add("the 4-file read is back inside composition remember")
        }
        if (!src.contains("LaunchedEffect(memoryRepository, autoItemsRefreshTick)")) {
            problems.add("no LaunchedEffect keyed on the refresh tick")
        }
        if (!Regex(
                "LaunchedEffect\\(memoryRepository, autoItemsRefreshTick\\)\\s*\\{\\s*" +
                    "autoFiles = withContext\\(Dispatchers\\.IO\\)",
            ).containsMatchIn(src)
        ) {
            problems.add("the snapshot read is not the first statement of the IO effect")
        }
        if (!src.contains("buildAutoInjectedItems(context, autoFiles)")) {
            problems.add("the assembly step no longer consumes the snapshot")
        }
        // The IO pass must read the four files AND the injection cap, and the
        // assembly (composition thread) must touch nothing else. One call site
        // (plus its declaration) = no second path that reads the repository.
        if (src.split("buildAutoInjectedItems(context, ").size - 1 != 1) {
            problems.add(
                "expected exactly 1 buildAutoInjectedItems(context, …) call site, found "
                    + (src.split("buildAutoInjectedItems(context, ").size - 1)
            )
        }
        if (!src.contains("readAutoInjectedFiles(\n                readFile = memoryRepository::readFile,")) {
            problems.add("the IO pass does not go through readAutoInjectedFiles")
        }
        // The tick must still key the remember that BUILDS the items (the gate's
        // contract: every in-sheet mutation rebuilds the list).
        if (!Regex(
                "remember\\([^)]*autoItemsRefreshTick[^)]*\\)\\s*\\{[^{}]*buildAutoInjectedItems",
            ).containsMatchIn(src)
        ) {
            problems.add("the item-building remember no longer keys on the refresh tick")
        }
        return problems
    }

    private fun saveErrorProbe(src: String): List<String> {
        val problems = mutableListOf<String>()
        if (src.contains(preFixSilentCatch)) {
            problems.add("the save catch is silent again")
        }
        if (src.contains("errorMessage = null,")) {
            problems.add("the editor is handed a hard-coded errorMessage = null")
        }
        if (!src.contains("errorMessage = editErrorMessage,")) {
            problems.add("the editor does not consume the error state")
        }
        if (!src.contains("editErrorMessage = e.message ?: e.toString()")) {
            problems.add("the caught exception message never reaches the error state")
        }
        if (!src.contains("android.util.Log.w(\n")) {
            problems.add("the save failure is not logged")
        }
        return problems
    }

    private fun pillProbe(sheet: String): List<String> {
        val problems = mutableListOf<String>()
        if (sheet.contains(preFixPillFromIsWrite)) {
            problems.add("the row pill is derived from isWrite again")
        }
        if (!sheet.contains("val opLabel = record.toolName")) {
            problems.add("the row pill does not come from the record's own toolName")
        }
        return problems
    }

    private fun recordSitesProbe(exec: String): List<String> {
        val problems = mutableListOf<String>()
        for (op in listOf("MEMORY_TOOL_WRITE", "MEMORY_TOOL_GET", "MEMORY_TOOL_ROLLUP")) {
            if (!exec.contains("toolName = $op,")) {
                problems.add("no record site labels itself $op")
            }
        }
        if (!exec.contains("writtenContent = memoryWriteRecordContent(result.success, content),")) {
            problems.add("the write record no longer gates its body on the write result")
        }
        return problems
    }

    @Test
    fun `sheet source keeps the read off the composition thread`() {
        assertEquals(emptyList<String>(), ioProbe(sheetSource))
    }

    @Test
    fun `sheet source surfaces a failed save to the editor`() {
        assertEquals(emptyList<String>(), saveErrorProbe(sheetSource))
    }

    @Test
    fun `sheet source labels each op-log row from the record itself`() {
        assertEquals(emptyList<String>(), pillProbe(sheetSource))
        assertEquals(emptyList<String>(), recordSitesProbe(executorSource))
    }

    @Test
    fun `negative control - pre-fix composition read fails the probe`() {
        val mutated = sheetSource.replace(
            "    var autoFiles by remember { mutableStateOf<AutoInjectedFiles?>(null) }",
            preFixCompositionRead + "    var autoFiles by remember { mutableStateOf<AutoInjectedFiles?>(null) }",
        )
        assertTrue("fixture drift: anchor not found", mutated != sheetSource)
        assertTrue(ioProbe(mutated).isNotEmpty())
    }

    @Test
    fun `negative control - pre-fix silent catch fails the probe`() {
        val mutated = sheetSource.replace(
            "                        } catch (e: Exception) {",
            preFixSilentCatch + "                        } catch (e: Exception) {",
        )
        assertTrue("fixture drift: anchor not found", mutated != sheetSource)
        assertTrue(saveErrorProbe(mutated).isNotEmpty())
    }

    @Test
    fun `negative control - pre-fix isWrite pill fails the probe`() {
        val mutated = sheetSource.replace(
            "    val opLabel = record.toolName\n",
            preFixPillFromIsWrite,
        )
        assertTrue("fixture drift: anchor not found", mutated != sheetSource)
        assertTrue(pillProbe(mutated).isNotEmpty())
    }
}
