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

    @Test fun `active aggregate call passes actual user browsing and drag state`() {
        val screen = source("ui/chat/ChatScreen.kt")
        val aggregate = screen.substringAfter("is FlatChatItem.AssistantMessageItem -> {")
        assertTrue(aggregate.contains("AssistantMessageView("))
        assertEquals(1, Regex("isBrowsingHistory = !followState\\.isFollowing \\|\\| isUserDragging,")
            .findAll(aggregate).count())
        assertTrue(screen.contains("listState.interactionSource.interactions.collect { interaction ->"))
        assertTrue(screen.contains("is androidx.compose.foundation.interaction.DragInteraction.Start -> {"))
        assertTrue(screen.contains("isUserDragging = true"))
        assertFalse(aggregate.contains("isBrowsingHistory = listState.isScrollInProgress"))
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

    @Test fun `aggregate still consumes exact folded members and original tool callbacks`() {
        val ui = source("ui/chat/ChatAssistantMessageUI.kt")
        val aggregate = ui.substringAfter("internal fun AssistantMessageView(").substringBefore("private fun ChatProcessSummary(")
        assertTrue(aggregate.contains("ChatProcessFoldPlanner.plan(message.toolBlocks, message.isStreaming)"))
        assertTrue(aggregate.contains("group.memberIndices.forEach { put(it, group) }"))
        assertTrue(aggregate.contains("val foldedGroup = groupByIndex[index]"))
        assertTrue(aggregate.contains("return@forEachIndexed"))
        assertTrue(aggregate.contains("val actions = toolPillActions(block)"))
        assertTrue(aggregate.contains("key(message.id, block.id)"))
    }
}
