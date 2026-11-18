package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/composer-post-send-residue] Wiring test for the composer-side replay
 * guard: the decision lives in the pure functions in ComposerClearResiduePolicy
 * (pinned by ComposerClearResiduePolicyTest), but a correct pure function that
 * the composer never calls, or calls with the wrong stamp, changes nothing on
 * the device.
 *
 * [W1118-COMPOSER A6] Every arm here is written against a BOUNDED, BRACE-MATCHED
 * block rather than `source.substring(anchor)`. The previous version of this
 * class was provably blind in two of its four arms: `substring(dropAndClear)`
 * reached the `clearComposerInput()` calls 400 lines further down, and a
 * 400-character window before the stamp drop was satisfied by the observability
 * log block a few lines above it. Both were green with the fix deleted. An arm
 * that can be satisfied by text outside the branch it names measures nothing.
 *
 * Read from the repository (walk-up, so both Gradle's module `user.dir` and the
 * repo root work) rather than from a copy: the point is to fail when someone
 * moves the call away from the funnel.
 *
 * [W1119-2D F-07] Every anchor and body check reads the COMMENT-MASKED copy of
 * the file ([code]), and each arm additionally pins that its subject is a direct
 * statement of the block it names. The class-level reason: a raw `indexOf`/
 * `contains` over the file is also satisfied by a comment above the code — a
 * KDoc that quotes the call, or a commented-out copy of it — so the previous
 * version could be turned green by deleting the call and leaving a line of prose
 * in its place. Comments are the third way an arm measures nothing.
 */
class ComposerClearResidueWiringTest {

    private val source: String get() = readRepoFile(CHAT_INPUT_AREA)

    /**
     * [W1119-2D F-07] `source` with comments blanked out, SAME LENGTH, so every
     * offset in `code` is also an offset in `source` and vice versa.
     *
     * Every anchor in this class used to be a raw `source.indexOf(...)`, and every
     * body check a raw `contains`, so a comment containing the anchor text — or a
     * commented-out copy of the call the arm is looking for — satisfied the arm
     * without the code being there at all. All anchors and body checks read this
     * copy instead; [maskComments] is pinned by its own arm so that the guard the
     * other arms depend on is itself tested (the M12 lesson: a gate that can be
     * satisfied without its subject is not a gate).
     */
    private val code: String get() = maskComments(source)

    /** Blank out line and block comments, keeping every offset and newline. */
    private fun maskComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = skipRawString(text, i)
                    out.append(text, i, end)
                    i = end
                }
                text[i] == '"' -> {
                    val end = skipStringLiteral(text, i)
                    out.append(text, i, end)
                    i = end
                }
                text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i)
                    val end = if (nl < 0) text.length else nl
                    while (i < end) {
                        out.append(' ')
                        i++
                    }
                }
                text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i)
                    val end = if (close < 0) text.length else close + 2
                    while (i < end) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun skipRawString(text: String, open: Int): Int {
        val end = text.indexOf("\"\"\"", open + 3)
        return if (end < 0) text.length else end + 3
    }

    private fun skipStringLiteral(text: String, open: Int): Int {
        var j = open + 1
        while (j < text.length) {
            if (text[j] == '\\') {
                j += 2
                continue
            }
            if (text[j] == '"') return j + 1
            if (text.startsWith("\"\"\"", j)) return skipRawString(text, j)
            j++
        }
        return text.length
    }

    @Test
    fun `the clear funnel records the cleared body before emptying the field`() {
        val stampDecl = code.indexOf("val stampClearedBody: () -> Unit = {")
        val funnelStart = code.indexOf("val clearComposerInput: () -> Unit = {")
        val stampBody = blockAt(code, stampDecl)
        val readBody = stampBody.indexOf("val clearedBody = inputFieldValue.text")
        val record = stampBody.indexOf("lastClearedText = clearedBody")
        val funnelBody = blockAt(code, funnelStart)
        val callStamp = funnelBody.indexOf("stampClearedBody()")
        val fieldReset = funnelBody.indexOf(
            "inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(\"\")",
        )
        assertTrue("the stamp lambda is gone", stampDecl >= 0)
        assertTrue("the stamp must be declared before the funnel that calls it", stampDecl < funnelStart)
        assertTrue("the cleared body is never read from the field", readBody >= 0)
        assertTrue("the cleared body is never recorded", record > readBody)
        assertTrue("the funnel never stamps at all", callStamp >= 0)
        assertTrue(
            "the funnel must stamp the body before it empties the field (after the " +
                "reset the stamp would be empty forever and the guard would be dead)",
            callStamp < fieldReset,
        )
    }

    @Test
    fun `on value change consults the replay guard with the recorded stamp`() {
        val call = code.indexOf("composerResidueVerdict(")
        assertTrue("the composer never consults the replay guard", call >= 0)
        val args = code.substring(call, minOf(code.length, call + 700))
        assertTrue(
            "the guard is called without the recorded stamp (a constant would never fire)",
            args.contains("clearedText = lastClearedText"),
        )
        assertTrue(
            "the guard must be given the field's live text, not a constant",
            args.contains("fieldText = inputFieldValue.text"),
        )
        assertTrue(
            "the guard must be given the ViewModel copy as well (arm B needs both)",
            args.contains("vmText = inputText"),
        )
    }

    /**
     * [fix/composer-post-send-residue-2] Refusing to grow a replay is not enough:
     * a char-by-char IME replay lands its first fragments before the guard can
     * recognise it, so the box is already partly filled. If the composer only
     * drops the newest edit, the user keeps staring at half of the message he
     * just sent — the exact reported symptom.
     *
     * [W1118-COMPOSER A6] The block is the `if (verdict == …DROP_AND_CLEAR)` BODY,
     * brace-matched. Slicing to end-of-file made this arm pass on the unrelated
     * `clearComposerInput()` calls further down the file (verified: deleting the
     * one inside the branch left it green).
     */
    @Test
    fun `an accumulated replay is wiped through the clear funnel`() {
        val verdictCall = code.indexOf("composerResidueVerdict(")
        val branch = code.indexOf("if (verdict == ComposerResidueVerdict.DROP_AND_CLEAR)")
        assertTrue("the verdict enum is never branched on", branch >= 0)
        assertTrue("the DROP_AND_CLEAR branch sits before the guard is even reached", branch > verdictCall)
        val branchBody = blockAt(code, branch)
        assertTrue(
            "an accumulated replay must be cleared through the funnel, not just skipped " +
                "(branch body: ${branchBody.replace("\n", " ").take(200)})",
            branchBody.contains("clearComposerInput()"),
        )
    }

    /**
     * [W1118-COMPOSER A4] A guard-driven clear must be MARKED as such, and the
     * mark must be consumed by the stamp. Without it the funnel can only guess
     * from state, and a user's second message that happens to be a piece of the
     * first is indistinguishable from an un-winding replay.
     */
    @Test
    fun `the replay wipe tells the funnel why it is clearing`() {
        val branch = code.indexOf("if (verdict == ComposerResidueVerdict.DROP_AND_CLEAR)")
        assertTrue("the DROP_AND_CLEAR branch is gone", branch >= 0)
        val branchBody = blockAt(code, branch)
        val mark = branchBody.indexOf("guardUnwindClear = true")
        val clear = branchBody.indexOf("clearComposerInput()")
        assertTrue("the guard never marks its clear as a replay unwind", mark >= 0)
        assertTrue("the guard must mark BEFORE it clears", mark < clear)

        val stampDecl = code.indexOf("val stampClearedBody: () -> Unit = {")
        val stampBody = blockAt(code, stampDecl)
        assertTrue(
            "the stamp must consume the flag and hand it to the decision",
            stampBody.contains("guardClear = guardClear"),
        )
        assertTrue(
            "the stamp must clear the flag so a later user clear cannot inherit it",
            stampBody.contains("guardUnwindClear = false"),
        )
    }

    /**
     * [W1118-COMPOSER F-01 / A6] The stamp decision must be the pure helper's
     * ANSWER, not a re-derivation — and the answer must be USED. A shape test can
     * only prove a helper is called, so this arm pins two facts that a "call it
     * and ignore the result" edit would break: the call is the `if` condition,
     * and the stamp write lives INSIDE that `if` block.
     */
    @Test
    fun `the clear funnel does not re-stamp an un-winding replay`() {
        val stampDecl = code.indexOf("val stampClearedBody: () -> Unit = {")
        assertTrue("the stamp lambda is gone", stampDecl >= 0)
        val body = blockAt(code, stampDecl)
        val ifAt = body.indexOf("if (shouldRestampClearedBody(")
        assertTrue(
            "the stamp decision must BE the if condition, not a discarded call",
            ifAt >= 0,
        )
        // [W1118-COMPOSER A6-③] ...and the condition must be that call and NOTHING
        // else. "Is it the if condition" was still blind to `if (helper(...) ||
        // true)`: the call is there, the write is inside the block, and the
        // helper's answer is discarded anyway (M12 in mutate.py proved it). Pin
        // the whole condition expression instead of a substring of it.
        val condition = parenthesisedAt(body, body.indexOf('(', ifAt))
        val inner = condition.removePrefix("(").removeSuffix(")").trim()
        assertTrue(
            "the stamp condition must be the decision itself, found: ${inner.replace("\n", " ")}",
            inner.startsWith("shouldRestampClearedBody(") && inner.endsWith(")"),
        )
        assertTrue(
            "the stamp condition must not OR/AND anything onto the decision — " +
                "`if (shouldRestampClearedBody(...) || true)` still calls it and still " +
                "restamps every time, found: ${inner.replace("\n", " ")}",
            !inner.contains("||") && !inner.contains("&&"),
        )
        val ifBody = blockAt(body, ifAt)
        assertTrue(
            "the stamp write must live inside the decision block (otherwise the " +
                "helper's answer is ignored and every clear restamps)",
            ifBody.contains("lastClearedText = clearedBody") &&
                ifBody.contains("lastClearedAtMs = SystemClock.elapsedRealtime()"),
        )
        assertTrue(
            "the stamp body must not re-implement the rule inline",
            !body.contains("COMPOSER_CLEAR_RESIDUE_WINDOW_MS"),
        )
        // One funnel only: a second clear definition is what the two shape gates
        // (B3RenderInputShapeTest, chat_ui_interaction_check) exist to prevent.
        // Comments are already blanked out by `code`, so a KDoc line that quotes
        // the write no longer counts as one — the count is of real statements.
        val clearWrites = code.lines().count { line ->
            line.contains("viewModel.setInputText(\"\")")
        }
        assertTrue(
            "there must be exactly ONE composer-clear funnel, found $clearWrites",
            clearWrites == 1,
        )
    }

    /**
     * The stamp must survive a legitimate short fragment. A char-by-char replay
     * starts with 1-3 characters, which arm A lets through BY DESIGN; the old
     * `if (tfv.text.isNotEmpty()) lastClearedText = null` treated that as the
     * user reclaiming the composer and destroyed the fingerprint, after which
     * nothing could ever be caught again.
     *
     * [W1118-COMPOSER A6] This arm used to be a 400-character window scan that
     * the observability log block satisfied on its own (that block calls
     * `isFragmentOfClearedBody` too), so deleting the real condition left the
     * suite green. The decision is a pure function now — pinned by
     * ComposerClearResiduePolicyTest — and this arm only pins that the composer
     * ASKS it, with the edit and the stamp.
     */
    @Test
    fun `the stamp survives an edit that is still a piece of the cleared body`() {
        val onValueChange = code.indexOf("onValueChange = { tfv ->")
        assertTrue("the composer's onValueChange is gone", onValueChange >= 0)
        val guard = code.indexOf("shouldKeepClearedStamp(", onValueChange)
        assertTrue(
            "the stamp is dropped without asking whether the edit is still a piece of " +
                "the cleared body, so the first replay fragment disarms the guard",
            guard > onValueChange,
        )
        val args = code.substring(guard, minOf(code.length, guard + 200))
        assertTrue(
            "the decision must be given the arriving text and the recorded stamp",
            args.contains("tfv.text") && args.contains("lastClearedText"),
        )
        val drop = code.indexOf("lastClearedText = null", guard)
        assertTrue("the stamp is never dropped at all", drop > guard)
    }

    @Test
    fun `the guard runs before the field is written and before the paste fold`() {
        val onValueChange = code.indexOf("onValueChange = { tfv ->")
        val call = code.indexOf("composerResidueVerdict(", onValueChange)
        val write = code.indexOf("inputFieldValue = tfv", onValueChange)
        val fold = code.indexOf("foldLongPasteIfNeeded(", onValueChange)
        assertTrue("the guard call moved out of onValueChange", call > onValueChange)
        assertTrue("the field write anchor moved", write > onValueChange)
        assertTrue("the paste fold anchor moved", fold > onValueChange)
        assertTrue("the replay guard runs after the field is written", call < write)
        assertTrue("the replay guard runs after the paste fold", call < fold)
    }

    /**
     * [W1118-COMPOSER A2] A refusal hands the user's OWN text back through
     * `commitComposerBody`; that text must never be measured against the
     * cleared-body fingerprint, or the restored message is wiped by the user's
     * next Backspace (or by the IME settling its punctuation) inside the window.
     */
    @Test
    fun `a composer commit clears the replay fingerprint`() {
        val decl = code.indexOf("val commitComposerBody: (String, Int?) -> Unit = {")
        assertTrue("the commit funnel is gone", decl >= 0)
        val body = blockAt(code, decl)
        val drop = body.indexOf("lastClearedText = null")
        val write = body.indexOf("viewModel.setInputText(text)")
        assertTrue(
            "the commit funnel must drop the cleared-body stamp: the text it writes is " +
                "the user's own, not a replay of a body a clear removed",
            drop >= 0,
        )
        assertTrue("the stamp must be dropped before the text is written", drop < write)
    }

    /**
     * [W1118-COMPOSER C-A F-02] The same funnel must also DISARM the T217-2
     * post-send window. That window drops every edit carrying a composing region
     * for 500 ms after a send, and only the send path arms it — so after a refusal
     * handed the body back, the user's first Pinyin / Japanese / Korean word was
     * thrown away. The write here is the user's own text, so the window goes with
     * the stamp.
     *
     * Pure half of the contract (the predicate standing down) is pinned in
     * ComposerClearResiduePolicyTest; this arm pins that the composer really
     * resets the value the predicate reads.
     */
    @Test
    fun `a composer commit disarms the post-send IME window`() {
        val decl = code.indexOf("val commitComposerBody: (String, Int?) -> Unit = {")
        assertTrue("the commit funnel is gone", decl >= 0)
        val body = blockAt(code, decl)
        val reset = body.indexOf("lastSendTimeMs = 0L")
        val write = body.indexOf("viewModel.setInputText(text)")
        assertTrue(
            "the commit funnel must disarm the post-send IME window: `lastSendTimeMs` " +
                "is still armed from the send that was refused, and the composer drops " +
                "every composing edit for 500 ms",
            reset >= 0,
        )
        assertTrue("the window must be disarmed before the text is written", reset < write)

        val guard = code.indexOf("isPostSendImeResidue(", code.indexOf("onValueChange = { tfv ->"))
        assertTrue("the post-send window must go through the pure predicate", guard >= 0)
        val args = code.substring(guard, minOf(code.length, guard + 400))
        assertTrue(
            "the predicate must be given the live composing region and the armed send time",
            args.contains("hasComposingRegion = tfv.composition != null") &&
                args.contains("msSinceSend = now - lastSendTimeMs"),
        )
    }

    /**
     * [W1119-2D F-07] The guard wipe must leave focus and the keyboard alone.
     *
     * `focusManager.clearFocus()` is what makes the IME settle its composing
     * region (`finishComposingText`) — that is the mechanism that PRODUCES the
     * replay this branch is wiping, so asking for focus here re-arms the very thing
     * it just cleared. It would also dismiss the keyboard on the clear paths that
     * deliberately keep the box focused for the user's next message (a slash
     * command or a clear-chat clear). The stamp is kept on purpose too: it is what
     * recognises the replay fragments that follow.
     */
    @Test
    fun `the guard wipe changes neither focus nor the keyboard`() {
        val branch = code.indexOf("if (verdict == ComposerResidueVerdict.DROP_AND_CLEAR)")
        assertTrue("the DROP_AND_CLEAR branch is gone", branch >= 0)
        val branchBody = blockAt(code, branch)
        assertFalse(
            "the guard wipe drops focus, which makes the IME finish its composing " +
                "region and replay the body it just cleared (branch body: " +
                "${branchBody.replace("\n", " ").take(200)})",
            branchBody.contains("clearFocus"),
        )
        assertFalse(
            "the guard wipe hides the keyboard on a clear the user may still be typing in",
            branchBody.contains("keyboardController"),
        )
    }

    /**
     * [W1119-2D F-07] Every anchor and body check in this class reads the comment-
     * masked view, and that view is itself pinned here — otherwise a change to
     * [maskComments] could silently turn every other arm into a scan of nothing.
     */
    @Test
    fun `the comment masker blanks comment text and keeps every offset`() {
        val sample = "val a = 1 // note: val b = 2\n/* block: val c = 3 */ val d = \"// not a comment\""
        val masked = maskComments(sample)
        assertEquals("the masker must be length preserving", sample.length, masked.length)
        assertFalse("a line comment survived the masking", masked.contains("note:"))
        assertFalse("a block comment survived the masking", masked.contains("block:"))
        assertTrue(
            "a slash inside a string literal was treated as a comment",
            masked.contains("\"// not a comment\""),
        )
        assertEquals("the anchor offsets moved", sample.indexOf("val d"), masked.indexOf("val d"))
        assertTrue(
            "the masker swallowed real code: a string literal must be copied verbatim " +
                "(masked: ${masked.replace("\n", "\\n")})",
            masked.contains("val a = 1") && masked.contains("val d"),
        )
    }

    /**
     * [W1119-2D F-07] The arm that the report proved blind: `clearComposerInput()`
     * wrapped in `if (false) { … }` (or commented out, which the masked view now
     * rules out) satisfies `contains` without the branch doing anything. A direct
     * statement of the branch body is at brace depth ONE; anything nested deeper is
     * conditional or dead.
     */
    @Test
    fun `the replay wipe is a direct statement of the branch body`() {
        val branch = code.indexOf("if (verdict == ComposerResidueVerdict.DROP_AND_CLEAR)")
        assertTrue("the DROP_AND_CLEAR branch is gone", branch >= 0)
        val branchBody = blockAt(code, branch)
        val clear = branchBody.indexOf("clearComposerInput()")
        val mark = branchBody.indexOf("guardUnwindClear = true")
        assertTrue("the wipe call is gone", clear >= 0)
        assertTrue("the mark is gone", mark >= 0)
        assertEquals(
            "the wipe is nested inside another block, so the branch is not what clears " +
                "(branch body: ${branchBody.replace("\n", " ").take(200)})",
            1,
            depthAt(branchBody, clear),
        )
        assertEquals("the mark is nested, so a dead branch would still satisfy the arm", 1, depthAt(branchBody, mark))
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

    /**
     * The brace-matched block that starts at [anchor]: from the first `{` at or
     * after the anchor to its matching `}`. String literals and comments are
     * skipped so a `{` inside KDoc or a message cannot desynchronise the count.
     *
     * Every arm above depends on this being a REAL bound: the previous version
     * sliced to end-of-file and measured text from unrelated code.
     */
    private fun blockAt(text: String, anchor: Int): String {
        assertTrue("anchor not found", anchor >= 0)
        var i = text.indexOf('{', anchor)
        assertTrue("no block opens after the anchor", i >= 0)
        val start = i
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i)
                    if (nl < 0) break
                    i = nl
                    continue
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i)
                    if (end < 0) break
                    i = end + 2
                    continue
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    if (end < 0) break
                    i = end + 3
                    continue
                }
                text[i] == '"' -> {
                    // [W1119-2D F-07] Raw strings too, or a `"""…"""` block (KDoc is
                    // the usual one) desynchronises the counter.
                    i = if (text.startsWith("\"\"\"", i)) skipRawString(text, i)
                    else skipStringLiteral(text, i)
                    continue
                }
                text[i] == '{' -> depth++
                text[i] == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced block at $anchor")
    }

    /**
     * Brace depth at [index]: how many `{` are still open before that point, with
     * string literals and raw strings skipped so a brace inside a message or a KDoc
     * does not desynchronise the count.
     *
     * [W1119-2D F-07] This is what separates "the statement is in the branch" from
     * "the statement is in a nested block inside the branch" — the latter is
     * unreachable or dead and was enough to keep the previous arm green.
     */
    private fun depthAt(text: String, index: Int): Int {
        var depth = 0
        var i = 0
        while (i < index && i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> i = skipRawString(text, i)
                text[i] == '"' -> i = skipStringLiteral(text, i)
                text[i] == '{' -> { depth++; i++ }
                text[i] == '}' -> { depth--; i++ }
                else -> i++
            }
        }
        return depth
    }

    /**
     * The parenthesised expression that starts at [open]: from that `(` to its
     * matching `)`. String literals and comments are skipped for the same reason
     * as in [blockAt].
     *
     * This exists so an arm can assert on the WHOLE condition rather than a
     * substring of it: `if (helper(...) || true)` contains the call, contains the
     * write inside its block, and still ignores the helper's answer.
     */
    private fun parenthesisedAt(text: String, open: Int): String {
        assertTrue("no parenthesis opens at the anchor", open >= 0 && text[open] == '(')
        var i = open
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i)
                    if (nl < 0) break
                    i = nl
                    continue
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i)
                    if (end < 0) break
                    i = end + 2
                    continue
                }
                text[i] == '"' -> {
                    // [W1119-2D F-07] Raw strings too, or a `"""…"""` block (KDoc is
                    // the usual one) desynchronises the counter.
                    i = if (text.startsWith("\"\"\"", i)) skipRawString(text, i)
                    else skipStringLiteral(text, i)
                    continue
                }
                text[i] == '(' -> depth++
                text[i] == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced parenthesis at $open")
    }

    private companion object {
        const val CHAT_INPUT_AREA =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"
    }}
