package com.rikkaminis.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1017 G4] Source-shape probe for `SystemSpeechRecognitionEngine`'s
 * startup hygiene. `SpeechRecognizer` cannot be constructed on the JVM, so the
 * two properties are asserted on the production text:
 *
 * 1. `startInternal` destroys the previous recognizer *before* creating a new
 *    one. `tearDown()` used to run only from cancel/onResults/onError, so a
 *    repeated `start()` (mic tapped again mid-session, or after `stop()`)
 *    abandoned a live recognizer with its Binder and audio session.
 * 2. `usingOnDevice` comes from [SpeechOnDevicePlan] — the effective recognizer,
 *    not the requested one.
 *
 * The on-device retry gate in `onError` is pinned as well: it reads
 * `usingOnDevice`, so a future refactor that stops assigning the effective value
 * silently redirects the retry.
 *
 * Each assertion has an in-test negative control, so a probe that has quietly
 * stopped looking fails loudly.
 */
class SystemSpeechLifecycleShapeTest {

    private val engineRel =
        "src/android/app/src/main/java/com/rikkaminis/app/speech/SystemSpeechRecognitionEngine.kt"

    // ── the probe ────────────────────────────────────────────────────────

    /**
     * Body with comment lines blanked out (offsets preserved) so a *mention* of a
     * call in prose cannot satisfy an ordering probe — the KDoc for this very
     * function names `tearDown()` while explaining why it is needed, and the
     * first version of this probe was fooled by it.
     */
    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    private fun assertStartupShape(body: String, fileSrc: String) {
        val code = codeOnly(body)
        val teardown = code.indexOf("tearDown()")
        val create = code.indexOf("SpeechRecognizer.create")
        assertTrue("startInternal never cleans up an existing recognizer", teardown >= 0)
        assertTrue("startInternal never creates a recognizer", create > 0)
        assertTrue(
            "startInternal must destroy the previous recognizer before creating a new one",
            teardown < create,
        )
        assertTrue(
            "usingOnDevice must come from SpeechOnDevicePlan.effective — " +
                "the effective recognizer, not the requested one",
            code.contains("SpeechOnDevicePlan.effective("),
        )
        assertFalse(
            "usingOnDevice is assigned the requested value again (fallback misreported)",
            code.contains("usingOnDevice = wantOnDevice"),
        )
        assertTrue(
            "tearDown must still null the recognizer (idempotency is what makes the " +
                "retry path's own tearDown safe)",
            fileSrc.contains("recognizer = null"),
        )
    }

    private fun startInternalBody(src: String = readRepoFile(engineRel)): String {
        val start = src.indexOf("private fun startInternal(")
        assertTrue("startInternal not found in $engineRel", start >= 0)
        val end = src.indexOf("\n    /** True when the live session", start)
        assertTrue("usingOnDevice member must follow startInternal (probe anchor lost)", end > start)
        return src.substring(start, end)
    }

    private fun assertRejects(pristine: String, mutated: String, fileSrc: String) {
        // A negative control only means something if the probe accepts the real
        // source first — otherwise it is "rejects everything", which would pass
        // against any tree, including the pre-fix one.
        assertStartupShape(pristine, fileSrc)
        var rejected = false
        try {
            assertStartupShape(mutated, fileSrc)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts mutated source", rejected)
    }

    // ── the fix ──────────────────────────────────────────────────────────

    @Test
    fun `startInternal destroys the previous recognizer and reports the effective one`() {
        val src = readRepoFile(engineRel)
        assertStartupShape(startInternalBody(src), src)
    }

    @Test
    fun `onError still gates the on-device retry on usingOnDevice`() {
        val src = readRepoFile(engineRel)
        assertTrue(
            "the on-device retry gate is gone",
            src.contains("usingOnDevice && retryLocale != null"),
        )
        // Exactly one assignment from the effective plan, next to the creation.
        assertEquals(
            1,
            Regex("usingOnDevice = SpeechOnDevicePlan\\.effective").findAll(src).count(),
        )
    }

    // ── negative controls ────────────────────────────────────────────────

    @Test
    fun `probe rejects a startInternal without the pre-create teardown`() {
        val src = readRepoFile(engineRel)
        val body = startInternalBody(src)
        val codeLines = codeOnly(body).lines()
        val idx = codeLines.indexOfFirst { it.contains("tearDown()") }
        assertTrue("probe anchor lost: no tearDown() call in the body", idx >= 0)
        val bodyLines = body.lines().toMutableList()
        val removed = bodyLines.removeAt(idx)
        assertTrue("the line removed must be the call itself", removed.trim() == "tearDown()")
        val mutated = bodyLines.joinToString("\n")
        assertTrue(
            "the mutation must actually remove one tearDown() call",
            countOccurrences(codeOnly(mutated), "tearDown()") <
                countOccurrences(codeOnly(body), "tearDown()"),
        )
        assertRejects(body, mutated, src)
    }

    @Test
    fun `probe rejects the requested-value assignment`() {
        val src = readRepoFile(engineRel)
        val body = startInternalBody(src)
        val mutated = body.replace(
            "usingOnDevice = SpeechOnDevicePlan.effective(wantOnDevice, onDeviceCreationFailed)",
            "usingOnDevice = wantOnDevice",
        )
        assertFalse(mutated.contains("SpeechOnDevicePlan.effective("))
        assertRejects(body, mutated, src)
    }

    // ── [r3-B11-F06] cancel() must invalidate the session BEFORE the post ──
    //
    // cancel() used to only post its teardown to the main looper while leaving
    // `sessionToken` alone. onResults/onError are delivered ON the main thread,
    // so a callback the system had already queued ran ahead of that posted
    // teardown, still matched the live token, and delivered a late transcript
    // (or ERROR_CLIENT) into a session the user had just cancelled. The bump has
    // to be synchronous to make those queued callbacks inert.

    private fun cancelBody(src: String): String {
        val start = src.indexOf("override fun cancel()")
        assertTrue("cancel() not found in $engineRel", start >= 0)
        val end = src.indexOf("override fun markDegraded()", start)
        assertTrue("markDegraded must follow cancel (probe anchor lost)", end > start)
        return src.substring(start, end)
    }

    private fun assertCancelInvalidatesFirst(body: String) {
        val code = codeOnly(body)
        val bump = code.indexOf("sessionToken++")
        val post = code.indexOf("mainHandler.post")
        assertTrue("cancel() no longer bumps the session token", bump >= 0)
        assertTrue("cancel() no longer posts its teardown", post >= 0)
        assertTrue(
            "the token bump must come BEFORE the posted teardown, or a queued " +
                "callback still looks live",
            bump < post,
        )
    }

    @Test
    fun `cancel invalidates the session before posting the teardown`() {
        assertCancelInvalidatesFirst(cancelBody(readRepoFile(engineRel)))
    }

    @Test
    fun `probe rejects a cancel that only posts the teardown`() {
        val body = cancelBody(readRepoFile(engineRel))
        assertCancelInvalidatesFirst(body) // probe must accept the real source
        val mutated = body.replace("sessionToken++", "")
        assertFalse("mutation must remove the bump", mutated.contains("sessionToken++"))
        var rejected = false
        try {
            assertCancelInvalidatesFirst(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: probe accepted a cancel() with no invalidation", rejected)
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }
}
