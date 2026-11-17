package com.rikkaminis.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-T6 D11 F-06 / R6 F-02] The crash-share prompt's failure branch must
 * not undo the burst it was about to offer.
 *
 * `maybeShowOnActivity` runs from `MainActivity.onResume` while
 * `MinisApp.onCreate` has already early-returned in safe mode: `_safeMode` is
 * true, `_initSkipped` is true (one-way for the process) and
 * `pendingShareFiles` holds the burst. If the dialog cannot be shown — typically
 * a finishing Activity / dead window token — the `catch` block used to run
 *
 *     pendingShareFiles = null
 *     setSafeMode(false)
 *
 * two lines that contradict the comment right above them and each cost
 * something real:
 *
 *  - clearing the burst makes the RECREATED Activity (rotation, or the next
 *    launch path that re-enters `maybeShowOnActivity`) take the "no pending
 *    burst" branch, which invokes `onClosed()` = `finish()`: the app exits and
 *    the crash logs the user was supposed to send are never offered again in
 *    that process;
 *  - `setSafeMode(false)` fires `fireSafeModeCleared()` to subscribers while
 *    `isInitSkipped()` still holds and the `lateinit` repositories were never
 *    built — an announcement that the app recovered, for an app that cannot
 *    run. Only `finishClose()` (the user actually dismissing the dialog) may
 *    stamp the dismissal clock and clear that state.
 *
 * Pinned as a source probe over the exact branch, with the pre-fix shape put
 * back as the negative control.
 */
class CrashPromptFailureTest {

    private val sourcePath = "src/main/java/com/rikkaminis/app/crash/CrashFrequencyDetector.kt"

    @Test
    fun `a dialog failure keeps the burst and safe mode but releases ownership`() {
        assertFailureBranchIsPassive(source())
    }

    private fun assertFailureBranchIsPassive(body: String) {
        val branch = failureBranch(body)
        assertTrue("the failure branch must still reset the prompt ownership", branch.contains("promptInFlight = false"))
        assertTrue("the host reference must be dropped", branch.contains("promptHost = null"))
        assertTrue(
            "the caller's onClosed must still run (safe mode finishes the Activity)",
            branch.contains("onClosed?.invoke()"),
        )
        assertFalse(
            "the burst is the only copy the recreated Activity can prompt with — it must survive",
            branch.contains("pendingShareFiles = null"),
        )
        assertFalse(
            "safe mode may only be cleared by a resolved dialog (finishClose), never by a failed one",
            branch.contains("setSafeMode(false)"),
        )
    }

    /** The `catch` body around `"dialog failed"`, up to and including `onClosed`. */
    private fun failureBranch(body: String): String {
        val marker = body.indexOf("android.util.Log.w(TAG, \"dialog failed:")
        assertTrue("the failure branch marker is gone", marker >= 0)
        val end = body.indexOf("onClosed?.invoke()", marker)
        assertTrue("the failure branch lost its onClosed call", end > marker)
        return body.substring(marker, end + "onClosed?.invoke()".length)
    }

    @Test
    fun `the probe rejects the pre-fix branch`() {
        val real = source()
        val preFix = real.replace(
            "            promptHost = null\n            onClosed?.invoke()",
            "            promptHost = null\n            pendingShareFiles = null\n            setSafeMode(false)\n            onClosed?.invoke()",
        )
        assertFalse("the mutation must really reinsert the two lines", preFix == real)
        assertEquals(
            "the pre-fix branch must be rejected",
            true,
            runCatching { assertFailureBranchIsPassive(preFix) }.isFailure,
        )
    }

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, sourcePath)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$sourcePath not found from user.dir")
    }
}
