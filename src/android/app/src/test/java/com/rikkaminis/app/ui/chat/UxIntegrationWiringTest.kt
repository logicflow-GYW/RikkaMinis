package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring only. These assertions are not a Compose/GPU/visual performance test. */
class UxIntegrationWiringTest {
    private fun source(path: String): String {
        val explicitRoot = System.getProperty("minis.test.sourceRoot")
        if (explicitRoot != null) {
            val file = File(explicitRoot, "src/android/app/src/main/java/com/rikkaminis/app/$path")
            check(file.isFile) { "explicit source not found: $file" }
            return withoutComments(file.readText())
        }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (prefix in listOf("src/android/app", "app")) {
                val file = File(dir, "$prefix/src/main/java/com/rikkaminis/app/$path")
                if (file.isFile) return withoutComments(file.readText())
            }
            dir = dir.parentFile
        }
        error("production source not found: $path")
    }

    private fun withoutComments(text: String): String = text
        .replace(Regex("(?ms)^\\h*/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^\\h*//[^\\n]*"), "")

    /** Same walk-up contract as [source], for a file under `src/main/res`. */
    private fun resource(relative: String): String {
        val roots = mutableListOf<File>()
        System.getProperty("minis.test.sourceRoot")?.let { roots += File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            roots += dir
            dir = dir.parentFile
        }
        for (root in roots) {
            for (prefix in listOf("src/android/app", "app")) {
                val file = File(root, "$prefix/src/main/res/$relative")
                if (file.isFile) return file.readText()
            }
        }
        return ""
    }

    @Test fun `scroll position never reaches the fold decision`() {
        val screen = source("ui/chat/ChatScreen.kt")
        val aggregate = screen.substringAfter("is FlatChatItem.AssistantMessageItem -> {")
        assertTrue(aggregate.contains("AssistantMessageView("))
        // The browsing/drag signal used to be passed in and expanded every
        // not-yet-folded segment while the user scrolled. It must not come back.
        assertFalse(aggregate.contains("isBrowsingHistory"))
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val view = ui.substringAfter("internal fun AssistantMessageView(").substringBefore("private fun ChatProcessSummary(")
        assertFalse(view.contains("isBrowsingHistory"))
        val planner = source("ui/chat/ChatProcessFoldPlanner.kt")
        val mode = planner.substringAfter("fun mode(")
        assertFalse(mode.contains("browsing"))
        assertFalse(mode.contains("scroll"))
    }

    @Test fun `only a live segment keeps its tail open`() {        val planner = source("ui/chat/ChatProcessFoldPlanner.kt")
        assertTrue(planner.contains("null -> if (liveTail) Mode.TAIL else Mode.COLLAPSED"))
        assertFalse(planner.contains("openByDefault"))
    }

    @Test fun `the summary row is plain text with no filled container`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val row = ui.substringAfter("private fun ChatProcessSummary(").substringBefore("internal fun BoundsTrackedBlock(")
        assertFalse(row.contains(".background("))
        assertFalse(row.contains("RoundedCornerShape"))
        assertTrue(row.contains("buildAnnotatedString"))
        // No second "hidden prefix" row: the summary line IS that line.
        assertFalse(ui.contains("ChatProcessHiddenPrefix"))
    }

    @Test fun `tool shimmer is an isolated sibling and never rerecords static content`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val pill = ui.substringAfter("internal fun ToolCallPill(").substringBefore("internal fun ToolCallRunGroup(")
        val overlay = pill.substringAfter("if (shimmerTick != null) {").substringBefore("MinisMenu(")
        assertTrue(pill.contains("val shimmerTick = if (isRunning) rememberDecorativeTick() else null"))
        assertTrue(overlay.contains("Modifier.matchParentSize()"))
        val layer = overlay.indexOf(".animationLayer()")
        val cache = overlay.indexOf(".drawWithCache {")
        val draw = overlay.indexOf("onDrawBehind {")
        val tick = overlay.indexOf("shimmerTick.value")
        assertTrue(layer >= 0 && cache > layer && draw > cache && tick > draw)
        assertTrue(pill.indexOf(".combinedClickable(") < pill.indexOf("if (shimmerTick != null) {"))
        assertFalse(overlay.contains("drawContent()"))
        assertFalse(overlay.contains(".clickable("))
        assertFalse(overlay.contains(".pointerInput("))
        assertFalse(pill.contains("rememberInfiniteTransition("))
        assertFalse(pill.contains("animateContentSize("))
    }

    @Test fun `shared spinners preserve progress semantics and full viewer dimensions`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        assertEquals(3, Regex("DecorativeSpinner\\(").findAll(ui).count())
        assertFalse(ui.contains("CircularProgressIndicator("))
        assertTrue(ui.contains("DecorativeSpinner(color = textColor, modifier = Modifier.padding(24.dp).size(40.dp))"))
        val decorative = source("ui/components/DecorativeAnimation.kt")
        assertTrue(decorative.substringAfter("fun DecorativeSpinner(").contains(".progressSemantics()"))
    }

    @Test fun `full-content entry exists only after the thinking body is expanded`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val start = ui.indexOf("internal fun ThinkingBlock(")
        val block = ui.substring(start, ui.indexOf("private fun ThinkingFullContentDialog(", start))
        val occurrences = Regex("R\\.string\\.thinking_view_full").findAll(block).toList()
        // 1) >100K header entry (inline body can never render), 2) expanded-body link.
        assertEquals(2, occurrences.size)
        val expand = block.indexOf("visibleState = thinkingBodyVisible")
        assertTrue(block.contains("MutableTransitionState(expanded && !overHardCap)"))
        assertTrue(block.contains("thinkingBodyVisible.targetState = expanded && !overHardCap"))
        assertFalse(block.contains("visible = expanded && !overHardCap"))
        assertTrue(expand > 0)
        assertTrue(occurrences[1].range.first > expand)
        assertTrue(block.substring(expand).contains("if (!isStreaming && isTruncated)"))
        // The collapsed path must not add a row after the header.
        assertFalse(block.substring(0, expand).contains("MinisTextButton("))
    }

    @Test fun `process folding is an opt-in switch that defaults to off`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        assertTrue(ui.contains("processFoldEnabled: Boolean = false"))
        assertTrue(ui.contains("ChatProcessFoldPlanner.plan(message.toolBlocks, message.isStreaming, processFoldEnabled)"))
        val planner = source("ui/chat/ChatProcessFoldPlanner.kt")
        assertTrue(planner.contains("enabled: Boolean"))
        assertTrue(planner.contains("if (!enabled"))
        val settings = source("ui/settings/AppearanceScreen.kt")
        assertTrue(settings.contains("const val KEY_TOOL_FOLD_ENABLED = \"chat.toolFoldEnabled\""))
        assertTrue(settings.contains("getBoolean(KEY_TOOL_FOLD_ENABLED, false)"))
        assertTrue(settings.contains("putBoolean(KEY_TOOL_FOLD_ENABLED, it)"))
        val config = source("config/ConfigBuiltins.kt")
        assertTrue(config.contains("path = \"chat.toolFoldEnabled\""))
        assertTrue(config.contains("key = com.rikkaminis.app.ui.settings.KEY_TOOL_FOLD_ENABLED"))
        assertTrue(config.contains("defaultValue = false"))
        // The screen must own the live value so a switch flip updates mounted rows.
        val screen = source("ui/chat/ChatScreen.kt")
        assertTrue(screen.contains("KEY_TOOL_FOLD_ENABLED ->"))
        assertTrue(screen.contains("processFoldEnabled = processFoldEnabled,"))
    }

    @Test fun `aggregate still consumes exact segment members and original tool callbacks`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val aggregate = ui.substringAfter("internal fun AssistantMessageView(").substringBefore("private fun ChatProcessSummary(")
        assertTrue(aggregate.contains("ChatProcessFoldPlanner.plan(message.toolBlocks, message.isStreaming"))
        assertTrue(aggregate.contains("segment.memberIndices.forEach { put(it, segment) }"))
        assertTrue(aggregate.contains("if (segment != null)"))
        assertTrue(aggregate.contains("return@forEachIndexed"))
        assertTrue(aggregate.contains("val actions = toolPillActions(block)"))
        assertTrue(aggregate.contains("key(message.id, block.id)"))
    }

    @Test fun `reasoning keeps its card whether or not it sits inside a segment`() {
        // Regression (2026-11-26 user report: "启动那个折叠的，思考胶囊就变回原版
        // 没有背景了"). The segment renderer used to pass inProcessSegment=true and
        // strip the card, so turning folding ON silently changed the look of
        // every reasoning block. The reasoning is a member of the run for
        // ORDERING purposes only — its card is presentation the user already had.
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        assertFalse(ui.contains("inProcessSegment"))
        val thinking = ui.substringAfter("internal fun ThinkingBlock(").substringBefore("private fun ChatProcessSummary(")
        assertTrue(thinking.contains(".background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))"))
        assertTrue(thinking.contains(".border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))"))
        // The card must not be behind a condition that a fold mode can flip.
        assertFalse(thinking.contains("if (segment"))
    }

    @Test fun `closing a running segment returns to its tail instead of freezing shut`() {
        // Regression (2026-11-26 user report: "点开折叠再收回去，留下的尾巴就不见
        // 了"). The tap handler wrote an explicit `false`, which outranks the live
        // default forever; only a settled segment has a collapsed state worth
        // remembering, so the running one must clear its choice instead.
        val planner = source("ui/chat/ChatProcessFoldPlanner.kt")
        assertTrue(planner.contains("fun nextChoice(current: Mode, liveTail: Boolean): Boolean?"))
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val aggregate = ui.substringAfter("internal fun AssistantMessageView(").substringBefore("private fun ChatProcessSummary(")
        assertTrue(aggregate.contains("ChatProcessFoldPlanner.nextChoice(mode, segment.liveTail)"))
        assertTrue(aggregate.contains("if (next == null) remove(segment.key) else put(segment.key, next)"))
        // The old shape hard-wrote the choice and lost the tail.
        assertFalse(aggregate.contains("Mode.COLLAPSED -> true"))
    }

    // ── auto-fold: the row settles itself once it leaves the top ────────────

    @Test fun `an open segment folds itself only after the scroll settles`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val aggregate = ui.substringAfter("internal fun AssistantMessageView(")
            .substringBefore("private fun ChatProcessSummary(")
        // Geometry is measured, never guessed.
        assertTrue(aggregate.contains("ChatProcessFoldPlanner.fullyAboveViewport("))
        // The fold waits for a settled list: a fling is not folded mid-flight.
        assertTrue(aggregate.contains("snapshotFlow { outOfViewKeys to state.isScrollInProgress }"))
        assertTrue(aggregate.contains("if (scrolling || keys.isEmpty()) return@collect"))
        // Only the segment the READER opened is closed again.
        assertTrue(aggregate.contains("if (mode != ChatProcessFoldPlanner.Mode.FULL) return@collect"))
        // The row measures, it does not decide: the anchor is handed back to the
        // list, which knows whether the reader is on the tail or in history.
        assertTrue(aggregate.contains("onFoldAnchor?.invoke(geometry.removedPx)"))
        assertFalse(aggregate.contains("requestScrollToItem("))
        // Scroll may CLOSE a segment, never OPEN one: the exact inverse of the
        // rule that once blew the transcript open on every scroll.
        assertFalse(aggregate.contains("put(segment.key, true)"))
        assertFalse(aggregate.contains("put(key, true)"))
    }

    @Test fun `the back-to-newest button also closes what the reader left open`() {
        val screen = source("ui/chat/ChatScreen.kt")
        val fab = screen.substringAfter("FollowEvent.FabDown")
        assertTrue(fab.contains("collapseAllEpoch++"))
        // The rows it reaches must be indexed now: the re-anchor needs this
        // item's position in the list.
        assertTrue(screen.contains("items(") || screen.contains("itemsIndexed("))
        assertTrue(screen.contains("onFoldAnchor = { removed ->"))
        assertTrue(screen.contains("LocalChatViewportTracker provides chatViewport"))
        assertTrue(screen.contains("chatViewport.bounds = it.boundsInWindow()"))
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        assertTrue(ui.contains("LaunchedEffect(collapseAllEpoch)"))
    }

    @Test fun `folding under auto-follow re-pins the bottom instead of shifting it`() {
        // 2026-11-26 user report: the fold "jumps" ONLY while auto-follow is
        // engaged. Root cause: the re-anchor was always the item-offset one, so
        // a follower was pulled `removedPx` off the tail and the follower
        // dragged them back — two moves, seen as one bounce. The anchor now
        // depends on which edge the reader is on.
        val screen = source("ui/chat/ChatScreen.kt")
        val anchor = screen.substringAfter("onFoldAnchor = { removed ->")
            .substringBefore("collapseAllEpoch = collapseAllEpoch")
        assertTrue(anchor.contains("if (followState.isFollowing)"))
        assertTrue(anchor.contains("safeBottomScrollIndex(listState.layoutInfo.totalItemsCount)"))
        assertTrue(anchor.contains("requestScrollToItem(idx, 0)"))
        // History reader: compensate exactly what disappeared above them.
        assertTrue(anchor.contains("ChatProcessFoldPlanner.anchoredOffset("))
        assertTrue(anchor.contains("firstVisibleItemScrollOffset"))
        // And the two branches must stay mutually exclusive — running both is
        // the bug, not a detail.
        assertTrue(anchor.contains("} else if (listState.firstVisibleItemIndex == lazyIndex)"))
    }

    @Test fun `a thinking block opened by the reader always opens at the top`() {
        // 2026-11-26 report: "点击展开思考，有时候是向下展开，有时候是向上展开" —
        // the direction was decided by two accidents (the streaming tail-follow
        // pins the body's scroller to the bottom; a scroller left at the bottom
        // keeps the viewport at the END while the enter animation grows the
        // height), so both unrolled the text upward out of the header.
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val thinking = ui.substringAfter("internal fun ThinkingBlock(")
            .substringBefore("private fun ThinkingFullContentDialog(")
        assertTrue(thinking.contains("val openedByReader = manualChoice == true"))
        assertTrue(
            thinking.contains("if (!thinkingBodyVisible.targetState || !openedByReader) return@LaunchedEffect"),
        )
        assertTrue(thinking.contains("scrollState.scrollTo(0)"))
        // A frozen tail window would keep showing the end even at offset 0.
        assertTrue(thinking.contains("readAnchor = ThinkingReadPlanner.Anchor()"))
        // Gated, not deleted: the app-opened live block still follows the tail.
        assertTrue(thinking.contains("if (!isStreaming || openedByReader) return@LaunchedEffect"))
    }

    @Test fun `the summary row is counts only`() {        // 2026-11-26: the title (last reasoning's bold phrase, else the last
        // call's label) described the final second of a run of eighty calls;
        // the "累计 N 秒" was a SUM of per-call durations (parallel calls
        // double-counted, not wall time) and is not persisted with the tool_use
        // part, so it vanished on reload.
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val row = ui.substringAfter("private fun ChatProcessSummary(")
            .substringBefore("internal fun BoundsTrackedBlock(")
        assertFalse(row.contains("segment.title"))
        assertFalse(row.contains("chat_process_duration"))
        assertTrue(row.contains("R.string.chat_process_tools"))
        // Failures are the only coloured token; a whole red line shouted for one
        // bad call.
        assertTrue(row.contains("appendMetaPart(failedText, ToolErrorColor, neutral)"))
        val planner = source("ui/chat/ChatProcessFoldPlanner.kt")
        assertFalse(planner.contains("val title: String?"))
        assertFalse(planner.contains("titleOf("))
        val strings = resource("values-zh/strings_chat_process.xml")
        if (strings.isNotEmpty()) {
            assertTrue(strings.contains("已执行 %1\$d 个工具"))
            assertFalse(strings.contains("chat_process_duration"))
        }
    }
}
