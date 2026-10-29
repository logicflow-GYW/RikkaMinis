package com.rikkaminis.app.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [r5f-F05] Source-shape probe for the delivery gate added on top of the
 * [r3-B11-F05] session-token invalidation ([ProviderSpeechSessionTokenShapeTest]
 * covers the token half; this class covers the gate half — the r3 gate counts
 * `isSessionLive` occurrences and is blind to a bare `listener.` call that
 * bypasses the gate entirely).
 *
 * The defect this pins: a `listener.onX` call inside the capture/transcribe
 * coroutines that does not route through `deliver` can fire after
 * cancel()/selectLocale() replaced the session (AudioRecord.read() ignores job
 * cancellation), and the manager's onError resets shared state — killing the
 * replacement session. The gate must (a) wrap EVERY listener call at/after the
 * capture job launch, (b) drop callbacks only when BOTH liveness signals have
 * moved on, and (c) hop to the main looper like SystemSpeechRecognitionEngine.
 *
 * Probes run on comment-blanked source (offsets kept) so prose cannot satisfy
 * them, each with an in-test negative control.
 */
class ProviderSpeechDeliveryGateShapeTest {

    private val engineRel =
        "src/android/app/src/main/java/com/rikkaminis/app/speech/ProviderSpeechRecognitionEngine.kt"
    private val managerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/speech/SpeechRecognitionManager.kt"

    private val callbackRe = Regex("listener\\.(onError|onFinal|onPartial|onRmsDb|onReadyForSpeech)\\b")
    private val deliverHead = "val deliver: (() -> Unit) -> Unit = {"

    /** Comment lines blanked out (offsets kept) so prose cannot satisfy a probe. */
    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    /** Index just past the '}' matching the '{' at [openIdx]. */
    private fun braceEnd(src: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        error("unbalanced braces")
    }

    /** [start, endExclusive) spans of every `marker...{ ... }` occurrence. */
    private fun spansOf(src: String, marker: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var idx = src.indexOf(marker)
        while (idx >= 0) {
            val open = src.indexOf('{', idx + marker.length - 1)
            assertTrue("marker without body: $marker", open >= 0)
            out.add(idx until braceEnd(src, open))
            idx = src.indexOf(marker, idx + 1)
        }
        return out
    }

    private fun engineSrc(): String = readRepoFile(engineRel)
    private fun managerSrc(): String = readRepoFile(managerRel)

    private fun gateSpans(src: String): List<IntRange> = spansOf(src, "deliver {")

    private fun assertGateCoversAllCalls(src: String): Int {
        val capStart = src.indexOf("captureJob = scope.launch {")
        assertTrue("capture job launch not found", capStart >= 0)
        val gates = gateSpans(src)
        assertTrue("no deliver gate found", gates.isNotEmpty())
        val bare = callbackRe.findAll(src)
            .filter { it.range.first >= capStart }
            .filterNot { m -> gates.any { r -> m.range.first in r } }
            .map { "${it.value}@${src.take(it.range.first).count { c -> c == '\n' } + 1}" }
            .toList()
        assertTrue(
            "listener calls at/after the capture job must all go through deliver; bare: $bare",
            bare.isEmpty(),
        )
        return gates.size
    }

    @Test
    fun `all capture-phase listener callbacks go through the deliver gate`() {
        assertGateCoversAllCalls(codeOnly(engineSrc()))
    }

    @Test
    fun `deliver gate posts to main looper and consults both liveness signals`() {
        val src = codeOnly(engineSrc())
        val head = src.indexOf(deliverHead)
        assertTrue("deliver declaration not found", head >= 0)
        val open = src.indexOf('{', head + deliverHead.length - 1)
        val body = src.substring(open, braceEnd(src, open))
        assertTrue("gate must dispatch on the main looper (System-engine contract)", body.contains("mainHandler.post"))
        assertTrue("gate must consult captureDeliverySuperseded", body.contains("captureDeliverySuperseded("))
        assertTrue("gate must pass the capture-epoch signal", body.contains("captureGuard.isCurrent("))
        assertTrue("gate must pass the session-liveness signal", body.contains("isSessionLive("))
        // The predicate must be re-evaluated INSIDE the posted lambda, not at post time.
        val postOpen = body.indexOf('{', body.indexOf("mainHandler.post"))
        val postBody = body.substring(postOpen, braceEnd(body, postOpen))
        assertTrue("predicate must be evaluated inside the posted lambda", postBody.contains("captureDeliverySuperseded("))
    }

    @Test
    fun `gate predicate delivers after stop and goes inert only after cancel`() {
        // Truth table over (isCaptureCurrent, isSessionLive) -> superseded.
        // Expectations are literals, not derived from the implementation.
        assertFalse(captureDeliverySuperseded(true, true))
        // stop(): the capture flag is released but the session is still live —
        // the interface contract owes a final result. A disjunctive predicate
        // would drop it here.
        assertFalse(captureDeliverySuperseded(false, true))
        // Shape that cannot arise from today's start/stop/cancel sequence.
        assertFalse(captureDeliverySuperseded(true, false))
        // cancel()/shutdown() move both signals: "no further callbacks will fire".
        assertTrue(captureDeliverySuperseded(false, false))

        // Locale probe identity (id equality stands in for registry identity).
        assertFalse(localeProbeSuperseded("system", "system"))
        assertFalse(localeProbeSuperseded("provider", "provider"))
        assertTrue(localeProbeSuperseded("system", "provider"))
        assertTrue(localeProbeSuperseded("system", null))
    }

    @Test
    fun `probe rejects a pipeline that bypasses the gate`() {
        val src = codeOnly(engineSrc())
        assertGateCoversAllCalls(src) // probe must accept the live source
        val mutated = src.replace("deliver { listener.onRmsDb(rmsDb(buf, n)) }", "listener.onRmsDb(rmsDb(buf, n))")
        assertFalse("mutation anchor must exist", mutated == src)
        var rejected = false
        try {
            assertGateCoversAllCalls(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: probe accepted a bare listener call", rejected)
    }

    @Test
    fun `probe rejects a gate without main-thread dispatch`() {
        val src = codeOnly(engineSrc())
        val mutated = src.replace("mainHandler.post {", "run {")
        assertFalse("mutation anchor must exist", mutated == src)
        var rejected = false
        try {
            gateSpans(mutated).forEach { r ->
                val body = mutated.substring(r.first, r.last + 1)
                assertTrue("gate must dispatch on the main looper", body.contains("mainHandler.post"))
            }
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: probe accepted an un-dispatched gate", rejected)
    }

    @Test
    fun `locale probe callback re-resolves the engine before writing`() {
        val src = codeOnly(managerSrc())
        val span = spansOf(src, "engine.fetchSupportedLocales {").singleOrNull()
            ?: error("expected exactly one fetchSupportedLocales callback")
        val body = src.substring(span.first, span.last + 1)
        val guard = body.indexOf("localeProbeSuperseded(")
        assertTrue("callback must verify the probe engine is still active", guard >= 0)
        // First code line of the callback must be the guard itself.
        val firstCode = body.lines().drop(1).firstOrNull { it.isNotBlank() }
        assertTrue("guard must be the first statement of the callback", firstCode?.contains("localeProbeSuperseded(") == true)
        for (write in listOf("_supportedLocales.value = locales", "_locale.value = fallback")) {
            val w = body.indexOf(write)
            assertTrue("expected write missing: $write", w >= 0)
            assertTrue("write `$write` must come after the identity guard", w > guard)
        }
    }

    @Test
    fun `locale probe rejects a callback without the identity guard`() {
        val src = codeOnly(managerSrc())
        val mutated = src.replace(
            "if (localeProbeSuperseded(engine.id, currentEngine()?.id)) return@fetchSupportedLocales",
            "            // guard removed",
        )
        assertFalse("mutation anchor must exist", mutated == src)
        var rejected = false
        try {
            val span = spansOf(mutated, "engine.fetchSupportedLocales {").single()
            val body = mutated.substring(span.first, span.last + 1)
            val guard = body.indexOf("localeProbeSuperseded(")
            assertTrue("callback must verify the probe engine is still active", guard >= 0)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: probe accepted an unguarded locale callback", rejected)
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }
}
