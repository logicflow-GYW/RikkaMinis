package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Wiring probes for [T-android-markdown-table-copy-actions] + FIX-4 B-02
 * (deep-audit-1037).
 *
 * A table publishes Copy Table / Copy Table Image to the [SelectionController]
 * so ONE selection toolbar appends them after Copy / Copy Markdown / etc. The
 * wiring was half-done: `selectionTableActions()` was called from
 * `MinisMarkdownTextToolbar` only — the main-chat
 * `MinisSelectionToolbarHost` (the long-press toolbar chat users actually see)
 * never surfaced the actions. On top of that the registry was keyed by the
 * bare messageId, so with two tables in one message the later overwrote the
 * earlier and a table leaving composition dropped its sibling's entry.
 *
 * Compose screens and a live SelectionController cannot be exercised on the
 * JVM (no Robolectric), so the wiring is asserted against the sources — same
 * probe shape as `FastioWiringTest`, with a negative control per probe.
 */
class MinisSelectionTableTest {

    private val gestureFile = "src/main/java/com/rikkaminis/app/ui/chat/MinisTextKitGesture.kt"
    private val selectionFile = "src/main/java/com/rikkaminis/app/ui/chat/MinisTextKitSelection.kt"
    private val streamFile = "src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt"
    private val mdToolbarFile = "src/main/java/com/rikkaminis/app/ui/chat/MinisMarkdownTextToolbar.kt"

    // ── the probes ───────────────────────────────────────────────────────────

    /** The main-chat long-press toolbar must surface the table actions. */
    private fun assertMainChatToolbarWired(gesture: String) {
        val host = bodyOf(gesture, "fun MinisSelectionToolbarHost(")
        assertTrue("MinisSelectionToolbarHost must exist", host.isNotEmpty())
        assertTrue(
            "the main-chat toolbar must resolve the table actions from the controller — " +
                "without this the long-press toolbar never shows Copy Table",
            host.contains("selectionTableActions()"),
        )
        assertTrue(
            "both table buttons must call the published actions",
            host.contains("copyTableMarkdown()") && host.contains("copyTableImage()"),
        )
    }

    /** The markdown-toolbar path is a legitimate consumer and must stay. */
    private fun assertMarkdownToolbarStillConsumes(mdToolbar: String) {
        assertTrue(
            "MinisMarkdownTextToolbar must keep consuming selectionTableActions() — " +
                "removing it is a regression, not a cleanup",
            mdToolbar.contains("selectionTableActions()"),
        )
    }

    /** The registry key must carry the table dimension, and the publisher must pass it. */
    private fun assertTableLevelKey(selection: String, stream: String) {
        assertTrue(
            "rememberTableActions must take a table-level key beyond messageId",
            Regex("""fun rememberTableActions\(messageId: String,\s*tableIndex: Int,""")
                .containsMatchIn(selection),
        )
        assertTrue(
            "forgetTableActions must take the table key too — a bare messageId remove " +
                "takes a still-composed sibling table's entry",
            Regex("""fun forgetTableActions\(messageId: String,\s*tableIndex: Int\)""")
                .containsMatchIn(selection),
        )
        assertTrue(
            "the registry declaration must be two-level (an Int table dimension inside " +
                "the per-message map)",
            Regex("""SnapshotStateMap<Int,\s*TableActions>""").containsMatchIn(selection),
        )
        assertTrue(
            "RenderTable must publish under the table key",
            Regex("""rememberTableActions\(messageId,\s*tableIndex,\s*actions\)""")
                .containsMatchIn(stream),
        )
        assertTrue(
            "RenderTable must source the table index from the shard allocator",
            stream.contains("LocalShardSubIndexAllocator.current"),
        )
    }

    /** Cross-message selections expose no single-table actions. */
    private fun assertCrossMessageExcluded(selection: String) {
        val fn = bodyOf(selection, "fun selectionTableActions(")
        assertTrue("selectionTableActions must exist", fn.isNotEmpty())
        assertTrue(
            "a selection spanning messages must not expose table actions — the guard " +
                "on singleMessageId() is the contract",
            fn.contains("singleMessageId()"),
        )
        assertTrue(
            "the table under the selection must be resolved from the selection's shard",
            fn.contains("tableIndexFromShard("),
        )
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `main-chat toolbar surfaces the table copy actions`() {
        assertMainChatToolbarWired(source(gestureFile))
    }

    @Test
    fun `markdown toolbar keeps consuming the table actions`() {
        assertMarkdownToolbarStillConsumes(source(mdToolbarFile))
    }

    @Test
    fun `the table registry is keyed per table and the publisher passes the index`() {
        assertTableLevelKey(source(selectionFile), source(streamFile))
    }

    @Test
    fun `cross-message selections expose no table actions`() {
        assertCrossMessageExcluded(source(selectionFile))
    }

    @Test
    fun `negative control - unwiring the main-chat toolbar fails the wiring probe`() {
        val original = source(gestureFile)
        val mutated = original.replace(
            "val tableActions = controller.selectionTableActions()",
            "val tableActions: SelectionController.TableActions? = null",
        )
        Assume.assumeTrue("the wiring must exist to be removable", mutated != original)
        var failed = false
        try {
            assertMainChatToolbarWired(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the wiring probe must fail once the toolbar stops resolving actions", failed)
    }

    @Test
    fun `negative control - reverting the registry key to bare messageId fails the key probe`() {
        val original = source(selectionFile)
        val mutated = original
            .replace("fun rememberTableActions(messageId: String, tableIndex: Int,", "fun rememberTableActions(messageId: String,")
            .replace("fun forgetTableActions(messageId: String, tableIndex: Int)", "fun forgetTableActions(messageId: String)")
            .replace("SnapshotStateMap<Int, TableActions>", "TableActions")
        Assume.assumeTrue("the table key must exist to be reverted", mutated != original)
        var failed = false
        try {
            assertTableLevelKey(mutated, source(streamFile))
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the key probe must fail on a bare-messageId registry", failed)
    }

    @Test
    fun `negative control - a cross-message-blind resolver fails the exclusion probe`() {
        val original = source(selectionFile)
        // Mutate the ORIGINAL text (bodyOf returns a comment/string-blanked
        // slice, which is not a substring of the original). The guard literal
        // also appears in selectionMessageMarkdown(); mutating both is fine —
        // the probe reads the selectionTableActions body only.
        val mutated = original
            .replace("val msgId = singleMessageId() ?: return null", "val msgId = \"any\"")
            .replace("tableIndexFromShard(", "unusedTableIndexFromShard(")
        Assume.assumeTrue("the guard must exist to be removed", mutated != original)
        var failed = false
        try {
            assertCrossMessageExcluded(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the exclusion probe must fail once the single-message guard is gone", failed)
    }

    // ── [F-02 / F-09 meta-audit-1028] selection copy + document order ────────
    //
    // Report B04 F-02: the shard order key parsed the trailing integer of the
    // WHOLE id, and MdText appends its slot index with '#', so the key was
    // null for every production shard — endpoints lost their ordering as soon
    // as one scrolled out of the viewport, and a table row (one shared y) had
    // every column counted as "between". F-09: the non-shard-block test still
    // listed `|---|` tables (whose cells DO register shards) and the anchor
    // search ran from md[0], so a copy could start at the message's preface.
    //
    // The decision cores are top-level pure functions precisely so they can be
    // driven here (a live SelectionController needs a TextLayoutResult, which
    // cannot be built on the JVM) — same split as composerCommitCaret().

    @Test
    fun `shardOrderKey strips the sub-index suffix and reads the block ordinal`() {
        assertEquals(ShardOrderKey(space = "mdblock:12", block = 3, sub = 2), shardOrderKey("mdblock:12:3#2"))
        assertEquals(ShardOrderKey(space = "mdblock:12", block = 0, sub = 0), shardOrderKey("mdblock:12:0#0"))
        // Legacy whole-message path: no block segment at all, sub-index only.
        assertEquals(ShardOrderKey(space = "legacy-text", block = null, sub = 4), shardOrderKey("legacy-text#4"))
        assertEquals(ShardOrderKey(space = "text:b7f3c1", block = null, sub = 2), shardOrderKey("text:b7f3c1#2"))
        assertEquals(ShardOrderKey(space = "3", block = null, sub = 0), shardOrderKey("3#0"))
        // Table cells get NO key: "tbl:<i>" counts tables inside one fragment, so
        // two tables in different fragments both spell "tbl:0" — an id that cannot
        // order them. They order by geometry (window y, then x) instead, which is
        // also what splits the columns of a row.
        assertNull(shardOrderKey("tbl:0#1"))
        assertNull(shardOrderKey("tbl:0"))
        // No suffix AND no numeric block segment → no document position at all.
        assertNull(shardOrderKey("3"))
        assertNull(shardOrderKey("legacy"))
        assertNull(shardOrderKey("text:b7f3c1"))
        // The pre-fix arithmetic, pinned: the trailing segment of a suffixed id
        // is never an Int, which is why the old key was null for 100% of the
        // shards the app produces. If a "simplification" goes back to it, the
        // assertions above fail instead of the bug returning silently.
        assertNull("0#1".toIntOrNull())
    }

    @Test
    fun `compareShardOrder orders inside one space and refuses across spaces`() {
        val a3 = ShardOrderKey("mdblock:12", 3, 0)
        assertTrue(compareShardOrder(ShardOrderKey("mdblock:12", 3, 9), ShardOrderKey("mdblock:12", 5, 0))!! < 0)
        assertTrue(compareShardOrder(ShardOrderKey("mdblock:12", 3, 2), ShardOrderKey("mdblock:12", 3, 7))!! < 0)
        assertTrue(compareShardOrder(a3, ShardOrderKey("mdblock:12", 3, 0)) == 0)
        // Sub-index decides when neither side has a block ordinal but they share
        // one space.
        assertTrue(compareShardOrder(ShardOrderKey("legacy-text", null, 2), ShardOrderKey("legacy-text", null, 9))!! < 0)
        // Same ordinal, different fragment: each streaming text block numbers its
        // own fragments from 0, so "A:3" and "B:3" have no order between them —
        // inventing one is how a middle shard gets unpainted.
        assertNull(compareShardOrder(ShardOrderKey("mdblock:A", 3, 0), ShardOrderKey("mdblock:B", 3, 0)))
        // A legacy fragment and a numbered block likewise.
        assertNull(compareShardOrder(ShardOrderKey("legacy-text", null, 1), a3))
        assertNull(compareShardOrder(a3, ShardOrderKey("legacy-text", null, 1)))
    }

    @Test
    fun `same table row is ordered by x, not by the shared y`() {
        val col1 = ShardOrderPosition(y = 300f, x = 0f)
        val col2 = ShardOrderPosition(y = 300f, x = 100f)
        val col3 = ShardOrderPosition(y = 300f, x = 300f)
        val col4 = ShardOrderPosition(y = 300f, x = 400f)
        assertTrue("a cell between columns 2 and 3 belongs to the span", isPositionBetween(col2, col3, ShardOrderPosition(300f, 150f)))
        assertFalse("column 1 of the same row is outside", isPositionBetween(col2, col3, col1))
        assertFalse("column 4 of the same row is outside", isPositionBetween(col2, col3, col4))
        // The pre-fix predicate was `ym in minOf(ya, yb)..maxOf(ya, yb)`, and
        // every cell of a row shares one y — so it answered true for the whole
        // row. Pinned as a literal so the y-only shape cannot come back.
        assertTrue(300f in minOf(300f, 300f)..maxOf(300f, 300f))
        // Rows still order vertically.
        assertTrue(isPositionBetween(ShardOrderPosition(300f, 400f), ShardOrderPosition(500f, 0f), ShardOrderPosition(400f, 900f)))
        assertFalse(isPositionBetween(ShardOrderPosition(300f, 400f), ShardOrderPosition(500f, 0f), ShardOrderPosition(100f, 0f)))
    }

    @Test
    fun `an endpoint with no position still orders by document key`() {
        // posA == null is the recycled-off-screen endpoint. The document key is
        // the only ordering evidence left; dropping to "undecidable" here is
        // what used to collapse the whole selection.
        assertEquals(true, endpointComesFirst(ShardOrderKey("p", 0, 0), ShardOrderKey("p", 4, 0), null, null))
        assertEquals(false, endpointComesFirst(ShardOrderKey("p", 4, 0), ShardOrderKey("p", 0, 0), null, null))
        // Different spaces → the key cannot answer; the caller must use geometry.
        assertNull(endpointComesFirst(ShardOrderKey("p", 0, 0), ShardOrderKey("q", 4, 0), null, null))
        // Without a key AND without a position there is nothing to order by.
        assertNull(endpointComesFirst(null, null, null, ShardOrderPosition(0f, 0f)))
        // Keyless ids (legacy / text:<uuid>) still order by position.
        assertEquals(true, endpointComesFirst(null, null, ShardOrderPosition(100f, 0f), ShardOrderPosition(200f, 0f)))
        assertEquals(false, endpointComesFirst(null, null, ShardOrderPosition(200f, 0f), ShardOrderPosition(100f, 0f)))
    }

    @Test
    fun `isBetweenEndpoints keeps painting with a recycled endpoint and splits a row by x`() {
        val a = ShardOrderKey("p", 0, 0)
        val b = ShardOrderKey("p", 4, 0)
        // Recycled endpoints (no positions) but document order → still decided.
        assertTrue(isBetweenEndpoints(a, b, ShardOrderKey("p", 2, 0), null, null, null))
        assertFalse(isBetweenEndpoints(a, b, ShardOrderKey("p", 6, 0), null, null, null))
        // Same call with the middle's shard also unregistered: geometry is gone
        // but the order key is not.
        assertTrue(isBetweenEndpoints(a, b, ShardOrderKey("p", 1, 3), null, null, null))
        // No keys, no positions → not "between" (nothing to decide with).
        assertFalse(isBetweenEndpoints(null, null, null, null, null, null))
        assertFalse(isBetweenEndpoints(null, null, null, ShardOrderPosition(100f, 0f), null, ShardOrderPosition(300f, 0f)))
        // Table cells: no cross-space order, so geometry decides and x splits
        // the row (a y-only range would have said true for all three).
        val cell1 = ShardOrderPosition(300f, 0f)
        val cell2 = ShardOrderPosition(300f, 100f)
        val cell3 = ShardOrderPosition(300f, 200f)
        assertTrue(isBetweenEndpoints(null, null, null, cell2, cell3, ShardOrderPosition(300f, 150f)))
        assertFalse(isBetweenEndpoints(null, null, null, cell2, cell3, cell1))
        // Two endpoints that land on the same slot carry no ordering → geometry.
        assertTrue(isBetweenEndpoints(a, a, a, cell2, cell3, ShardOrderPosition(300f, 150f)))
        assertFalse(isBetweenEndpoints(a, a, a, cell2, cell3, cell1))
    }

    @Test
    fun `the controllers ordering and copy paths are wired to those cores`() {
        val sel = source(selectionFile)
        val ordered = bodyOf(sel, "fun orderedEndpoints(")
        assertTrue("orderedEndpoints must consult the document key", ordered.contains("endpointComesFirst("))
        assertTrue(
            "orderedEndpoints must fall back to the last known position — a registered-only " +
                "lookup is what made an off-screen endpoint collapse the selection",
            ordered.contains("windowPositionOf("),
        )
        val between = bodyOf(sel, "internal fun SelectionController.isShardBetween(")
        assertTrue("isShardBetween must delegate to the testable core", between.contains("isBetweenEndpoints("))
        val unregister = bodyOf(sel, "fun unregister(")
        assertTrue("unregister must capture the position before dropping the shard", unregister.contains("lastKnownPosition["))
        assertTrue("the shard entry must still be removed", unregister.contains("shards.remove(id)"))
        assertTrue(
            "the capture has to happen BEFORE the removal, or it reads a detached shard",
            unregister.indexOf("lastKnownPosition[") < unregister.indexOf("shards.remove(id)"),
        )
        assertTrue("windowPositionOf must answer from the cached position", bodyOf(sel, "internal fun windowPositionOf(").contains("lastKnownPosition[id]"))
        assertTrue("clearSelection must drop the selection-scoped cache", bodyOf(sel, "fun clearSelection(").contains("lastKnownPosition.clear()"))
        assertTrue(
            "registeredShardsInOrder must break y ties on x — a row's cells share one y",
            declSnippet(sel, "private fun registeredShardsInOrder(").contains("thenBy"),
        )
        // F-09 ①: tables are shard-backed, so the delimiter test must be gone;
        // display math has no shard at all and must be recognised.
        val crosses = bodyOf(sel, "private fun sliceCrossesNonShardBlock(")
        assertFalse("a table's cells DO register shards — the delimiter test must be gone", crosses.contains("tableDelimiter"))
        assertTrue("display math (image, no shard) must be recovered", crosses.contains("slice.contains("))
        assertTrue(
            "display math is spelled `\\\\[` in markdown",
            rawBody(sel, "private fun sliceCrossesNonShardBlock(").contains("slice.contains(\"\\\\[\")"),
        )
        // F-09 ②: the head anchor must not be searched from offset 0.
        val splice = bodyOf(sel, "private fun spliceNonShardSpan(")
        assertTrue("the anchor search must start at the preceding shards' offset", splice.contains("rawSearchFloor("))
        assertTrue("...and that floor must be passed to indexOf", splice.contains("md.indexOf(headAnchor, searchFrom)"))
    }

    @Test
    fun `negative control - a global anchor search fails the wiring probe`() {
        val original = source(selectionFile)
        val mutated = original.replace("md.indexOf(headAnchor, searchFrom)", "md.indexOf(headAnchor)")
        Assume.assumeTrue("the bounded search must exist to be removed", mutated != original)
        var failed = false
        try {
            assertSpliceBounded(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the probe must fail once the anchor is searched from offset 0 again", failed)
    }

    @Test
    fun `negative control - restoring the table delimiter test fails the wiring probe`() {
        val original = source(selectionFile)
        val mutated = original.replace(
            "        return slice.contains(\"\\\\[\")",
            "        val tableDelimiter = Regex(\"\"\"(?m)^\\s*\\|?\\s*:?-{3,}.*\\|\"\"\")\n" +
                "        return tableDelimiter.containsMatchIn(slice)",
        )
        Assume.assumeTrue("the display-math branch must exist to be replaced", mutated != original)
        var failed = false
        try {
            val crosses = bodyOf(mutated, "private fun sliceCrossesNonShardBlock(")
            assertFalse(crosses.contains("tableDelimiter"))
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the probe must fail once tables are treated as non-shard blocks again", failed)
    }

    @Test
    fun `negative control - dropping the recycled-endpoint cache fails the wiring probe`() {
        val original = source(selectionFile)
        val mutated = original
            .replace("lastKnownPosition[id] = ShardOrderPosition(y = p.y, x = p.x)", "Unit")
            .replace("return lastKnownPosition[id]", "return null")
        Assume.assumeTrue("the cache must exist to be removed", mutated != original)
        var failed = false
        try {
            assertRecycleCacheWired(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the probe must fail once an off-screen endpoint has no remembered position", failed)
    }

    private fun assertSpliceBounded(selection: String) {
        val splice = bodyOf(selection, "private fun spliceNonShardSpan(")
        assertTrue("the anchor search must start at the preceding shards' offset", splice.contains("rawSearchFloor("))
        assertTrue("...and that floor must be passed to indexOf", splice.contains("md.indexOf(headAnchor, searchFrom)"))
    }

    private fun assertRecycleCacheWired(selection: String) {
        assertTrue(
            "unregister must capture the position before dropping the shard",
            bodyOf(selection, "fun unregister(").contains("lastKnownPosition["),
        )
        assertTrue(
            "windowPositionOf must answer from the cached position",
            bodyOf(selection, "internal fun windowPositionOf(").contains("lastKnownPosition[id]"),
        )
        assertTrue(
            "orderedEndpoints must consult it",
            bodyOf(selection, "fun orderedEndpoints(").contains("windowPositionOf("),
        )
    }

    // ── source helpers ───────────────────────────────────────────────────────
    private fun source(rel: String): String = sourceFile(rel).readText()

    /**
     * Comment- and string-blanked copy of [src] with LENGTH AND OFFSETS
     * PRESERVED — a `${…}` template or a `//` inside a URL would otherwise
     * desync brace counting (the B-01 branch carries a `${cmd.id}` literal).
     * Because blanking keeps indices aligned, slices can be taken from the
     * original text using offsets found in the skeleton.
     */
    private fun skeleton(src: String): String {
        val out = StringBuilder(src.length)
        val stack = mutableListOf('c' to 0)
        var i = 0
        while (i < src.length) {
            val frame = stack.last()
            if (frame.first == 'c') {
                if (src.startsWith("\"\"\"", i)) {
                    out.append("   "); i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < src.length) { out.append("   "); i += 3 }
                    continue
                }
                val c = src[i]
                if (c == '"') { stack.add('s' to 0); out.append('"'); i++; continue }
                if (src.startsWith("//", i)) {
                    while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                    continue
                }
                if (src.startsWith("/*", i)) {
                    out.append("  "); i += 2
                    while (i < src.length && !src.startsWith("*/", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < src.length) { out.append("  "); i += 2 }
                    continue
                }
                if (c == '{') { stack[stack.lastIndex] = 'c' to (frame.second + 1); out.append(c); i++; continue }
                if (c == '}') {
                    if (frame.second == 0 && stack.size > 1) { stack.removeAt(stack.lastIndex); out.append(c); i++; continue }
                    stack[stack.lastIndex] = 'c' to (frame.second - 1); out.append(c); i++; continue
                }
                out.append(c); i++
                continue
            }
            val c = src[i]
            if (c == '\\') { out.append("  "); i += 2; continue }
            if (src.startsWith("${'$'}{", i)) { stack.add('c' to 0); out.append("${'$'}{"); i += 2; continue }
            if (c == '"') { stack.removeAt(stack.lastIndex); out.append('"'); i++; continue }
            out.append(if (c == '\n') '\n' else ' '); i++
        }
        return out.toString()
    }

    /**
     * Code-only `{ … }` body of the declaration containing [marker]. Comments
     * and string contents are blanked (offsets preserved), so NEITHER prose (a
     * KDoc naming the very call under test) NOR a log literal can satisfy an
     * assertion. The parameter list is skipped — a default lambda in the
     * signature (`clampToViewportBounds: () -> Rect? = { null }`) would
     * otherwise win the "first brace" race.
     */
    private fun bodyOf(text: String, marker: String): String {
        val skel = skeleton(text)
        val idx = skel.indexOf(marker)
        if (idx == -1) return ""
        var i = skel.indexOf('(', idx)
        if (i == -1) return ""
        var depth = 0
        while (i < skel.length) {
            when (skel[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        return braceSlice(skel, skel.indexOf('{', i + 1))
    }

    private fun braceSlice(skel: String, start: Int): String {
        if (start == -1) return ""
        var depth = 0
        var i = start
        while (i < skel.length) {
            when (skel[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return skel.substring(start, i + 1)
                }
            }
            i++
        }
        return skel.substring(start)
    }

    /**
     * Code-only `{ … }` body of [marker] taken from the ORIGINAL text (string
     * literals intact). Needed for assertions about the literals themselves —
     * [bodyOf] blanks them, so `slice.contains("\\[")` is unreadable there.
     * Comment prose is still excluded by construction: the offsets come from
     * the blanked skeleton, and a comment inside the body is not code, but it
     * DOES survive in this raw slice — so only assert on literals that a
     * comment cannot plausibly spell.
     */
    /**
     * [marker]'s declaration up to the first blank line. Needed for members
     * written with an expression body (`= shards.values.sortedWith(...)`),
     * where [bodyOf] finds no brace of its own and slices the NEXT function.
     */
    private fun declSnippet(text: String, marker: String): String {
        val start = text.indexOf(marker)
        if (start == -1) return ""
        val end = text.indexOf("\n\n", start)
        return if (end == -1) text.substring(start) else text.substring(start, end)
    }

    private fun rawBody(text: String, marker: String): String {
        val skel = skeleton(text)
        val idx = skel.indexOf(marker)
        if (idx == -1) return ""
        var i = skel.indexOf('(', idx)
        if (i == -1) return ""
        var depth = 0
        while (i < skel.length) {
            when (skel[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        val start = skel.indexOf('{', i + 1)
        if (start == -1) return ""
        var braces = 0
        var j = start
        while (j < skel.length) {
            when (skel[j]) {
                '{' -> braces++
                '}' -> {
                    braces--
                    if (braces == 0) return text.substring(start, j + 1)
                }
            }
            j++
        }
        return text.substring(start)
    }

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }
}
