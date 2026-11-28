package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whole-row long-press reorder across the eight settings reorder zones
 * (providers, model groups, agent-loop groups + entries, group members, chat
 * menu attach / overflow / footer) and the accessibility path that replaces
 * the removed drag handles.
 *
 * Two layers, the convention the other settings tests use:
 *  1. the shared pure ordering primitives, executed for real with hardcoded
 *     literals — a mutation cannot pass by moving the expectation with the
 *     implementation;
 *  2. the wiring shape, so a screen cannot keep the old handle-only gesture
 *     while the primitives stay green (and vice versa).
 *
 * The whole-row gesture lives on a Composable modifier and needs a real
 * Compose host, so the gesture itself is not executed here; the instrumented
 * half is declared but not run in this task (see the report).
 */
class LongPressOrderTest {

    // ── ① pure move: one slot, clamped, never mutates the input ──────────

    @Test
    fun `move down swaps with the next entry`() {
        assertEquals(
            listOf("a", "c", "b", "d"),
            moveEntryByDelta(listOf("a", "b", "c", "d"), "b", 1),
        )
    }

    @Test
    fun `move up swaps with the previous entry`() {
        assertEquals(
            listOf("a", "c", "b", "d"),
            moveEntryByDelta(listOf("a", "b", "c", "d"), "c", -1),
        )
    }

    @Test
    fun `moving past either end is refused, not clamped`() {
        // A refused move must let the caller answer "not performed" instead of
        // persisting an unchanged list.
        assertNull(moveEntryByDelta(listOf("a", "b"), "a", -1))
        assertNull(moveEntryByDelta(listOf("a", "b"), "b", 1))
    }

    @Test
    fun `unknown id and zero delta are refusals`() {
        assertNull(moveEntryByDelta(listOf("a", "b"), "z", 1))
        assertNull(moveEntryByDelta(listOf("a", "b"), "a", 0))
        assertNull(moveEntryByDelta(emptyList(), "a", 1))
    }

    @Test
    fun `a single item list cannot be reordered`() {
        assertNull(moveEntryByDelta(listOf("only"), "only", 1))
        assertNull(moveEntryByDelta(listOf("only"), "only", -1))
    }

    @Test
    fun `the input list is not mutated in place`() {
        val original = listOf("a", "b", "c")
        moveEntryByDelta(original, "a", 1)
        assertEquals(listOf("a", "b", "c"), original)
    }

    // ── ② section splice: other sections keep their rendered order ───────

    private val flat = listOf(
        "fav-1", "fav-2",        // favorites bucket
        "openai-1", "openai-2",  // one providerType bucket
        "gemini-1",              // another providerType bucket
    )

    @Test
    fun `a favorites move leaves every other section in place`() {
        assertEquals(
            listOf("fav-2", "fav-1", "openai-1", "openai-2", "gemini-1"),
            spliceSectionOrder(flat, listOf("fav-1", "fav-2"), 0, 1),
        )
    }

    @Test
    fun `a providerType move does not touch favourites or other types`() {
        assertEquals(
            listOf("fav-1", "fav-2", "openai-2", "openai-1", "gemini-1"),
            spliceSectionOrder(flat, listOf("openai-1", "openai-2"), 0, 1),
        )
    }

    @Test
    fun `an out-of-range section move is refused`() {
        assertNull(spliceSectionOrder(flat, listOf("fav-1", "fav-2"), 0, 2))
        assertNull(spliceSectionOrder(flat, listOf("fav-1", "fav-2"), -1, 0))
        assertNull(spliceSectionOrder(flat, listOf("fav-1", "fav-2"), 1, 1))
    }

    @Test
    fun `a section member missing from the flat list is refused`() {
        // The pre-condition a duplicated-id config would violate: splicing
        // would leave a section member unplaced and persist a damaged order.
        assertNull(spliceSectionOrder(flat, listOf("fav-1", "ghost"), 0, 1))
    }

    // ── ③ wiring: every zone uses the whole-row gesture ──────────────────

    private val dir = "src/android/app/src/main/java/com/rikkaminis/app/ui/settings"

    private fun code(rel: String): String {
        val viaAndroid = "src/android/app/$rel"
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            val d = cur
            val direct = File(d, rel)
            if (direct.isFile) return strip(direct.readText())
            val nested = File(d, viaAndroid)
            if (nested.isFile) return strip(nested.readText())
            cur = d.parentFile
        }
        throw IllegalStateException("cannot locate $rel — a source-shape arm must never skip")
    }

    /** Comment lines cannot satisfy an anchor. */
    private fun strip(src: String): String = src.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) " ".repeat(line.length) else line
    }

    private val screens = listOf(
        "ProviderListScreen.kt",
        "ModelGroupsScreen.kt",
        "AgentLoopModelsScreen.kt",
        "ModelGroupDetailScreen.kt",
        "ChatMenuSettingsScreen.kt",
    )

    @Test
    fun `the four lazy-list screens drag from the whole row`() {
        for (file in listOf(
            "ProviderListScreen.kt",
            "ModelGroupsScreen.kt",
            "AgentLoopModelsScreen.kt",
            "ModelGroupDetailScreen.kt",
        )) {
            val c = code("$dir/$file")
            assertTrue("$file: ReorderableItem must hand the drag state to the row", c.contains("{ isDragging ->"))
            assertTrue("$file: the row must use the shared long-press modifier", c.contains("longPressReorder(isDragging)"))
        }
    }

    @Test
    fun `no screen still renders a drag handle`() {
        for (file in screens) {
            val c = code("$dir/$file")
            assertTrue("$file: the visible handle must be gone", !c.contains("DragHandleButton("))
            assertTrue("$file: no handle-only drag path may remain", !c.contains(".draggableHandle()"))
            assertTrue("$file: no drag-handle icon may remain", !c.contains("Icons.Outlined.DragHandle") && !c.contains("Icons.Default.DragHandle"))
        }
    }

    @Test
    fun `normal and unavailable model names align with the Models heading without a leading slot`() {
        val members = code("$dir/ModelGroupDetailScreen.kt")
            .substringAfter("R.string.model_group_detail_models_header_count,", "")
            .substringBefore("R.string.model_group_detail_session_defaults,")
        assertTrue("keep the existing heading inset", members.contains("Modifier.padding(horizontal = 32.dp,"))
        val rows = members.split("ListItem(").drop(1)
        assertEquals("check both the unavailable and resolved member row", 2, rows.size)
        for ((index, row) in rows.withIndex()) {
            assertTrue("row $index must render its headline", row.contains("headlineContent ="))
            assertTrue("row $index: add the outer inset to Material3's own start padding",
                row.substringBefore("headlineContent =").contains(".padding(start = SectionDesign.ScreenHorizontalPadding)"))
            assertFalse("row $index must not have an icon or spacer in a leading slot", row.contains("leadingContent ="))
        }
        assertTrue(rows[0].contains("R.string.model_group_model_unavailable"))
        assertTrue(rows[0].contains("Icons.Default.Delete"))
        assertTrue(rows[1].contains("text = entry.model.displayName"))
        assertTrue(rows[1].contains("Icons.Default.MoreVert"))
    }

    @Test
    fun `every zone exposes move up and move down calling the real persistence path`() {
        val expectations = mapOf(
            "ProviderListScreen.kt" to listOf("moveProviderBy(", "reorderInstances("),
            "ModelGroupsScreen.kt" to listOf("moveGroupBy(", "reorderGroups("),
            "AgentLoopModelsScreen.kt" to listOf("onMoveEntry(", "onMoveGroup(", "reorderAgentLoopEntries(", "reorderAgentLoopGroups("),
            "ModelGroupDetailScreen.kt" to listOf("moveMemberBy(", "updateGroup("),
            "ChatMenuSettingsScreen.kt" to listOf("moveMenuEntryBy(", "moveAttachEntryBy(", "moveFooterEntryBy("),
        )
        for ((file, anchors) in expectations) {
            val c = code("$dir/$file")
            assertTrue("$file: must publish accessibility reorder actions", c.contains("reorderAccessibilityActions("))
            for (anchor in anchors) {
                assertTrue("$file: missing $anchor — the action must reach the real order writer", c.contains(anchor))
            }
        }
    }

    @Test
    fun `the chat menu keeps its settings wall and its three independent lists`() {
        val c = code("$dir/ChatMenuSettingsScreen.kt")
        assertTrue("the footer must still treat SETTINGS as a wall", c.contains("lockedKeys = setOf(ChatMenuPrefs.SETTINGS),"))
        assertTrue(
            "the accessibility move must respect the same wall as the gesture",
            c.contains("chatMenuDragTargetIndex(footerOrder, from, delta, setOf(ChatMenuPrefs.SETTINGS))"),
        )
        assertTrue("the gesture's pure landing slot must survive", c.contains("chatMenuDragTargetIndex("))
        assertTrue("the gesture's pure swap must survive", c.contains("chatMenuDragSwap(currentOrder"))
    }

    @Test
    fun `the provider list keeps its same-section guard`() {
        val c = code("$dir/ProviderListScreen.kt")
        assertTrue("cross-section provider drags must stay refused", c.contains("val sameSection ="))
        assertTrue("the refusal must be applied", c.contains("if (!sameSection) return@rememberReorderableLazyListState"))
        assertTrue("the splice must keep other buckets in place", c.contains("spliceSectionOrder("))
    }

    @Test
    fun `the agent loop keeps its two independent zones`() {
        val c = code("$dir/AgentLoopModelsScreen.kt")
        assertTrue(c.contains("\"agent_entry:\"") && c.contains("\"agent_group:\""))
        assertTrue("cross-zone drags must stay refused by construction", c.contains("when {"))
    }

    @Test
    fun `application back arrows are gone but the system back channel remains`() {
        for (file in listOf("ModelGroupsScreen.kt", "AgentLoopModelsScreen.kt", "ModelGroupDetailScreen.kt")) {
            val c = code("$dir/$file")
            assertTrue("$file: the app back arrow must be gone", !c.contains("ArrowBack"))
            assertTrue("$file: onBack must stay wired for the system back / other callers", c.contains("onBack"))
        }
    }

    // ── ④ the shared helper itself ───────────────────────────────────────

    @Test
    fun `the helper covers both 2_4_0 scopes and not the newer one`() {
        val c = code("$dir/ReorderableCardRow.kt")
        assertTrue("LazyColumn items use ReorderableCollectionItemScope in 2.4.0", c.contains("ReorderableCollectionItemScope.longPressReorder("))
        assertTrue("plain Columns use ReorderableScope in 2.4.0", c.contains("ReorderableScope.longPressReorder("))
        assertTrue(
            "ReorderableListItemScope does not exist in 2.4.0 — importing it would not compile",
            !c.contains("ReorderableListItemScope"),
        )
        assertTrue("the gesture must keep the upstream haptic + 0.95 scale feedback", c.contains("GestureThresholdActivate") && c.contains("0.95f"))
    }
}
