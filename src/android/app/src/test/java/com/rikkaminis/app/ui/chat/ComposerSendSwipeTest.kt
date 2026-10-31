package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Wiring probes for FIX-4 B-06 (deep-audit-1037) — swipe-up-to-send parity.
 *
 * Two defects in the same gesture block (`ChatInputArea`):
 *  1. the release branch re-derived send eligibility INSIDE an already
 *     `hasText`-guarded block (`val canSendNow = hasText || attachmentsCount > 0`),
 *     so the inner test was tautological: with attachments but no text the
 *     drag fell to the "pop the keyboard" branch instead of sending;
 *  2. both the hint and the release read the DEBOUNCED ViewModel string
 *     (`inputText`), so a just-typed prefix could be invisible for the 150 ms
 *     burst window and the release silently did nothing.
 *
 * One shared predicate (`inputFieldValue.text.isNotBlank() || attachments.isNotEmpty()`),
 * read at call time from the field's own TextFieldValue, now drives all three
 * decision sites. The threshold reads through `rememberUpdatedState` so a
 * ChatTuning adjustment reaches the long-lived pointerInput closure.
 *
 * Compose is not runnable on the JVM here, so this asserts the source shapes
 * — same probe shape as `FastioWiringTest`, negative control per probe.
 */
class ComposerSendSwipeTest {

    private val inputFile = "src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"

    // ── probes ───────────────────────────────────────────────────────────────

    /** Exactly one shared eligibility predicate, read from the live field value. */
    private fun assertSingleLivePredicate(input: String) {
        val skel = skeleton(input)
        val defs = Regex("""val (\w+): \(\) -> Boolean = \{""").findAll(skel).toList()
        assertEquals(
            "exactly one send-eligibility predicate must exist — a per-site re-derivation " +
                "is how the dead branch came back",
            1,
            defs.size,
        )
        val body = braceSlice(skel, defs.first().range.last)
        assertTrue(
            "the predicate must read the field's own TextFieldValue, not the debounced " +
                "ViewModel string (a just-typed prefix is invisible for the burst window)",
            body.contains("inputFieldValue.text.isNotBlank()"),
        )
        assertTrue(
            "the predicate must count attachments — an attachment-only drag is a valid send",
            body.contains("attachments.isNotEmpty()"),
        )
        assertTrue(
            "the predicate must NOT read the debounced ViewModel string",
            !body.contains("inputText.isNotBlank()"),
        )
    }

    /** The predicate must be shared by all three decision sites. */
    private fun assertPredicateSharedByThreeSites(input: String) {
        val skel = skeleton(input)
        val name = Regex("""val (\w+): \(\) -> Boolean = \{""").find(skel)?.groupValues?.get(1)
        assertTrue("the shared predicate must exist", !name.isNullOrEmpty())
        val calls = Regex("""\b${Regex.escape(name!!)}\(""").findAll(skel).count()
        assertTrue(
            "the shared predicate must be read at the hint, the release and the " +
                "empty-input branches (three sites), found $calls",
            calls >= 3,
        )
    }

    /** The nested dead-branch shape must not come back. */
    private fun assertNoDeadBranchShape(input: String) {
        val block = gestureBlock(input)
        assertTrue("the swipe gesture block must exist", block.isNotEmpty())
        val lines = block.split("\n")
        lines.forEachIndexed { i, line ->
            val m = Regex("""if \((\w+) && (\w+)""").find(line) ?: return@forEachIndexed
            val second = m.groupValues[2]
            val window = lines.drop(i + 1).take(4).filter { it.isNotBlank() }
            val tautology = window.any {
                Regex("""val \w+ = ${Regex.escape(second)}\b(\(\))? \|\|""").containsMatchIn(it)
            }
            assertTrue(
                "an eligibility value must not be re-derived from `$second` inside a " +
                    "block already guarded by `&& $second` — the inner test is " +
                    "tautological and sends attachment-only drags to the keyboard",
                !tautology,
            )
        }
    }

    /** The threshold must reach the long-lived gesture closure. */
    private fun assertThresholdIsLiveState(input: String) {
        val m = Regex(
            """val (\w+) = rememberUpdatedState\(\s*with\(LocalDensity\.current\) \{\s*sendSwipeThresholdDp\.dp\.toPx\(\)"""
        ).find(skeleton(input))
        assertTrue(
            "the swipe threshold must read through rememberUpdatedState — a plain val " +
                "capture freezes the first composition's px forever",
            m != null,
        )
        val name = m!!.groupValues[1]
        assertTrue(
            "the gesture must read the threshold state ($name.value), or the holder is dead weight",
            gestureBlock(input).contains("$name.value"),
        )
    }

    /** Send/enqueue and the empty-input keyboard shortcut must both survive. */
    private fun assertPreservedGestures(input: String) {
        val block = gestureBlock(input)
        assertTrue(
            "the release branch must route through performSendOrEnqueue() — streaming " +
                "drag-to-send enqueues instead of being dropped",
            block.contains("performSendOrEnqueue()"),
        )
        assertTrue(
            "the empty-input keyboard shortcut (!inputFocused) must survive",
            block.contains("!inputFocused"),
        )
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `one shared send predicate reads the live field value`() {
        assertSingleLivePredicate(source(inputFile))
    }

    @Test
    fun `the shared predicate drives all three decision sites`() {
        assertPredicateSharedByThreeSites(source(inputFile))
    }

    @Test
    fun `the swipe gesture has no tautological eligibility re-derivation`() {
        assertNoDeadBranchShape(source(inputFile))
    }

    @Test
    fun `the swipe threshold follows dynamic tuning`() {
        assertThresholdIsLiveState(source(inputFile))
    }

    @Test
    fun `send-enqueue and the empty-input keyboard shortcut survive`() {
        assertPreservedGestures(source(inputFile))
    }

    // ── negative controls ────────────────────────────────────────────────────

    @Test
    fun `negative control - restoring the baseline dead branch fails the shape probe`() {
        val original = source(inputFile)
        // The dead-branch shape: the re-derivation sits INSIDE the guarded block,
        // after the `swipedUp && <flag>` condition line.
        val mutated = original.replace(
            "if (swipedUp && canSwipeSendNow()) {",
            "if (swipedUp && canSwipeSendNow()) {\n" +
                "                        val canSendNow = canSwipeSendNow() || attachmentsCount > 0",
        )
        Assume.assumeTrue("the release branch must exist to be rewritten", mutated != original)
        var failed = false
        try {
            assertNoDeadBranchShape(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the shape probe must fail once the tautological re-derivation returns", failed)
    }

    @Test
    fun `negative control - a debounced-VM predicate fails the live-value probe`() {
        val original = source(inputFile)
        // [fix/composer-paste-lifecycle] The predicate is a multi-line expression
        // now, so aim at the single sub-expression that stays unique regardless
        // of line wrapping — a whole-literal replace would silently skip this
        // negative control (assumeTrue) and lose its teeth.
        val mutated = original.replace(
            "inputFieldValue.text.isNotBlank() ||",
            "inputText.isNotBlank() ||",
        )
        Assume.assumeTrue("the predicate body must exist to be rewritten", mutated != original)
        var failed = false
        try {
            assertSingleLivePredicate(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the live-value probe must fail on the debounced read", failed)
    }

    @Test
    fun `negative control - a frozen threshold fails the dynamic-tuning probe`() {
        val original = source(inputFile)
        val mutated = original.replace(
            "val swipeThresholdPxState = rememberUpdatedState(\n" +
                "        with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() },\n" +
                "    )",
            "val swipeThresholdPxState = with(LocalDensity.current) { sendSwipeThresholdDp.dp.toPx() }",
        )
        Assume.assumeTrue("the threshold state must exist to be frozen", mutated != original)
        var failed = false
        try {
            assertThresholdIsLiveState(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the dynamic-tuning probe must fail on a frozen capture", failed)
    }

    @Test
    fun `negative control - dropping the enqueue route fails the preserved-gesture probe`() {
        val original = source(inputFile)
        val mutated = original.replace("performSendOrEnqueue()", "Unit // dropped")
        Assume.assumeTrue("the enqueue route must exist to be dropped", mutated != original)
        var failed = false
        try {
            assertPreservedGestures(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the preserved-gesture probe must fail once the send route is gone", failed)
    }

    // ── source helpers ───────────────────────────────────────────────────────
    private fun source(rel: String): String = sourceFile(rel).readText()

    /**
     * Comment- and string-blanked copy of [src] with LENGTH AND OFFSETS
     * PRESERVED — a `${…}` template or a `//` inside a URL would otherwise
     * desync brace counting (the B-01 branch carries a `${cmd.id}` literal).
     * Because blanking keeps indices aligned, slices can be taken from the
     * original text using offsets found in the skeleton.
     */
    private fun skeleton(src: String): String {
        val out = StringBuilder(src.length)
        val stack = mutableListOf('c' to 0)
        var i = 0
        while (i < src.length) {
            val frame = stack.last()
            if (frame.first == 'c') {
                if (src.startsWith("\"\"\"", i)) {
                    out.append("   "); i += 3
                    while (i < src.length && !src.startsWith("\"\"\"", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < src.length) { out.append("   "); i += 3 }
                    continue
                }
                val c = src[i]
                if (c == '"') { stack.add('s' to 0); out.append('"'); i++; continue }
                if (src.startsWith("//", i)) {
                    while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
                    continue
                }
                if (src.startsWith("/*", i)) {
                    out.append("  "); i += 2
                    while (i < src.length && !src.startsWith("*/", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < src.length) { out.append("  "); i += 2 }
                    continue
                }
                if (c == '{') { stack[stack.lastIndex] = 'c' to (frame.second + 1); out.append(c); i++; continue }
                if (c == '}') {
                    if (frame.second == 0 && stack.size > 1) { stack.removeAt(stack.lastIndex); out.append(c); i++; continue }
                    stack[stack.lastIndex] = 'c' to (frame.second - 1); out.append(c); i++; continue
                }
                out.append(c); i++
                continue
            }
            val c = src[i]
            if (c == '\\') { out.append("  "); i += 2; continue }
            if (src.startsWith("${'$'}{", i)) { stack.add('c' to 0); out.append("${'$'}{"); i += 2; continue }
            if (c == '"') { stack.removeAt(stack.lastIndex); out.append('"'); i++; continue }
            out.append(if (c == '\n') '\n' else ' '); i++
        }
        return out.toString()
    }

        private fun braceSlice(skel: String, start: Int): String {
        if (start == -1) return ""
        var depth = 0
        var i = start
        while (i < skel.length) {
            when (skel[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return skel.substring(start, i + 1)
                }
            }
            i++
        }
        return skel.substring(start)
    }

    /**
     * The lambda body of `pointerInput(Unit)` — the swipe gesture closure.
     * The marker is located in the SKELETON: prose (a KDoc mentioning
     * "pointerInput(Unit)") would otherwise win the `indexOf` race and the
     * slice would land on whatever brace follows the comment.
     */
    private fun gestureBlock(text: String): String {
        val skel = skeleton(text)
        val idx = skel.indexOf("pointerInput(Unit)")
        return if (idx == -1) "" else braceSlice(skel, skel.indexOf('{', idx))
    }

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }
}
