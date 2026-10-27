package com.rikkaminis.app.ui.preview

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv17-3 / B-03] Source-shape test: every preview WebView host's `AndroidView`
 * must sit inside a `key(...)` that carries its data source.
 *
 * Bug context: `AndroidView(factory = …)` runs `factory` only on first
 * composition (or when the enclosing `key` changes). Two hosts had no key at
 * all (WebPreviewBottomSheet / WebPreviewFullscreenScreen — a holder swap kept
 * the destroyed previous holder's WebView attached), and two hosts keyed only
 * on `rendererEpoch`, which is `remember(dataSource) { mutableStateOf(0) }` and
 * therefore resets 0→0 when the data source changes (FilePreviewScreen: the
 * previous file's HTML stayed on screen; KaTeXView: the factory kept its stale
 * latex/isDark closure through streaming updates and theme flips).
 *
 * Compose cannot run in a sandbox JVM; the behavioral contract is pinned here
 * as source-shape assertions (independent of the gate's Python helpers), with
 * the renderer-death rebuild (`rendererEpoch`) kept in every key — that path
 * must stay live in all four hosts.
 *
 * Expected counts are dead literals, NOT derived from the production files.
 */
class WebPreviewAndroidViewKeyTest {

    // ── 3/4-1: WebPreviewBottomSheet — key(holder) ────────────────────

    @Test
    fun `sheet hosts its AndroidView inside key holder`() {
        val src = stripComments(readRepoFile(SHEET))
        val blocks = keyBlocksAroundAndroidView(src)
        assertEquals(
            "WebPreviewBottomSheet must have exactly one AndroidView (dead literal: 1), " +
                "found ${androidViewCount(src)}",
            1,
            androidViewCount(src),
        )
        assertEquals(
            "every AndroidView site must be wrapped in key(...) (got ${blocks.size} wrapped)",
            1,
            blocks.size,
        )
        assertTrue(
            "the key must carry the holder (key(holder)) — got args: ${blocks.map { it.first }}",
            blocks.all { (args, _) -> containsWord(args, "holder") },
        )
    }

    // ── 3/4-2: WebPreviewFullscreenScreen — key(holder) ───────────────

    @Test
    fun `fullscreen hosts its AndroidView inside key holder`() {
        val src = stripComments(readRepoFile(FULLSCREEN))
        val blocks = keyBlocksAroundAndroidView(src)
        assertEquals(
            "WebPreviewFullscreenScreen must have exactly one AndroidView (dead literal: 1), " +
                "found ${androidViewCount(src)}",
            1,
            androidViewCount(src),
        )
        assertEquals(
            "every AndroidView site must be wrapped in key(...) (got ${blocks.size} wrapped)",
            1,
            blocks.size,
        )
        assertTrue(
            "the key must carry the holder (key(holder)) — got args: ${blocks.map { it.first }}",
            blocks.all { (args, _) -> containsWord(args, "holder") },
        )
    }

    // ── 3/4-3: FilePreviewScreen.HtmlPreview — key(item.file, rendererEpoch) ──

    @Test
    fun `html preview keys AndroidView on item file and renderer epoch`() {
        val src = stripComments(readRepoFile(FILE_PREVIEW))
        val blocks = keyBlocksAroundAndroidView(src)
        assertEquals(
            "FilePreviewScreen must have exactly one AndroidView (dead literal: 1), " +
                "found ${androidViewCount(src)}",
            1,
            androidViewCount(src),
        )
        assertEquals(
            "every AndroidView site must be wrapped in key(...) (got ${blocks.size} wrapped)",
            1,
            blocks.size,
        )
        assertTrue(
            "the key must carry item.file (file switch must re-run the factory) — " +
                "got args: ${blocks.map { it.first }}",
            blocks.all { (args, _) -> containsWord(args, "item.file") },
        )
        assertTrue(
            "rendererEpoch must stay in the key (renderer-death rebuild) — " +
                "got args: ${blocks.map { it.first }}",
            blocks.all { (args, _) -> containsWord(args, "rendererEpoch") },
        )
    }

    // ── 3/4-4: KaTeXView.KaTeXRenderView — key(cacheKey, rendererEpoch) ──

    @Test
    fun `katex view keys AndroidView on cache key and renderer epoch`() {
        val src = stripComments(readRepoFile(KATEX))
        val blocks = keyBlocksAroundAndroidView(src)
        assertEquals(
            "KaTeXView must have exactly one AndroidView (dead literal: 1), " +
                "found ${androidViewCount(src)}",
            1,
            androidViewCount(src),
        )
        assertEquals(
            "every AndroidView site must be wrapped in key(...) (got ${blocks.size} wrapped)",
            1,
            blocks.size,
        )
        assertTrue(
            "the key must carry cacheKey (streaming/theme changes must re-run the " +
                "factory) — got args: ${blocks.map { it.first }}",
            blocks.all { (args, _) -> containsWord(args, "cacheKey") },
        )
        assertTrue(
            "rendererEpoch must stay in the key (renderer-death rebuild) — " +
                "got args: ${blocks.map { it.first }}",
            blocks.all { (args, _) -> containsWord(args, "rendererEpoch") },
        )
    }

    // ── helpers (independent of the gate's Python helpers) ──────────────

    /**
     * Every `key(...) { … }` block whose trailing-lambda body contains an
     * `AndroidView(` call, as (keyArgs, body) pairs. The block lives OUTSIDE the
     * key's paren list (trailing lambda), so the args are the text between the
     * parens and the body is the brace-balanced span that follows.
     */
    private fun keyBlocksAroundAndroidView(src: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val lines = src.split("\n")
        for (i in lines.indices) {
            val line = lines[i]
            if (!Regex("""\bkey\s*\(""").containsMatchIn(line)) continue
            val body = blockSlice(src, line.trim())
            if (!Regex("""\bAndroidView\s*\(""").containsMatchIn(body)) continue
            val args = body.substringBefore("{")
            out.add(args to body)
        }
        return out
    }

    /** Source lines of the block starting at [marker], ending when its braces balance. */
    private fun blockSlice(src: String, marker: String): String {
        val lines = src.split("\n")
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        var depth = 0
        var end = start
        for (k in start until lines.size) {
            val s = stripStrings(lines[k])
            depth += s.count { it == '{' } - s.count { it == '}' }
            end = k
            if (depth == 0) break
        }
        assertTrue("unbalanced braces for $marker", depth == 0)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    /** Drops quoted spans so braces inside string literals do not unbalance the count. */
    private fun stripStrings(line: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '\'' || c == '"') quote = c
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Removes // and block comments (nesting-aware) while keeping string literals. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') { quote = c; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                var nest = 1
                i += 2
                while (i < src.length && nest > 0) {
                    if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { nest--; i += 2 }
                    else if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { nest++; i += 2 }
                    else i++
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Word-boundary containment (a bare substring hit on e.g. `holderTwo` must not pass). */
    private fun containsWord(haystack: String, word: String): Boolean =
        Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(haystack)

    private fun androidViewCount(src: String): Int =
        Regex("""\bAndroidView\s*\(""").findAll(src).count()

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}"
        )
    }

    private companion object {
        private const val SHEET =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewBottomSheet.kt"
        private const val FULLSCREEN =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewFullscreenScreen.kt"
        private const val FILE_PREVIEW =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"
        private const val KATEX =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/KaTeXView.kt"
    }
}
