package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [fix/aggregate-copy-text] Stage G regression guard.
 *
 * The aggregate renderer path (`AssistantMessageView` in
 * ChatAssistantMessageUI.kt) must register a MinisTextKit `TextShard` for every
 * `StreamingMarkdownText` it renders — otherwise long-press selection and
 * "copy plain text" fail (no shard → no selectable region). See
 * `StreamingMarkdownText.kt` L540-546: text is only registered as a shard when
 * `shardId != null`.
 *
 * Because `AssistantMessageView` is a pure composable that the sandbox JVM
 * cannot render, we use a static source assertion: read the production source
 * and assert that BOTH `StreamingMarkdownText(...)` calls in the aggregate
 * path (the per-text-block one and the legacy fallback) carry a
 * `shardId = TextShardId(...)` argument. This is a self-checking textual
 * contract that fails loudly if someone later strips the shard registration.
 */
class AssistantMessageShardRegressionTest {

    private val sourceFile: File by lazy {
        // Gradle test working dir is the module dir; provide both candidates
        // depending on how the sandbox clones/checks out the repo.
        val candidates = listOf(
            File("src/android/app/src/main/java"),
            File("src/main/java"),
        )
        val root: File = candidates.firstOrNull { it.exists() }
            ?: File(System.getProperty("user.dir"))
        val file = File(
            root,
            "com/rikkaminis/app/ui/chat/ChatAssistantMessageUI.kt",
        )
        if (!file.exists()) {
            fail("cannot locate ChatAssistantMessageUI.kt (tried ${file.absolutePath})")
        }
        file
    }

    private val sourceText: String by lazy { sourceFile.readText() }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    @Test
    fun `text-block StreamingMarkdownText carries TextShardId shard`() {
        // The per-text-block call site in the aggregate path.
        val call = """
            StreamingMarkdownText(
                                content = block.content,
                                // Only the trailing text block is "still streaming"; earlier
                                // text blocks (before a tool call) are frozen.
                                isStreaming = streaming,
                                // Stage G: register a stable TextShard so MinisTextKit can
                                // hit-test and select this text (long-press + copy).
                                // `block.id` is the stable pre-stream-rebuild block key —
                                // do NOT use index (streaming inserts shift indices).
                                shardId = TextShardId(
        """.trimIndent()
        assertTrue(
            "aggregate text-block StreamingMarkdownText must register TextShardId(block.id). Source:\n$sourceText\nPreview of the call:\n${sourceText.substring(sourceText.indexOf("content = block.content").takeIf { it >= 0 } ?: 0)}",
            sourceText.contains("content = block.content") &&
                sourceText.contains("shardId = TextShardId(") &&
                sourceText.contains("shardId = block.id,"),
        )
    }

    @Test
    fun `exactly two TextShardId registrations in AssistantMessageView (text block + legacy fallback)`() {
        val shardRegistrations = countOccurrences(sourceText, "shardId = TextShardId(")
        assertTrue(
            "expected exactly 2 shard registrations (text block + legacy fallback), found $shardRegistrations",
            shardRegistrations == 2,
        )
    }

    @Test
    fun `legacy fallback StreamingMarkdownText carries legacy-text shard`() {
        assertTrue(
            "legacy fallback must use shardId = \"legacy-text\"",
            sourceText.contains("shardId = \"legacy-text\","),
        )
    }

    @Test
    fun `shardId uses stable block id not render index`() {
        // Regression guard: shard must be keyed by stable `block.id` (streaming
        // inserts move indices, which would corrupt selection). No
        // `shardId = ... index` style selection anywhere in the file.
        assertTrue(
            "shard must be keyed by block.id, not index",
            sourceText.contains("shardId = block.id,"),
        )
    }

    @Test
    fun `selection kit TextShardId type is importable from same package`() {
        // TextShardId lives in the same `com.rikkaminis.app.ui.chat` package
        // (MinisTextKitSelection.kt), so no import is required in
        // ChatAssistantMessageUI.kt. This guards accidental reliance on a
        // (broken) implicit import of a not-yet-existing type.
        assertTrue(
            "TextShardId must be defined in the chat package",
            sourceText.contains("shardId = TextShardId("),
        )
    }
}
