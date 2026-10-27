package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [fix/da1016-chat-sysinfo] 8d — system rows must NOT render the ✨ identity
 * header.
 *
 * A "system" message is a notice, not a speaker turn: slash-command
 * confirmations, browser download progress, context-pressure warnings and the
 * compact divider all arrive as `role = "system"` rows built by
 * `appendSystemInfo` (the ONLY production writer of that role — see
 * ChatViewModel.flushPendingSysInfo). Before this fix the aggregate pipeline —
 * the only live pipeline — rendered every one of them through
 * `AssistantMessageView`, whose `AssistantHeader()` call was unconditional, so
 * each notice carried an "AI said this" attribution. The legacy flat builder
 * skipped the header for `isSystem` rows, but that pipeline is runtime-dead,
 * so this regression guard pins the skip in the LIVE renderer.
 *
 * `AssistantMessageView` is a Compose function the sandbox JVM cannot render,
 * so (same approach as [AssistantMessageShardRegressionTest]) this is a
 * self-checking textual contract on the production source: the header must be
 * called exactly once in the file, and that call must sit inside an open
 * `message.role != "system"` guard placed before the message's block loop.
 */
class SystemRowHeaderSuppressionTest {

    private fun repoSource(tailPath: String): File {
        val candidates = listOf(
            File("src/android/app/src/main/java"),
            File("src/main/java"),
        )
        val root: File = candidates.firstOrNull { it.exists() }
            ?: File(System.getProperty("user.dir") ?: error("user.dir unset"))
        val file = File(root, tailPath)
        if (!file.exists()) {
            fail("cannot locate $tailPath (tried ${file.absolutePath})")
        }
        return file
    }

    private val rendererText: String by lazy {
        repoSource("com/rikkaminis/app/ui/chat/ChatAssistantMessageUI.kt").readText()
    }

    private val flatItemsText: String by lazy {
        repoSource("com/rikkaminis/app/ui/chat/ChatFlatItems.kt").readText()
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    /** The [AssistantHeader] DECLARATION (`fun AssistantHeader() {`). */
    private val declIdx: Int get() = rendererText.indexOf("fun AssistantHeader()")

    /**
     * The first CALL site — searched from the END of the declaration line, so
     * the `AssistantHeader()` substring inside the declaration itself
     * (`fun AssistantHeader() {`) cannot be mistaken for the call.
     */
    private val callIdx: Int
        get() {
            val from = rendererText.indexOf("\n", declIdx).let { if (it >= 0) it else declIdx }
            return rendererText.indexOf("AssistantHeader()", from)
        }

    @Test
    fun `AssistantHeader has exactly one call site`() {
        assertTrue("AssistantHeader declaration not found", declIdx >= 0)
        assertTrue("AssistantHeader() is never called", callIdx >= 0)
        val second = rendererText.indexOf("AssistantHeader()", callIdx + 1)
        assertEquals(
            "AssistantHeader() must be called exactly once in this file — a second " +
                "call site bypasses the system-role guard (offset=$second)",
            -1,
            second,
        )
    }

    @Test
    fun `the header call sits inside the open system-role guard`() {
        val call = callIdx
        assertTrue("AssistantHeader() call not found", call >= 0)
        val guardMarker = "if (message.role != \"system\") {"
        val guardIdx = rendererText.indexOf(guardMarker)
        assertTrue(
            "guarded by `$guardMarker` — without it system rows (notices / compact " +
                "divider) render an AI identity header again",
            guardIdx in 0 until call,
        )
        val between = rendererText.substring(guardIdx + guardMarker.length, call)
        val net = countOccurrences(between, "{") - countOccurrences(between, "}")
        assertEquals(
            "nothing may open or close a brace between the guard's `{` (part of the " +
                "marker) and AssistantHeader() — a -1 means the guard was closed " +
                "before the call (header unguarded again)",
            0,
            net,
        )
    }

    @Test
    fun `the guard lives inside AssistantMessageView and before the body loop`() {
        val funIdx = rendererText.indexOf("internal fun AssistantMessageView(")
        val guardIdx = rendererText.indexOf("if (message.role != \"system\") {")
        val call = callIdx
        val loopIdx = rendererText.indexOf("message.toolBlocks.forEachIndexed")
        assertTrue("AssistantMessageView declaration not found", funIdx >= 0)
        assertTrue(
            "the guard must be inside AssistantMessageView, before its block loop, and " +
                "wrap the header call (fun=$funIdx guard=$guardIdx call=$call loop=$loopIdx)",
            funIdx < guardIdx && guardIdx < call && call < loopIdx,
        )
    }

    @Test
    fun `the aggregate pipeline still routes system rows through AssistantMessageView`() {
        // Premise guard for the render-layer fix: buildAggregateChatItems must
        // NOT special-case `role == "system"` into a different item type. If a
        // future change does split system rows off, this test (and the guard it
        // pins) must be revisited — that's deliberate.
        val marker = "internal fun buildAggregateChatItems"
        val start = flatItemsText.indexOf(marker)
        assertTrue("buildAggregateChatItems not found", start >= 0)
        val nextDecl = flatItemsText.indexOf("\ninternal fun ", start + marker.length)
        val body = flatItemsText.substring(
            start,
            if (nextDecl > start) nextDecl else flatItemsText.length,
        )
        assertFalse(
            "buildAggregateChatItems grew a `role == \"system\"` branch — the " +
                "header-suppression premise (system rows reach AssistantMessageView) " +
                "is stale; update this guard and SystemRowHeaderSuppressionTest together",
            body.contains("role == \"system\""),
        )
        assertTrue(
            "buildAggregateChatItems must still emit AssistantMessageItem for non-user rows",
            body.contains("AssistantMessageItem("),
        )
    }
}
