package com.rikkaminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1017 G1] Source-shape probe for `captureScreenshot`.
 *
 * The leak was a *shape* defect, not a value defect: the callback wrote into a
 * reference nobody read any more, and nothing closed that hand-off before
 * returning. Neither the compiler nor a device-free unit test can see that — the
 * body needs a live `AccessibilityService` — so the three properties that make
 * the hand-off safe are asserted on the production text:
 *
 * 1. the result travels through [LateValueSlot], and the abandoned
 *    `resultRef` shape is gone;
 * 2. the callback asks [LateValueSlot.isCallerWaiting] *before* it allocates the
 *    software copy (skipping that copy is the point — a later check would only
 *    recycle, not avoid, a 10–15 MB allocation);
 * 3. the timeout branch closes the slot before it returns, so a frame that
 *    already landed is recycled instead of parked.
 *
 * The hardware-calibration constant (`timeoutMs = 5_000`) is pinned as well: it
 * is a device-tuned budget, not a knob to be re-tuned by a refactor.
 *
 * Each assertion has an in-test negative control — a mutated body the probe must
 * reject — so a probe that has quietly stopped looking fails loudly.
 */
class ScreenshotCaptureShapeTest {

    private val serviceRel =
        "src/android/app/src/main/java/com/rikkaminis/app/accessibility/MinisAccessibilityService.kt"

    // ── the probe ────────────────────────────────────────────────────────

    /**
     * Body with comment lines blanked out (offsets preserved) so a mention of a
     * call in prose cannot satisfy an ordering probe. The KDoc for
     * `captureScreenshot` names `[LateValueSlot.isCallerWaiting]` while explaining
     * the fix, and an `indexOf` that reads prose would happily order-check the
     * comment instead of the call.
     */
    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    private fun assertCaptureScreenshotShape(rawBody: String) {
        val body = codeOnly(rawBody)
        assertTrue(
            "captureScreenshot must hand the result over through LateValueSlot",
            body.contains("LateValueSlot("),
        )
        assertFalse(
            "the abandoned resultRef hand-off is back (late callbacks leak their bitmap)",
            body.contains("resultRef"),
        )
        val guard = body.indexOf("isCallerWaiting()")
        val copy = body.indexOf(".copy(Bitmap.Config.ARGB_8888")
        assertTrue("the callback never asks whether the caller is still waiting", guard >= 0)
        assertTrue("no software copy found — this probe needs updating", copy > 0)
        assertTrue(
            "isCallerWaiting() must be checked before the full-screen copy is allocated",
            guard < copy,
        )
        val timeout = body.indexOf("if (!done.await(")
        assertTrue("the await(timeout) branch is gone", timeout >= 0)
        val closed = body.indexOf("slot.exit(timedOut)", timeout)
        val returns = body.indexOf("return timedOut", timeout)
        assertTrue("the timeout branch no longer returns its own result", returns > timeout)
        assertTrue(
            "the timeout branch must close the slot before returning (else a late frame is parked)",
            closed in (timeout + 1) until returns,
        )
        assertTrue(
            "the 5 s screenshot timeout is a device-calibrated budget, not a refactor knob",
            body.contains("timeoutMs: Long = 5_000"),
        )
    }

    private fun captureBody(src: String = readRepoFile(serviceRel)): String {
        val start = src.indexOf("fun captureScreenshot(")
        assertTrue("captureScreenshot not found in $serviceRel", start >= 0)
        val end = src.indexOf("\n    fun setNodeText(", start)
        assertTrue("setNodeText must follow captureScreenshot (probe anchor lost)", end > start)
        return src.substring(start, end)
    }

    private fun assertRejects(pristine: String, mutated: String) {
        // A negative control only means something if the probe accepts the real
        // source first — otherwise it is "rejects everything", which would pass
        // against any tree, including the pre-fix one.
        assertCaptureScreenshotShape(pristine)
        var rejected = false
        try {
            assertCaptureScreenshotShape(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts mutated source", rejected)
    }

    // ── the fix ──────────────────────────────────────────────────────────

    @Test
    fun `captureScreenshot arbitrates the late callback and keeps its 5 s budget`() {
        assertCaptureScreenshotShape(captureBody())
    }

    // ── negative controls ────────────────────────────────────────────────

    @Test
    fun `probe rejects a body that parks the late frame instead of recycling it`() {
        val body = captureBody()
        assertRejects(body, body.replace("slot.exit(timedOut)", ""))
    }

    @Test
    fun `probe rejects a body that checks the caller only after allocating the copy`() {
        val body = captureBody()
        val guard = body.indexOf("if (!slot.isCallerWaiting())")
        assertTrue("guard anchor lost", guard >= 0)
        val lateGuard = body.replaceRange(guard, guard + "if (!slot.isCallerWaiting())".length, "if (false)")
        // Sanity: the guard is gone, everything else is untouched.
        assertFalse(lateGuard.contains("isCallerWaiting()"))
        assertRejects(body, lateGuard)
    }

    @Test
    fun `probe rejects the pre-fix resultRef hand-off`() {
        val body = captureBody()
        assertRejects(body, body + "\n        resultRef.set(ShotResult(sw))\n")
    }

    @Test
    fun `probe rejects a re-tuned timeout constant`() {
        val body = captureBody()
        assertEquals(1, countOccurrences(body, "timeoutMs: Long = 5_000"))
        assertRejects(body, body.replace("timeoutMs: Long = 5_000", "timeoutMs: Long = 10_000"))
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

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run can
     * start anywhere under the repo — no fixed depth works.
     */
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
}
