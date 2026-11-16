package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Wiring probes for deep-audit-1039 B-01 (dv19 wave) — the composer's four
 * decisions must read the LIVE field value.
 *
 * The composer keeps two copies of the draft: the field's own TextFieldValue
 * (`inputFieldValue`, written first on EVERY onValueChange — exactly what the
 * user sees) and the ViewModel string, which a >8-char IME burst (dictation,
 * large paste) lags by up to 150 ms (see `shouldDebounceImeBurst` and the flush
 * job in `ChatInputArea`). dv16-B routed the swipe GESTURE to a shared
 * predicate over the live value, but four DECISIONS kept reading the debounced
 * string:
 *
 *  1. the 3-state button's content flag — inside a burst window the button
 *     rendered a red STOP over a live stream (the tap called cancelStream and
 *     killed the run the user meant to enqueue), and the idle Send stayed
 *     dimmed over a composer the user could see was full;
 *  2. the slash-menu dismiss argument — `dismissSlashMenu` echoes its argument
 *     back as the composer value, so a stale read cleared the just-dictated
 *     burst;
 *  3. the slash-command argument — the same "current input" seam;
 *  4. the cross-session transfer payload — the move stashed an empty string and
 *     left the 150 ms job alive on ChatScreen's scope, so the snapshot was
 *     committed into the session the user had just left.
 *
 * Compose is not runnable on the JVM here, so this asserts the SOURCE SHAPES
 * (same probe style as `ComposerSendSwipeTest`, negative control per probe)
 * plus one EXECUTABLE premise: the burst classifier really does let the
 * committed string lag the field.
 */
class ComposerLiveSourceTest {

    private val inputFile = "src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"

    // ── probes ───────────────────────────────────────────────────────────────

    /**
     * 1. The 3-state button's content decision must transitively resolve to the
     * field's live value (or the shared predicate over it), never to the
     * debounced ViewModel string.
     */
    private fun assertButtonDecisionIsLive(input: String) {
        val skel = skeleton(input)
        val m = Regex("""val (\w+) = isStreaming && !(\w+)""").find(skel)
        assertTrue(
            "the 3-state button's content decision (`val <showStop> = isStreaming && " +
                "!<content>`) must exist — this probe pins that decision point",
            m != null,
        )
        val flag = m!!.groupValues[2]
        val decls = Regex("""val (\w+) = ([^\n]*)""").findAll(skel)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertTrue(
            "the button's content flag `$flag` must be a one-line `val` so the decision " +
                "stays readable (found: ${decls.keys.sorted()})",
            decls.containsKey(flag),
        )
        val pred = Regex("""val (\w+): \(\) -> Boolean = \{""").find(skel)?.groupValues?.get(1)
        assertTrue("the shared send-eligibility predicate must exist", !pred.isNullOrEmpty())

        var frontier = listOf(flag)
        val seen = mutableSetOf<String>()
        var live = false
        var debounced = false
        repeat(4) {
            val next = mutableListOf<String>()
            for (name in frontier) {
                if (!seen.add(name)) continue
                val init = decls[name] ?: continue
                if (init.contains("inputFieldValue")) live = true
                if (Regex("""\binputText\b""").containsMatchIn(init)) debounced = true
                if (init.contains("$pred(")) live = true
                for (tok in Regex("""[A-Za-z_]\w*""").findAll(init)) {
                    if (decls.containsKey(tok.value) && tok.value !in seen) next.add(tok.value)
                }
            }
            frontier = next
        }
        assertTrue(
            "the button's content decision must reach the field's own TextFieldValue (or the " +
                "shared predicate over it) — inside a dictation/paste burst the debounced " +
                "string is empty, which rendered a red STOP over a live stream",
            live,
        )
        assertTrue(
            "the button's content decision must not read the debounced ViewModel string " +
                "(inputText) anywhere in its `val` closure",
            !debounced,
        )
    }

    /**
     * 2. The two slash sites and the cross-session transfer must pass the live
     * field value, not the debounced string. All three sites must exist: a
     * missing site means the decision moved and this probe went stale.
     */
    private fun assertLeavingSitesPassLiveValue(input: String) {
        val skel = skeleton(input)
        val dismiss = argsOf(skel, Regex("""dismissSlashMenu\("""))
        assertTrue("the slash-menu dismiss site must exist", dismiss != null)
        assertTrue(
            "dismissSlashMenu must receive the field's live value — it echoes its argument " +
                "back as the composer value, so a stale read clears the just-dictated text",
            dismiss!!.contains("inputFieldValue") && !Regex("""\binputText\b""").containsMatchIn(dismiss),
        )

        val exec = argsOf(skel, Regex("""executeSlashCommand\("""))
        assertTrue("the slash-command site must exist", exec != null)
        val execArg = topLevelSplit(exec!!).last()
        assertTrue(
            "executeSlashCommand must receive the field's live value as its current input " +
                "(got `$execArg`)",
            execArg.contains("inputFieldValue") && !Regex("""\binputText\b""").containsMatchIn(execArg),
        )

        val pending = argsOf(skel, Regex("""(?<![\w])PendingTransfer\("""))
        assertTrue("the cross-session transfer payload must exist", pending != null)
        val payload = Regex("""inputText\s*=\s*([^,]*)""").find(pending!!)?.groupValues?.get(1)?.trim()
        assertTrue(
            "PendingTransfer must carry the field's live text — after a move the debounced " +
                "string is the empty one the move just cleared (got `$payload`)",
            payload != null && payload.contains("inputFieldValue") &&
                !Regex("""\binputText\b""").containsMatchIn(payload),
        )
    }

    /**
     * 3. Every site that acts on (or clears) the composer must drop the pending
     * burst flush first, so the 150 ms snapshot cannot commit over the value the
     * site just wrote — inline, or through a shared `() -> Unit` helper.
     */
    private fun assertBurstFlushDroppedAtLeavingSites(input: String) {
        val skel = skeleton(input)
        val helper = Regex("""val (\w+): \(\) -> Unit = \{""").findAll(skel)
            .firstOrNull { braceSlice(skel, it.range.last).contains("imeBurstJob?.cancel()") }
            ?.groupValues?.get(1)
        val sites = listOf(
            "dismissSlashMenu(" to Regex("""dismissSlashMenu\("""),
            "executeSlashCommand(" to Regex("""executeSlashCommand\("""),
            "PendingTransfer(" to Regex("""(?<![\w])PendingTransfer\("""),
        )
        for ((label, re) in sites) {
            val m = re.find(skel)
            assertTrue("$label site must exist (a missing site = the decision moved)", m != null)
            val block = enclosingBlock(skel, m!!.range.last)
            val ok = (helper != null && block.contains("$helper()")) ||
                block.contains("imeBurstJob?.cancel()")
            assertTrue(
                "$label must drop the pending IME-burst flush before it acts on the " +
                    "composer (cancel the job, or call the shared cancel helper)",
                ok,
            )
        }
    }

    /**
     * 4. [dv23-B3] The two `@`-mention completion sites must drop the pending
     * IME burst before they commit the completed text. A paste containing
     * "@doc" is still buffered for 150 ms; the flush compares its snapshot
     * against the completed text, sees a difference and commits the
     * UN-completed text back — silently undoing the mention the user just
     * picked.
     */
    private fun assertMentionSitesDropPendingBurst(input: String) {
        val skel = skeleton(input)
        val helper = Regex("""val (\w+): \(\) -> Unit = \{""").findAll(skel)
            .firstOrNull { braceSlice(skel, it.range.last).contains("imeBurstJob?.cancel()") }
            ?.groupValues?.get(1)
        val sites = listOf(
            "selectMention(" to Regex("""selectMention\("""),
            "executeSelectedMention(" to Regex("""executeSelectedMention\("""),
        )
        for ((label, re) in sites) {
            val m = re.find(skel)
            assertTrue("$label site must exist (a missing site = the decision moved)", m != null)
            val block = enclosingBlock(skel, m!!.range.last)
            val ok = (helper != null && block.contains("$helper()")) ||
                block.contains("imeBurstJob?.cancel()")
            assertTrue(
                "$label must drop the pending IME-burst flush before it commits the " +
                    "completed mention — otherwise the 150 ms snapshot overwrites the " +
                    "completion and the picked @file silently reverts",
                ok,
            )
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `the button's content decision reads the live field value`() {
        assertButtonDecisionIsLive(source(inputFile))
    }

    @Test
    fun `the slash and transfer sites pass the live field value`() {
        assertLeavingSitesPassLiveValue(source(inputFile))
    }

    @Test
    fun `every leaving site drops the pending burst flush first`() {
        assertBurstFlushDroppedAtLeavingSites(source(inputFile))
    }

    /** [dv23-B3] The two @-mention completion sites drop the pending burst too. */
    @Test
    fun `the mention completion sites drop the pending burst flush first`() {
        assertMentionSitesDropPendingBurst(source(inputFile))
    }

    /** [dv23-B3] Negative control: a mention site that keeps the pending job. */
    @Test
    fun `negative control - a mention site that keeps the pending burst fails the probe`() {
        val src = source(inputFile)
        val mutated = src.replace(
            "                                                cancelPendingImeBurst()\n" +
                "                                                val (newText, newCaret) = viewModel.selectMention(",
            "                                                val (newText, newCaret) = viewModel.selectMention(",
        )
        Assume.assumeTrue("the mention site must exist to be rewritten", mutated != src)
        var failed = false
        try {
            assertMentionSitesDropPendingBurst(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue(
            "the probe must fail once a mention site keeps the pending 150 ms job — the " +
                "flush would overwrite the just-completed @file",
            failed,
        )
    }

    /** The executable premise: a burst really does leave the committed string behind. */
    @Test
    fun `a large IME burst is debounced, so the committed string lags the field`() {
        assertTrue(
            "a >8-char single edit must be classified as a burst (this is why the " +
                "committed ViewModel string is empty while the field already shows text)",
            shouldDebounceImeBurst("", "a dictated sentence of some length"),
        )
        assertTrue(
            "ordinary typing must NOT be debounced, so the lag is burst-only",
            !shouldDebounceImeBurst("hello worl", "hello world"),
        )
        assertTrue(
            "a pending burst commits only while the field still holds the snapshot",
            shouldCommitImeBurst("dictated text", "", "dictated text"),
        )
    }

    // ── negative controls ────────────────────────────────────────────────────

    @Test
    fun `negative control - a debounced button flag fails the live-value probe`() {
        val mutated = source(inputFile).replace(
            "val hasContent = canSwipeSendNow()",
            "val hasContent = inputText.isNotBlank() || attachments.isNotEmpty()",
        )
        Assume.assumeTrue("the button flag must exist to be rewritten", mutated != source(inputFile))
        var failed = false
        try {
            assertButtonDecisionIsLive(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the probe must fail once the button reads the debounced string", failed)
    }

    @Test
    fun `negative control - the baseline hasText hop also fails the live-value probe`() {
        val src = source(inputFile)
        val mutated = src.replace(
            "val hasContent = canSwipeSendNow()",
            "val hasText = inputText.isNotBlank()\n" +
                "                    val hasContent = hasText || attachments.isNotEmpty()",
        )
        Assume.assumeTrue("the button flag must exist to be rewritten", mutated != src)
        var failed = false
        try {
            assertButtonDecisionIsLive(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue(
            "the probe must follow the `val` hop — the shipped defect read the debounced " +
                "string one hop away from the flag",
            failed,
        )
    }

    @Test
    fun `negative control - a debounced dismiss argument fails the site probe`() {
        val src = source(inputFile)
        val mutated = src.replace(
            "dismissSlashMenu(inputFieldValue.text)",
            "dismissSlashMenu(inputText)",
        )
        Assume.assumeTrue("the dismiss site must exist to be rewritten", mutated != src)
        var failed = false
        try {
            assertLeavingSitesPassLiveValue(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the site probe must fail once an argument is the debounced string", failed)
    }

    @Test
    fun `negative control - a missing burst cancel fails the flush probe`() {
        val src = source(inputFile)
        val marker = "onSelect = { targetId ->"
        val idx = src.indexOf(marker)
        Assume.assumeTrue("the transfer site must exist to be rewritten", idx >= 0)
        val tail = src.substring(idx)
        val cut = tail.replaceFirst("cancelPendingImeBurst()", "")
        Assume.assumeTrue("the transfer must drop the flush before the rewrite", cut != tail)
        val mutated = src.substring(0, idx) + cut
        var failed = false
        try {
            assertBurstFlushDroppedAtLeavingSites(mutated)
        } catch (_: AssertionError) {
            failed = true
        }
        assertTrue("the flush probe must fail once a leaving site keeps the pending job", failed)
    }

    // ── source helpers (same provenance as ComposerSendSwipeTest) ────────────

    private fun source(rel: String): String = sourceFile(rel).readText()

    /** Arguments of the first call matching [re], or null when it is absent. */
    private fun argsOf(skel: String, re: Regex): String? {
        val m = re.find(skel) ?: return null
        val open = m.range.last
        var depth = 0
        var i = open
        while (i < skel.length) {
            when (skel[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return skel.substring(open + 1, i)
                }
            }
            i++
        }
        return null
    }

    /** Split an argument list on top-level commas only. */
    private fun topLevelSplit(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var cur = 0
        args.forEachIndexed { i, c ->
            when {
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ',' && depth == 0 -> {
                    out.add(args.substring(cur, i).trim())
                    cur = i + 1
                }
            }
        }
        out.add(args.substring(cur).trim())
        return out
    }

    /**
     * Innermost `{…}` block enclosing [idx].
     *
     * [fix/move-to-capsule-empty-1116] This used to record the LAST brace opened
     * before [idx] and never restore the previously-open one when that block
     * closed. Any SIBLING block ahead of the site (a guard
     * `if (…) { … }`, a `when` arm, a nested lambda) therefore became the
     * "enclosing" block, and the probe reported "must drop the pending burst
     * flush" at a site whose own block did call it. Track the open braces on a
     * stack so the answer is the block the site actually sits in.
     */
    private fun enclosingBlock(skel: String, idx: Int): String {
        val open = java.util.ArrayDeque<Int>()
        for (i in 0 until idx) {
            when (skel[i]) {
                '{' -> open.addLast(i)
                '}' -> if (open.isNotEmpty()) open.removeLast()
            }
        }
        return if (open.isEmpty()) "" else braceSlice(skel, open.peekLast())
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
     * Comment- and string-blanked copy of [src] with LENGTH AND OFFSETS
     * PRESERVED, so slices can be taken from the original text using offsets
     * found in the skeleton (a `${…}` template or a `//` inside a URL would
     * otherwise desync brace counting).
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
                    stack[stack.lastIndex] = 'c' to (frame.second - 1); out.append(c); i++
                    continue
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
