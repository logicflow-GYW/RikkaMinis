package com.rikkaminis.app.ui.media

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv19-B02 / dv19-B06] Source-shape + decision test for the media renderer
 * family: every native media host must be (re-)mounted on the DATA SOURCE it
 * renders, and must not open or resolve that source during composition.
 *
 * Bug context: `AndroidView(factory = …)` runs `factory` only on first
 * composition (or when an enclosing `key` changes). `InlineVideoPlayer` and the
 * fullscreen dialog had no key at all, so a reused composition slot (the file
 * preview renders every attachment through the same slot) kept playing the
 * PREVIOUS video — with `videoAspectRatio` already reset to null (height frozen
 * at the 240dp placeholder) and the old `VideoView` still holding the old
 * `MediaController` whose window nobody hid. Their playback state was unkeyed
 * for the same reason. Two markdown renderers additionally still called
 * `MediaPlayer.setDataSource()` / the session-directory resolver inside
 * `remember { }`, i.e. on the main thread during composition, and one of them
 * dropped the freshly built native player when the source was unreadable.
 *
 * Compose cannot run in a sandbox JVM, so the behavioural half is pinned as
 * source shape (the house pattern of `WebPreviewAndroidViewKeyTest` /
 * `FullscreenVideoWindowContractTest`), while the two key derivations are real
 * functions and are exercised directly. Expected counts are dead literals, NOT
 * derived from the production files.
 */
class MediaHostSourceKeyTest {

    // ── 1. what identifies a media source (real functions, no Android) ───

    @Test
    fun `a resolved host is identified by its absolute path`() {
        assertEquals(
            "/sdcard/minis-sessions/abc/attachments/clip.mp4",
            resolvedMediaSourceKey(File("/sdcard/minis-sessions/abc/attachments/clip.mp4")),
        )
        assertEquals("no file yet -> no source identity yet", null, resolvedMediaSourceKey(null))
    }

    @Test
    fun `same-named files in different directories are different sources`() {
        assertEquals("/a/clip.mp4", resolvedMediaSourceKey(File("/a/clip.mp4")))
        assertEquals("/b/clip.mp4", resolvedMediaSourceKey(File("/b/clip.mp4")))
        // A key of `file.name` would collide here and silently reuse the other
        // directory's playback state.
        assertNotEquals(
            resolvedMediaSourceKey(File("/a/clip.mp4")),
            resolvedMediaSourceKey(File("/b/clip.mp4")),
        )
    }

    @Test
    fun `an inline video card is identified by the string the message carried`() {
        assertEquals(
            "content://media/external/video/media/42",
            inlineMediaSourceKey("content://media/external/video/media/42"),
        )
        assertEquals(
            "/data/user/0/com.rikkaminis.app/files/clip.mp4",
            inlineMediaSourceKey("/data/user/0/com.rikkaminis.app/files/clip.mp4"),
        )
        assertNotEquals(
            inlineMediaSourceKey("/a/clip.mp4"),
            inlineMediaSourceKey("/b/clip.mp4"),
        )
    }

    // ── 2. the view and the state move together ─────────────────────────

    @Test
    fun `the inline video host is re-mounted on its data source`() {
        val body = bodyOf(stripComments(readRepoFile(PLAYER)), "fun InlineVideoPlayer(")
        assertEquals(
            "InlineVideoPlayer must have exactly one AndroidView (dead literal: 1), " +
                "found ${androidViewCount(body)}",
            1,
            androidViewCount(body),
        )
        val blocks = keyBlocksAroundAndroidView(body)
        assertEquals(
            "the host must sit inside key(...) (got ${blocks.size} wrapped)",
            1,
            blocks.size,
        )
        assertTrue(
            "the key must carry the data source the card renders — " +
                "got args: ${blocks.map { it.first }}",
            blocks.all { containsWord(it.first, "inlineMediaSourceKey") },
        )
    }

    @Test
    fun `every fullscreen playback state carries the data-source key`() {
        val body = bodyOf(
            stripComments(readRepoFile(PLAYER)),
            "private fun FullscreenVideoContent(",
        )
        assertEquals(
            "all eight playback states must be remembered on fileKey (dead literal: 8)",
            8,
            Regex("""by remember\(fileKey\)""").findAll(body).count(),
        )
        assertEquals(
            "an unkeyed state delegate re-introduces the previous file's " +
                "state (dead literal: 0)",
            0,
            Regex("""by remember\s*\{""").findAll(body).count(),
        )
        assertTrue(
            "fileKey must be the resolved data source of this host",
            body.contains("val fileKey = resolvedMediaSourceKey(file)"),
        )
        val blocks = keyBlocksAroundAndroidView(body)
        assertEquals(
            "the fullscreen host must sit inside key(...) (got ${blocks.size} wrapped)",
            1,
            blocks.size,
        )
        assertTrue(
            "the key must carry the resolved data source — " +
                "got args: ${blocks.map { it.first }}",
            blocks.all { containsWord(it.first, "resolvedMediaSourceKey") },
        )
    }

    @Test
    fun `the inline audio transport state carries the file key`() {
        val body = bodyOf(stripComments(readRepoFile(PLAYER)), "fun InlineAudioPlayer(")
        assertEquals(
            "isPlaying / progress / duration / position / error / prepared / " +
                "pendingPlay must all be keyed on filePath (dead literal: 7)",
            7,
            Regex("""by remember\(filePath\)""").findAll(body).count(),
        )
        assertEquals(
            "an unkeyed state delegate shows the previous file's play state " +
                "(dead literal: 0)",
            0,
            Regex("""by remember\s*\{""").findAll(body).count(),
        )
    }

    // ── 3. no media source is opened or resolved during composition ──────

    @Test
    fun `the markdown media blocks resolve through the shared off-main resolver`() {
        // Literals stand for the media blocks each renderer owns, not for the
        // helper's own definition or its KDoc mentions: both renderers now run
        // image + video + audio through it (3 each) -- MarkdownText's image
        // block was the last one handing Coil a bare host path (dv20-B03 B-05).
        // [r3-c / B09 F-12] MarkdownText's audio block gained `prepareFailed`
        // as a fifth member of the resolved-file-keyed family (player +
        // prepared + isPlaying + positionMs + prepareFailed) so the dead
        // "prepare threw" state can't outlive a file swap. StreamingMarkdownText
        // is outside that wave's radius and keeps the original four.
        for ((rel, blocks, fileKeyed) in listOf(
            Triple(STREAM, 3, 4),
            Triple(MARKDOWN, 3, 5),
        )) {
            val src = stripComments(readRepoFile(rel))
            assertEquals(
                "$rel: every media block must resolve via rememberMdMediaFile " +
                    "(dead literal: $blocks)",
                blocks,
                Regex("""val file = rememberMdMediaFile\(""").findAll(src).count(),
            )
            assertEquals(
                "$rel: the audio player instance plus prepared / isPlaying / " +
                    "positionMs / prepareFailed must share the resolved-file key " +
                    "(dead literal: $fileKeyed)",
                fileKeyed,
                Regex("""remember\(resolvedMediaSourceKey\(file\)\)""").findAll(src).count(),
            )
        }
    }

    @Test
    fun `no media source is opened or resolved inside a remember block`() {
        for (rel in listOf(STREAM, MARKDOWN)) {
            val src = stripComments(readRepoFile(rel))
            val spans = rememberBlocks(src)
            assertTrue("$rel: no remember block found — the scanner drifted", spans.isNotEmpty())
            val hits = Regex("""(setDataSource|resolveMediaFile|resolveMdMediaFile)\s*\(""")
                .findAll(src)
                .filter { m -> spans.any { m.range.first in it } }
                .map { it.value }
                .toList()
            assertEquals(
                "$rel: opening a media source inside remember{} is composition-time " +
                    "IO on the main thread (dead literal: 0) — found $hits",
                0,
                hits.size,
            )
        }
    }

    // ── helpers (independent of the gate's Python helpers) ─────────────

    /** Balanced `{ ... }` body of the first function whose declaration matches [marker]. */
    private fun bodyOf(src: String, marker: String): String {
        val m = src.indexOf(marker)
        assertTrue("marker not found: $marker", m >= 0)
        val open = src.indexOf('{', m)
        assertTrue("no body after: $marker", open >= 0)
        var depth = 0
        for (j in open until src.length) {
            if (src[j] == '{') depth++
            if (src[j] == '}') {
                depth--
                if (depth == 0) return src.substring(open, j + 1)
            }
        }
        throw IllegalStateException("unbalanced body after: $marker")
    }

    /**
     * Every `key(...) { … }` block whose trailing-lambda body contains an
     * `AndroidView(` call, as (keyArgs, body) pairs.
     */
    private fun keyBlocksAroundAndroidView(src: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        src.split("\n").forEachIndexed { i, line ->
            if (!Regex("""\bkey\s*\(""").containsMatchIn(line)) return@forEachIndexed
            val body = blockSliceAt(src, i)
            if (!Regex("""\bAndroidView\s*\(""").containsMatchIn(body)) return@forEachIndexed
            out.add(body.substringBefore("{") to body)
        }
        return out
    }

    /** Char ranges of every `remember …{ … }` trailing-lambda block in [src]. */
    private fun rememberBlocks(src: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        src.split("\n").forEachIndexed { i, line ->
            if (!Regex("""\bremember\b""").containsMatchIn(line)) return@forEachIndexed
            val text = blockSliceAt(src, i)
            val start = lineOffset(src, i)
            spans.add(start until (start + text.length))
        }
        return spans
    }

    /** Char offset of line [index]. */
    private fun lineOffset(src: String, index: Int): Int {
        var off = 0
        for (k in 0 until index) off = src.indexOf('\n', off) + 1
        return off
    }

    /** Source lines of the block starting at line [start], ending when its braces balance. */
    private fun blockSliceAt(src: String, start: Int): String {
        val lines = src.split("\n")
        var depth = 0
        var end = start
        for (k in start until lines.size) {
            val s = stripStrings(lines[k])
            depth += s.count { it == '{' } - s.count { it == '}' }
            end = k
            if (depth == 0) break
        }
        assertTrue("unbalanced braces at line ${start + 1}", depth == 0)
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
                if (c == '\\') {
                    i += 2
                    continue
                }
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
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                var nest = 1
                i += 2
                while (i < src.length && nest > 0) {
                    if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        nest--
                        i += 2
                    } else if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        nest++
                        i += 2
                    } else {
                        i++
                    }
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
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        private const val PLAYER =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/media/InlineMediaPlayer.kt"
        private const val STREAM =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt"
        private const val MARKDOWN =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/MarkdownText.kt"
    }
}
