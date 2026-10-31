package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Wiring probes for [fix/ime-dictation-guard] (upstream OpenMinis issue #472).
 *
 * The IME's own voice typing dies when we hide the keyboard, and
 * scroll-to-dismiss — `LaunchedEffect(isUserDragging)` in [ChatScreen] — hides it
 * on every finger drag of the message list. A dictating user scrolls up to read
 * what they are answering, so that drag ended their dictation.
 *
 * Compose state and [android.media.AudioManager] cannot be driven from a plain
 * JVM unit test, so the wiring is asserted against the sources — same probe
 * shape as `ChatSlashMcpWiringTest`. Every expectation is a literal, and each
 * probe carries a negative control that mutates a copy of the real source and
 * asserts the same check fails (structure, not symbol counts: a "call site
 * count" alone cannot see a new hide path, so the ordering probe pins WHERE the
 * guard sits relative to the hide).
 */
class ImeDictationGuardWiringTest {

    private val chatScreenFile = "src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"
    private val imeDictationFile = "src/main/java/com/rikkaminis/app/ui/chat/ImeDictation.kt"

    private val guardCall = "ImeDictation.recordingActive(context)"
    private val hideCall = "keyboardController?.hide()"

    // ── probes ───────────────────────────────────────────────────────────────

    /**
     * Inside the drag-to-dismiss block the guard must run BEFORE the hide: a
     * guard placed after `keyboardController?.hide()` would log and return
     * having already killed the dictation.
     */
    private fun assertGuardPrecedesHide(src: String) {
        val block = dragBlockOf(src)
        assertTrue("LaunchedEffect(isUserDragging) block must exist", block.isNotEmpty())
        val guard = block.indexOf(guardCall)
        val hide = block.indexOf(hideCall)
        assertTrue("the drag block must call $guardCall", guard != -1)
        assertTrue("the drag block must still hide the keyboard when nothing is recording", hide != -1)
        assertTrue(
            "the dictation guard must sit BEFORE the hide in the drag block — after it, " +
                "the keyboard is already gone and the IME's voice session with it",
            guard < hide,
        )
        // The arm body is read from the RAW source (the skeleton blanks string
        // literals, and the log tag lives in one).
        val raw = dragBlockRaw(src)
        assumeTrue("the raw block must be locatable for the log assertion", raw.isNotEmpty())
        val rawGuard = raw.indexOf(guardCall)
        val rawHide = raw.indexOf(hideCall, rawGuard)
        assumeTrue("the raw block must contain the hide after the guard", rawHide > rawGuard)
        val armText = raw.substring(rawGuard, rawHide)
        assertTrue(
            "the guard must return out of the effect (`return@LaunchedEffect`), or the hide below it runs anyway",
            armText.contains("return@LaunchedEffect"),
        )
        assertTrue(
            "the guard must log through ScrollFollow so a real-device report can tell it fired",
            armText.contains("\"ScrollFollow\""),
        )
    }

    /**
     * Exactly one call site: the guard protects ONE decision (a user drag
     * dismissing the keyboard). A second call site means either a new hide path
     * was wired to it (the contract must then be re-stated deliberately) or a
     * stray probe was left behind — both worth a red test rather than a silent
     * drift. Other hides (send, drawer, overlay) stay unguarded by design.
     */
    private fun assertSingleCallSite(src: String) {
        val count = Regex(Regex.escape(guardCall)).findAll(skeleton(src)).count()
        assertEquals(
            "ImeDictation.recordingActive must have exactly ONE call site in ChatScreen.kt " +
                "(the scroll-to-dismiss guard); found $count",
            1,
            count,
        )
    }

    /** The guard must keep its teeth — a hollow `recordingActive = false` would pass both probes above. */
    private fun assertImplementationQueriesRecordingConfigurations(src: String) {
        val body = skeleton(src)
        assertTrue(
            "ImeDictation must read activeRecordingConfigurations — that IS the API-29 proxy for " +
                "\"the IME is dictating\"; without it the guard is a constant",
            body.contains("activeRecordingConfigurations"),
        )
        assertTrue(
            "ImeDictation must resolve AudioManager from AUDIO_SERVICE",
            body.contains("Context.AUDIO_SERVICE"),
        )
        assertTrue(
            "ImeDictation must fail OPEN (`getOrDefault(false)`): a throwing guard must never trap " +
                "the keyboard on screen",
            body.contains("getOrDefault(false)"),
        )
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `dragging the list keeps the keyboard while the IME is dictating`() {
        assertGuardPrecedesHide(source(chatScreenFile))
    }

    @Test
    fun `the dictation guard has exactly one call site`() {
        assertSingleCallSite(source(chatScreenFile))
    }

    @Test
    fun `the guard reads the platform recording configurations`() {
        assertImplementationQueriesRecordingConfigurations(source(imeDictationFile))
    }

    @Test
    fun `negative control - a block without the guard fails the ordering probe`() {
        val original = source(chatScreenFile)
        val mutated = withoutGuard(original)
        assumeTrue("the guard must exist to be removable", mutated != original)
        assertTrue("the hide must survive the mutation", dragBlockOf(mutated).contains(hideCall))
        var failed = false
        try {
            assertGuardPrecedesHide(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the ordering probe must fail once the guard is gone", failed)
    }

    @Test
    fun `negative control - a second call site fails the single-site probe`() {
        val original = source(chatScreenFile)
        val secondCall = "private fun __secondGuardProbe(context: android.content.Context) =\n" +
            "    ImeDictation.recordingActive(context)\n"
        val mutated = original + "\n" + secondCall
        var failed = false
        try {
            assertSingleCallSite(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the single-site probe must fail once a second call site appears", failed)
    }

    @Test
    fun `negative control - a hollow implementation fails the implementation probe`() {
        val original = source(imeDictationFile)
        val mutated = original.replace("activeRecordingConfigurations", "emptyList<Any>()")
        assumeTrue("the platform call must exist to be hollowed out", mutated != original)
        var failed = false
        try {
            assertImplementationQueriesRecordingConfigurations(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the implementation probe must fail on a hollow guard", failed)
    }

    // ── source location (same technique as ChatSlashMcpWiringTest) ───────────

    private fun source(rel: String): String = sourceFile(rel).readText()

    /** Code-only text of the `LaunchedEffect(isUserDragging) { … }` body, comments and strings blanked. */
    private fun dragBlockOf(src: String): String {
        val skel = skeleton(src)
        val marker = skel.indexOf("LaunchedEffect(isUserDragging)")
        if (marker == -1) return ""
        val brace = skel.indexOf('{', marker)
        if (brace == -1) return ""
        return braceSlice(skel, brace)
    }

    /** Raw (comments/strings intact) text of the same block — offsets match [dragBlockOf]. */
    private fun dragBlockRaw(src: String): String {
        val skel = skeleton(src)
        val marker = src.indexOf("LaunchedEffect(isUserDragging)")
        if (marker == -1) return ""
        val brace = src.indexOf('{', marker)
        if (brace == -1) return ""
        return src.substring(brace, braceSliceEnd(skel, brace))
    }

    /**
     * Removes the `if (ImeDictation.recordingActive(context)) { … }` arm from
     * the real source. Offsets are taken from the comment/string-blanked
     * skeleton (which preserves positions) so the removal lands exactly on the
     * code, not on a look-alike inside the KDoc that documents it.
     */
    private fun withoutGuard(src: String): String {
        val skel = skeleton(src)
        val start = skel.indexOf(guardCall)
        if (start == -1) return src
        val brace = skel.indexOf('{', start)
        if (brace == -1) return src
        val end = braceSliceEnd(skel, brace)
        val ifStart = skel.lastIndexOf("if (", start).let { if (it == -1) start else it }
        return src.removeRange(ifStart, end)
    }

    private fun braceSliceEnd(skel: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < skel.length) {
            when (skel[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return skel.length
    }

    private fun braceSlice(skel: String, start: Int): String = skel.substring(start, braceSliceEnd(skel, start))

    /**
     * Comment- and string-blanked copy of [src] with LENGTH AND OFFSETS
     * PRESERVED — a `//` inside prose or a quote inside a log line would
     * otherwise let documentation satisfy an assertion about code, and
     * mis-aligned offsets would desync the brace walk.
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
            if (src.startsWith("\${", i)) { stack.add('c' to 0); out.append("\${"); i += 2; continue }
            if (c == '"') { stack.removeAt(stack.lastIndex); out.append('"'); i++; continue }
            out.append(if (c == '\n') '\n' else ' '); i++
        }
        return out.toString()
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
