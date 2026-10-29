package com.rikkaminis.app.ui.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-V F-03] The first-boot mirror detector must actually reach its own rule.
 *
 * `autoDetectOnceIfNeeded` exists to apply a *considered* choice —
 * `decideAutoApply`: a category the user already picked keeps its mirror, and
 * the official CDN is only used when nothing else is known (pinned separately
 * by [MirrorAutoDetectDecisionTest]).
 *
 * The defect was wiring, not policy: the detector called the shared body
 * `startAllTests(context)` — whose settlement auto-applies the fastest mirror
 * for every category that has no `selectedMirrorId` — and only then read
 * `selectedMirrorId.keys` as its `alreadySelected`. By that point the map held
 * one entry per category, so `decideAutoApply` was handed a complete set and
 * returned nothing: the boot path silently behaved like a user tap, official
 * mirrors included.
 *
 * Two properties fix it, and both are ordering properties, so both are pinned
 * here:
 *  1. the prior selection is snapshotted BEFORE the speed test starts;
 *  2. the shared body is told not to settle (`autoApplyOnComplete = false`),
 *     and the user-initiated entry point keeps the default (true).
 */
class MirrorAutoDetectWiringTest {

    private companion object {
        const val SCREEN =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/MirrorSettingsScreen.kt"
    }

    @Test
    fun `the boot path snapshots the prior selection before the speed test`() {
        val body = declarationSlice(readRepoFile(SCREEN), "fun autoDetectOnceIfNeeded(")
        val snapshot = body.indexOf("val alreadySelected = selectedMirrorId.keys.toSet()")
        val start = body.indexOf("startAllTests(context)")
        assertTrue("autoDetectOnceIfNeeded must snapshot`alreadySelected`", snapshot > 0)
        assertTrue("autoDetectOnceIfNeeded must run the speed test", start > 0)
        assertTrue(
            "the snapshot must come FIRST: reading selectedMirrorId after the settlement\n" +
                "handed decideAutoApply a complete set (that is the F-03 defect)",
            snapshot < start,
        )
        assertEquals(
            "exactly one snapshot — a second read after the test would be the old defect again",
            1,
            Regex(Regex.escape("selectedMirrorId.keys.toSet()")).findAll(body).count(),
        )
    }

    @Test
    fun `the boot path does not let the shared body settle`() {
        val body = declarationSlice(readRepoFile(SCREEN), "fun autoDetectOnceIfNeeded(")
        val flag = body.indexOf("settleOnComplete = false")
        val start = body.indexOf("startAllTests(context)")
        assertTrue("autoDetectOnceIfNeeded must ask for a detection-only run", flag > 0)
        assertTrue(
            "the request must be set BEFORE the call — the body consumes it at entry",
            start > 0 && flag < start,
        )
        assertEquals(
            "exactly one request per boot — a second one would be dead weight",
            1,
            Regex(Regex.escape("settleOnComplete = false")).findAll(body).count(),
        )
    }

    @Test
    fun `a user-initiated run keeps the auto-apply`() {
        val src = readRepoFile(SCREEN)
        val body = declarationSlice(src, "fun runAllTests(")
        assertTrue(
            "runAllTests must keep settling (the user tap has no separate decision step)",
            body.contains("startAllTests(context)"),
        )
    }

    @Test
    fun `the shared body's auto-selection is behind the flag`() {
        val body = declarationSlice(readRepoFile(SCREEN), "fun startAllTests(")
        // The signature must stay `(context: Context)`: MirrorSpeedTestShapeTest
        // arm E4 pins that literal and reads the body it opens.
        assertTrue(
            "startAllTests must keep its pinned signature",
            body.startsWith("fun startAllTests(context: Context)"),
        )
        val consume = body.indexOf("val settle = settleOnComplete")
        val reset = body.indexOf("settleOnComplete = true")
        assertTrue("startAllTests must consume the one-shot flag", consume > 0 && reset > consume)
        assertTrue(
            "the flag must be restored at entry, not after the run: a user tap " +
                "landing while the boot run is in flight must still settle",
            reset < body.indexOf("currentJob?.cancel()"),
        )
        val guard = body.indexOf("if (settle) {")
        val write = body.indexOf("useCustomMirror[cat] = true")
        assertTrue("no auto-selection write found in startAllTests", write > 0)
        assertTrue("the auto-selection write must be inside the flag", guard > 0 && guard < write)
        assertTrue(
            "the persist/apply loop must stay inside the same branch",
            body.indexOf("persist(context, cat)", guard).let { it > write && it - write < 400 },
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Text from [declaration] to the start of the next member declaration. */
    private fun declarationSlice(src: String, declaration: String): String {
        val start = src.indexOf(declaration)
        if (start < 0) throw AssertionError("`$declaration` not found (probe is blind)")
        val nextMember = src.indexOf("\n    /**", start + declaration.length)
        val end = if (nextMember < 0) src.length else nextMember
        return src.substring(start, end)
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
