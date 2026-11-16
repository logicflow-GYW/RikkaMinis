package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [voice-ime-1116] Wiring probes for the composer mirror fix.
 *
 * The behaviour of [shouldMirrorComposerText] is covered by
 * [ComposerMirrorPolicyTest]; what a JVM test cannot see is whether the
 * composable actually USES it, and whether every write this composer performs
 * against the ViewModel is marked as its own. Both are structural facts about
 * `ChatInputArea.kt`, so they are asserted against the source — same probe
 * shape as `ImeDictationGuardWiringTest`.
 *
 * The failure this prevents is not "the helper was deleted" (the compiler
 * would catch that) but the silent one: a new `viewModel.setInputText(...)`
 * added without the marker, which resurrects the exact bug — the mirror then
 * reads that write as an external intent and rolls the field back under a
 * dictating IME.
 */
class ComposerMirrorWiringTest {

    private val composerFile = "src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"
    private val writeMarker = "selfWrite.text ="
    private val vmWrite = "viewModel.setInputText("

    @Test
    fun `the mirror effect consults the decision function, the marker and the dictation guard`() {
        val effect = mirrorEffectBlock(source(composerFile))
        assertTrue(
            "the mirror effect must call shouldMirrorComposerText",
            effect.contains("shouldMirrorComposerText"),
        )
        assertTrue(
            "the mirror must pass this composer's own last write (selfWrite.text) — " +
                "without it our debounced lag reads as an external intent",
            effect.contains("selfWrittenText = selfWrite.text"),
        )
        assertTrue(
            "the mirror must ask ImeDictation whether an IME is recording right now",
            effect.contains("ImeDictation.recordingActive(context)"),
        )
    }

    @Test
    fun `every ViewModel write from the composer is marked as our own`() {
        val src = source(composerFile)
        val skel = skeleton(src)
        val positions = Regex(Regex.escape(vmWrite)).findAll(skel).map { it.range.first }.toList()
        assertEquals(
            "expected the four composer write funnels (clear, commit, burst flush, immediate edit)",
            4,
            positions.size,
        )
        positions.forEachIndexed { index, at ->
            val window = skel.substring(maxOf(0, at - 160), at)
            assertTrue(
                "write #$index at offset $at has no `$writeMarker` just before it — the mirror " +
                    "would treat it as an external intent and roll the field back mid-dictation",
                window.contains(writeMarker),
            )
        }
    }

    @Test
    fun `negative control - an unmarked write fails the probe`() {
        val original = source(composerFile)
        val mutated = original.replace("selfWrite.text = flushed\n", "")
        assumeTrue("the flush marker must exist to be removable", mutated != original)
        var failed = false
        try {
            val skel = skeleton(mutated)
            val positions = Regex(Regex.escape(vmWrite)).findAll(skel).map { it.range.first }.toList()
            positions.forEachIndexed { index, at ->
                val window = skel.substring(maxOf(0, at - 160), at)
                assertTrue("write #$index unmarked", window.contains(writeMarker))
            }
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the probe must fail once a write marker is gone", failed)
    }

    @Test
    fun `negative control - a guard-free mirror fails the effect probe`() {
        val original = source(composerFile)
        val mutated = original.replace("selfWrittenText = selfWrite.text,", "")
        assumeTrue("the marker argument must exist to be removable", mutated != original)
        var failed = false
        try {
            val effect = mirrorEffectBlock(mutated)
            assertTrue(effect.contains("selfWrittenText = selfWrite.text"))
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the effect probe must fail once the marker argument is gone", failed)
    }

    // ── source helpers ───────────────────────────────────────────────────────

    private fun source(rel: String): String = sourceFile(rel).readText()

    /** Code-only body of the `LaunchedEffect(inputText) { … }` mirror. */
    private fun mirrorEffectBlock(src: String): String {
        val skel = skeleton(src)
        val marker = skel.indexOf("LaunchedEffect(inputText)")
        if (marker == -1) return ""
        val brace = skel.indexOf('{', marker)
        if (brace == -1) return ""
        return braceSlice(skel, brace)
    }

    private fun braceSlice(skel: String, start: Int): String {
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
     * Comment- and string-blanked copy of [src] with LENGTH AND OFFSETS
     * PRESERVED, so a `setInputText(` mentioned in KDoc can never satisfy a
     * probe about code.
     */
    private fun skeleton(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            if (src.startsWith("\"\"\"", i)) {
                out.append("   ")
                i += 3
                while (i < src.length && !src.startsWith("\"\"\"", i)) {
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
                if (i < src.length) {
                    out.append("   ")
                    i += 3
                }
                continue
            }
            val c = src[i]
            if (c == '"') {
                out.append('"')
                i++
                while (i < src.length) {
                    val d = src[i]
                    if (d == '\\' && i + 1 < src.length) {
                        out.append("  ")
                        i += 2
                        continue
                    }
                    if (d == '"') {
                        out.append('"')
                        i++
                        break
                    }
                    out.append(if (d == '\n') '\n' else ' ')
                    i++
                }
                continue
            }
            if (src.startsWith("//", i)) {
                while (i < src.length && src[i] != '\n') {
                    out.append(' ')
                    i++
                }
                continue
            }
            if (src.startsWith("/*", i)) {
                out.append("  ")
                i += 2
                while (i < src.length && !src.startsWith("*/", i)) {
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
                if (i < src.length) {
                    out.append("  ")
                    i += 2
                }
                continue
            }
            out.append(c)
            i++
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
