package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [incr1113 F-08] The stream-end latch in [LargeContentGuard] is cleared in an
 * EFFECT, never during composition.
 *
 * Defect this pins: the lint pass replaced three `remember(key) { …; Unit }`
 * blocks with `LaunchedEffect`, but in the stream-end block it also moved
 * `wasDegraded.value = false` in front of the effect while leaving it in the
 * composition body. Writing a `MutableState` during composition invalidates the
 * scope immediately: the next recomposition reads `false`, the `if` is no longer
 * taken, and the `LaunchedEffect` declared by the previous pass is disposed
 * before its body runs — the diagnostic log could simply never appear, and
 * nothing would notice because it is only a log.
 *
 * Why a source test instead of a Compose UI test: the repo has Compose
 * instrumented test deps, but `androidTest` does not run in CI (the `unit-tests`
 * job is `./gradlew test`), so an instrumented test here would be a check that
 * never executes — the same class of false protection this batch is fixing.
 * Reading the source pins the SHAPE, which is exactly what the defect was, and
 * it runs on every CI build. Same approach as `SystemPromptSegmentOrderTest`.
 */
class LargeContentGuardStreamEndLatchTest {

    private val rel = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/LargeContentGuard.kt"

    @Test
    fun `the stream-end latch is cleared inside the effect, not in composition`() {
        val src = stripComments(readRepoFile(rel))
        val block = braceBlockAfter(src, "if (!isStreaming && wasDegraded.value) {")

        val effectAt = block.indexOf("LaunchedEffect(")
        assertTrue(
            "the stream-end swap no longer declares a LaunchedEffect — the latch " +
                "has nowhere to be cleared without writing state during composition",
            effectAt >= 0,
        )

        // Nothing may WRITE the latch before the effect is declared: that is the
        // composition-time write that cancels the effect it is guarding.
        val beforeEffect = block.substring(0, effectAt)
        assertTrue(
            "wasDegraded is written BEFORE the LaunchedEffect is declared — the " +
                "composition-time write re-triggers recomposition, which disposes " +
                "the effect before its body runs (the log then never appears):\n" +
                beforeEffect.trimIndent(),
            !beforeEffect.contains("wasDegraded.value ="),
        )

        // …and the clear must actually happen inside the effect body, or the
        // latch never resets and the swap log fires on every later pass.
        val effectBody = braceBlockAfter(block, "LaunchedEffect(")
        assertTrue(
            "the latch is never cleared inside the effect — it would stay true and " +
                "re-log the swap on every subsequent recomposition",
            effectBody.contains("wasDegraded.value = false"),
        )
        assertTrue(
            "the effect body must still emit the stream-end-swap log (this is a " +
                "diagnostic; dropping it silently loses the measurement)",
            effectBody.contains("stream-end-swap"),
        )
    }

    @Test
    fun `the streaming-degrade path still logs through an effect`() {
        val src = stripComments(readRepoFile(rel))
        // The sibling block the same lint pass touched: the degrade log must run
        // after composition commits, not inside the frame budget.
        val degradeAt = src.indexOf("wasDegraded.value = true")
        assertTrue("the degrade latch is gone", degradeAt >= 0)
        val tail = src.substring(degradeAt, minOf(src.length, degradeAt + 4000))
        val effectAt = tail.indexOf("LaunchedEffect(")
        assertTrue("the degrade log no longer runs in a LaunchedEffect", effectAt >= 0)
        assertTrue(
            "the degrade log must mention the key it is keyed on",
            tail.substring(effectAt, minOf(tail.length, effectAt + 600)).contains("ContentDiag"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** From [anchor]'s first `{`, through its matching `}` (literals skipped). */
    private fun braceBlockAfter(src: String, anchor: String): String {
        val at = src.indexOf(anchor)
        assertTrue("anchor not found: $anchor", at >= 0)
        val open = src.indexOf('{', at)
        assertTrue("no brace block after: $anchor", open >= 0)
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\' && i + 1 < src.length) { i += 2; continue }
                if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced block for anchor: $anchor")
    }

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; out.append(c) }
                    i++
                }
                inBlock -> {
                    if (c == '*' && next == '/') { inBlock = false; i += 2 } else i++
                }
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2 }
                    else {
                        if (c == '"') inString = false
                        i++
                    }
                }
                c == '/' && next == '/' -> { inLine = true; i += 2 }
                c == '/' && next == '*' -> { inBlock = true; i += 2 }
                c == '"' -> { inString = true; out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (f in listOf(java.io.File(dir, relative), java.io.File(dir, "src/android/app/$relative"))) {
                if (f.exists()) return f.readText()
            }
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
