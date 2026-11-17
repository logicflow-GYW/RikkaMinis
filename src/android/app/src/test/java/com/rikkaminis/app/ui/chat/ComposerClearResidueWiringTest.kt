package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/composer-post-send-residue] Wiring test for the composer-side replay
 * guard: the decision lives in [isComposerClearResidue] (pinned by
 * ComposerClearResiduePolicyTest), but a correct pure function that the composer
 * never calls, or calls with the wrong stamp, changes nothing on the device.
 *
 * Pins the three facts the fix depends on, each as a decision rather than a
 * spelling:
 *  1. `clearComposerInput` records the cleared body BEFORE it empties the field
 *     (after the field write the value is already gone — the stamp would be
 *     empty forever and the guard would be dead);
 *  2. `onValueChange` consults the guard, and passes the recorded stamp (a
 *     literal `clearedText = null` would compile and never fire);
 *  3. the guard runs before the field is written / the paste is folded, so the
 *     replay never reaches `inputFieldValue` at all.
 *
 * Read from the repository (walk-up, so both Gradle's module `user.dir` and the
 * repo root work) rather than from a copy: the point is to fail when someone
 * moves the call away from the funnel.
 */
class ComposerClearResidueWiringTest {

    private val source: String get() = readRepoFile(CHAT_INPUT_AREA)

    @Test
    fun `the clear funnel records the cleared body before emptying the field`() {
        val stampDecl = source.indexOf("val stampClearedBody: () -> Unit = {")
        val funnelStart = source.indexOf("val clearComposerInput: () -> Unit = {")
        val readBody = source.indexOf("val clearedBody = inputFieldValue.text")
        val record = source.indexOf("lastClearedText = clearedBody")
        val callStamp = source.indexOf("stampClearedBody()")
        val fieldReset = source.indexOf("inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(\"\")")
        assertTrue("the stamp lambda is gone", stampDecl >= 0)
        assertTrue("the stamp must be declared before the funnel that calls it", stampDecl < funnelStart)
        assertTrue("the cleared body is never read from the field", readBody > stampDecl)
        assertTrue("the cleared body is never recorded", record > readBody)
        assertTrue(
            "the funnel must stamp the body before it empties the field (after the " +
                "reset the stamp would be empty forever and the guard would be dead)",
            callStamp > funnelStart && callStamp < fieldReset,
        )
    }

    @Test
    fun `on value change consults the replay guard with the recorded stamp`() {
        val call = source.indexOf("isComposerClearResidue(")
        val stampArg = source.indexOf("clearedText = lastClearedText")
        assertTrue("the composer never consults the replay guard", call >= 0)
        assertTrue(
            "the guard is called without the recorded stamp (a constant would never fire)",
            stampArg > call,
        )
    }

    @Test
    fun `the guard runs before the field is written and before the paste fold`() {
        val call = source.indexOf("isComposerClearResidue(")
        val fold = source.indexOf("foldLongPasteIfNeeded(")
        assertTrue("the paste fold anchors moved", fold >= 0)
        assertTrue("the replay guard runs after the paste fold", call < fold)
    }

    @Test
    fun `an edit that is not a replay drops the stamp`() {
        // Without this the guard would keep swallowing identical edits for the
        // rest of the window — a keystroke the user actually typed.
        val clear = source.indexOf("lastClearedText = null")
        assertTrue("the stamp is never cleared; a real edit would be swallowed", clear >= 0)
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

    private companion object {
        const val CHAT_INPUT_AREA =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"
    }
}
