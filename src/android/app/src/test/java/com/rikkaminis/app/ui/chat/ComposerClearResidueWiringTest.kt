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
        // [W1118-COMPOSER F-01] Both anchors must be resolved INSIDE the funnel:
        // the landed-replay wipe contains the same two statements on purpose
        // (it clears the field without stamping), so a top-of-file indexOf finds
        // that copy and the arm fails for the wrong reason.
        val callStamp = source.indexOf("stampClearedBody()", funnelStart)
        val fieldReset = source.indexOf(
            "inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(\"\")",
            funnelStart,
        )
        assertTrue("the stamp lambda is gone", stampDecl >= 0)
        assertTrue("the stamp must be declared before the funnel that calls it", stampDecl < funnelStart)
        assertTrue("the cleared body is never read from the field", readBody > stampDecl)
        assertTrue("the cleared body is never recorded", record > readBody)
        assertTrue("the funnel never stamps at all", callStamp > funnelStart)
        assertTrue(
            "the funnel must stamp the body before it empties the field (after the " +
                "reset the stamp would be empty forever and the guard would be dead)",
            callStamp > funnelStart && callStamp < fieldReset,
        )
    }

    @Test
    fun `on value change consults the replay guard with the recorded stamp`() {
        val call = source.indexOf("composerResidueVerdict(")
        val stampArg = source.indexOf("clearedText = lastClearedText")
        assertTrue("the composer never consults the replay guard", call >= 0)
        assertTrue(
            "the guard is called without the recorded stamp (a constant would never fire)",
            stampArg > call,
        )
    }

    /**
     * [fix/composer-post-send-residue-2] Refusing to grow a replay is not enough:
     * a char-by-char IME replay lands its first fragments before the guard can
     * recognise it, so the box is already partly filled. If the composer only
     * drops the newest edit, the user keeps staring at half of the message he
     * just sent — the exact reported symptom.
     */
    @Test
    fun `an accumulated replay is wiped through the clear funnel`() {
        val verdictCall = source.indexOf("composerResidueVerdict(")
        val dropAndClear = source.indexOf("ComposerResidueVerdict.DROP_AND_CLEAR")
        assertTrue("the verdict enum is never branched on", dropAndClear >= 0)
        assertTrue("the DROP_AND_CLEAR branch sits before the guard is even reached", dropAndClear > verdictCall)
        val after = source.substring(dropAndClear)
        assertTrue(
            "an accumulated replay must be cleared through the funnel, not just skipped",
            after.contains("clearComposerInput()"),
        )
    }

    /**
     * [W1118-COMPOSER F-01] The wipe of an already-landed replay must NOT go
     * through `clearComposerInput()`. That funnel calls `stampClearedBody()`,
     * which reads whatever is in the box — on this path a FRAGMENT of the sent
     * message — and overwrites the stamp for the whole message with it. One
     * frame later the IME continues with a fragment that no longer matches the
     * truncated stamp, the guard returns NONE, and the rest of the message walks
     * back into the box. That is the regression the shipped c2a09fe9 carried.
     */
    @Test
    fun `the landed-replay wipe keeps the original stamp`() {
        val wipeDecl = source.indexOf("val wipeLandedReplay: () -> Unit = {")
        assertTrue("the non-stamping wipe path is gone", wipeDecl >= 0)
        val wipeBody = source.substring(wipeDecl, source.indexOf("val clearComposerInput", wipeDecl))
        assertTrue(
            "the landed-replay wipe must not re-stamp: it would record the fragment",
            !wipeBody.contains("stampClearedBody()"),
        )
        val branch = source.indexOf("ComposerResidueVerdict.DROP_AND_CLEAR")
        assertTrue("the verdict is never branched on", branch >= 0)
        val after = source.substring(branch, minOf(source.length, branch + 600))
        assertTrue(
            "the DROP_AND_CLEAR branch must use the non-stamping wipe",
            after.contains("wipeLandedReplay()"),
        )
        assertTrue(
            "the DROP_AND_CLEAR branch must NOT call the stamping funnel",
            !after.contains("clearComposerInput()"),
        )
    }

    /**
     * The stamp must survive a legitimate short fragment. A char-by-char replay
     * starts with 1-3 characters, which arm A lets through BY DESIGN; the old
     * `if (tfv.text.isNotEmpty()) lastClearedText = null` treated that as the
     * user reclaiming the composer and destroyed the fingerprint, after which
     * nothing could ever be caught again.
     */
    @Test
    fun `the stamp survives an edit that is still a piece of the cleared body`() {
        val drop = source.indexOf("lastClearedText = null")
        assertTrue("the stamp is never dropped", drop >= 0)
        val guard = source.indexOf("isFragmentOfClearedBody(", maxOf(0, drop - 400))
        assertTrue(
            "the stamp is dropped on ANY non-empty edit, so the first replay fragment " +
                "disarms the guard for the rest of the window",
            guard in 0 until drop,
        )
    }

    @Test
    fun `the guard runs before the field is written and before the paste fold`() {
        val call = source.indexOf("composerResidueVerdict(")
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
