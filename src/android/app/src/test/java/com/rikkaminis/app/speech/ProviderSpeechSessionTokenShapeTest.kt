package com.rikkaminis.app.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [r3-B11-F05] Source-shape probe for `ProviderSpeechRecognitionEngine`'s
 * session invalidation.
 *
 * The defect is a race between sessions: `cancel()` set a single shared
 * `cancelled: AtomicBoolean`, and the very next `start()` cleared it again
 * (`selectLocale` cancels and restarts back-to-back) — so the OLD capture job
 * walked past its own cancellation guard and transcribed-and-delivered into the
 * NEW session's listener, wiping a recording the user had just started. The fix
 * is a monotonic per-session token that every delivery point consults.
 *
 * `AudioRecord` cannot be constructed on the JVM, so the properties that make
 * the hand-off safe are asserted on the production text, each with an in-test
 * negative control.
 */
class ProviderSpeechSessionTokenShapeTest {

    private val engineRel =
        "src/android/app/src/main/java/com/rikkaminis/app/speech/ProviderSpeechRecognitionEngine.kt"

    /** Comment lines blanked out (offsets kept) so prose cannot satisfy a probe. */
    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    private fun engineSrc(): String = readRepoFile(engineRel)

    /** Brace-balanced body of the member whose signature contains [signature]. */
    private fun memberBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("no body for: $signature", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces for: $signature")
    }

    private fun assertSessionInvalidation(src: String) {
        // 1. no shared cancelled flag is left anywhere
        assertFalse(
            "the shared `cancelled` AtomicBoolean must be gone (it was the bug)",
            codeOnly(src).contains("cancelled.set("),
        )
        // 2. start() claims a session token
        val startBody = codeOnly(memberBody(src, "override fun start("))
        assertTrue(
            "start() must claim a per-session token",
            startBody.contains("val mySession = sessionToken.incrementAndGet()"),
        )
        // 3. cancel()/shutdown() invalidate the token
        val cancelBody = codeOnly(memberBody(src, "override fun cancel()"))
        assertTrue(
            "cancel() must bump the session token",
            cancelBody.contains("sessionToken.incrementAndGet()"),
        )
        val shutdownBody = codeOnly(memberBody(src, "override fun shutdown()"))
        assertTrue(
            "shutdown() must bump the session token",
            shutdownBody.contains("sessionToken.incrementAndGet()"),
        )
        // 4. every delivery point in the capture/transcribe pipeline consults it
        val whole = codeOnly(src)
        val liveChecks = Regex("isSessionLive\\(mySession\\)").findAll(whole).count()
        assertTrue(
            "every delivery point must consult the session token (found $liveChecks, need >= 4: " +
                "capture-loop guard, transcribe hand-off, per-candidate guard, final guard)",
            liveChecks >= 4,
        )
    }

    @Test
    fun `provider engine invalidates superseded sessions via a token`() {
        assertSessionInvalidation(engineSrc())
    }

    @Test
    fun `probe rejects a pipeline that ignores the token`() {
        val src = engineSrc()
        assertSessionInvalidation(src) // probe must accept the live source
        // The pre-fix shape: every delivery point trusts the session again.
        val mutated = src.replace("isSessionLive(mySession)", "true")
        var rejected = false
        try {
            assertSessionInvalidation(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: probe accepted a pipeline with no token checks", rejected)
    }

    @Test
    fun `cancel also cancels the capture job`() {
        val body = codeOnly(memberBody(engineSrc(), "override fun cancel()"))
        assertTrue(
            "cancel() must cancel the capture job (it used to only drop the handle)",
            body.contains("captureJob?.cancel()"),
        )
    }

    @Test
    fun `session token probe rejects a cancel without invalidation`() {
        val src = engineSrc()
        val body = codeOnly(memberBody(src, "override fun cancel()"))
        assertTrue(body.contains("sessionToken.incrementAndGet()"))
        val mutated = body.replace("sessionToken.incrementAndGet()", "")
        assertFalse(mutated.contains("sessionToken.incrementAndGet()"))
        var rejected = false
        try {
            val cancelBody = codeOnly(memberBody(mutated, "override fun cancel()"))
            assertTrue(cancelBody.contains("sessionToken.incrementAndGet()"))
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: probe accepted a cancel() with no invalidation", rejected)
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
