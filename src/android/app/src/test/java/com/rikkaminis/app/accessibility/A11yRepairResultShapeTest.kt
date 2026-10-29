package com.rikkaminis.app.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [r3-B11-F09] Source-shape probe for [AccessibilityRecoveryManager.repairWithShizuku]'s
 * result.
 *
 * The KDoc promises "true once the service has actually rebound", and BOTH
 * callers act on exactly that:
 *  - `A11yDiagnosticsUi` sets `repairFailed = !ok` (so a false "ok" leaves the
 *    user with no retry affordance while the service is still dead);
 *  - `ensureGrantOrPrompt` returns it to the a11y CLI, which then calls
 *    `svcOrThrow()` in the SAME tool call.
 *
 * The function waited for the rebind (`withTimeoutOrNull … while
 * (getInstance() == null)`), then threw that answer away and returned `true`
 * unconditionally. `ShizukuManager` and `MinisAccessibilityService.getInstance`
 * need a live process, so this is pinned on the production text.
 *
 * A negative control runs the same probe against the pre-fix body with the
 * `return true` restored, so a probe that has stopped looking fails loudly.
 */
class A11yRepairResultShapeTest {

    private val recoveryRel =
        "src/android/app/src/main/java/com/rikkaminis/app/accessibility/AccessibilityRecoveryManager.kt"

    /** Comment lines blanked out (offsets kept) so prose cannot satisfy a probe. */
    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

    private fun repairBody(src: String): String {
        val start = src.indexOf("suspend fun repairWithShizuku(")
        assertTrue("repairWithShizuku not found in $recoveryRel", start >= 0)
        val next = src.indexOf("suspend fun ", start + 10)
        assertTrue("no member after repairWithShizuku (probe anchor lost)", next > start)
        return src.substring(start, next)
    }

    /** True when the function's LAST top-level return is the rebind answer. */
    private fun returnsRebindAnswer(body: String): Boolean {
        val code = codeOnly(body)
        val last = code.lastIndexOf("return ")
        if (last < 0) return false
        val tail = code.substring(last).lineSequence().first().removeSuffix("}").trim()
        val hasUsable = code.contains("val usable = bound") &&
            code.contains("MinisAccessibilityService.getInstance() != null")
        return hasUsable && tail == "return usable"
    }

    @Test
    fun `repair reports the rebind result instead of unconditional success`() {
        val body = repairBody(readRepoFile(recoveryRel))
        assertTrue(
            "repairWithShizuku must return the rebind answer ('return usable'), " +
                "not an unconditional true",
            returnsRebindAnswer(body),
        )
    }

    @Test
    fun `probe rejects the unconditional success return`() {
        val body = repairBody(readRepoFile(recoveryRel))
        assertTrue("probe must accept the live source first", returnsRebindAnswer(body))
        // Restore the pre-fix tail: the rebind wait and the `usable` computation
        // stay in place (so "the wait exists" cannot rescue this), only the
        // returned answer is thrown away — exactly the reported defect.
        val mutated = body.replace("return usable", "return true")
        assertFalse(
            "negative control: the probe accepted a body that discards the rebind answer",
            returnsRebindAnswer(mutated),
        )
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
