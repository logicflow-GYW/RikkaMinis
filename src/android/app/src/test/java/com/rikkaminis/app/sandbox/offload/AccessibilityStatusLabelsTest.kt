package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1025 ISSUE-03] The a11y sub-action status line: the verbs the
 * dispatcher accepts must be the verbs the capsule can name.
 *
 * Before: `statusForSubAction` kept its own hand-written tables, and all three
 * had drifted from the dispatcher — `tap` dispatched node/xy/text/id while the
 * table knew by-text/by-id/by-node/at, `input` dispatched text/clear/key while
 * the table knew set/clear, `scroll` dispatched node/xy/to-text while the table
 * knew forward/backward/up/down. Not one real call matched, so every a11y
 * operation showed the generic "a11y_cli: tap" and the visual feedback the
 * overlay exists to give was blank 100 % of the time.
 *
 * The table is Android-free (`A11yStatusLabels` is a plain object), so the
 * labels are pinned here by literal, and the source shape is pinned against the
 * dispatcher's own `when` blocks — the same two-sided check
 * `AccessibilityTapTextTest` uses for `tap text`.
 */
class AccessibilityStatusLabelsTest {

    private val handlerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/AccessibilityOffloadHandler.kt"

    // ── behaviour: what the capsule reads ────────────────────────────────

    @Test
    fun `tap verbs render the label the agent reads`() {
        assertEquals("a11y_cli: tap node n1", A11yStatusLabels.labelFor(A11yStatusLabels.TAP, "node", "n1"))
        assertEquals("a11y_cli: tap at (100)", A11yStatusLabels.labelFor(A11yStatusLabels.TAP, "xy", "100"))
        assertEquals("a11y_cli: tap \"Login\"", A11yStatusLabels.labelFor(A11yStatusLabels.TAP, "text", "Login"))
        assertEquals(
            "a11y_cli: tap #com.app:id/ok",
            A11yStatusLabels.labelFor(A11yStatusLabels.TAP, "id", "com.app:id/ok"),
        )
    }

    @Test
    fun `input verbs render the label the agent reads`() {
        assertEquals("a11y_cli: input text", A11yStatusLabels.labelFor(A11yStatusLabels.INPUT, "text", "Hello"))
        assertEquals("a11y_cli: clearing input", A11yStatusLabels.labelFor(A11yStatusLabels.INPUT, "clear", null))
        assertEquals("a11y_cli: input key BACK", A11yStatusLabels.labelFor(A11yStatusLabels.INPUT, "key", "BACK"))
    }

    @Test
    fun `scroll verbs render the label the agent reads`() {
        assertEquals("a11y_cli: scroll node n1", A11yStatusLabels.labelFor(A11yStatusLabels.SCROLL, "node", "n1"))
        assertEquals("a11y_cli: scroll at (100)", A11yStatusLabels.labelFor(A11yStatusLabels.SCROLL, "xy", "100"))
        assertEquals(
            "a11y_cli: scrolling to \"Log\"",
            A11yStatusLabels.labelFor(A11yStatusLabels.SCROLL, "to-text", "Log"),
        )
    }

    @Test
    fun `a label without a target still renders and leaves no placeholder`() {
        assertEquals("a11y_cli: tap at ()", A11yStatusLabels.labelFor(A11yStatusLabels.TAP, "xy", null))
        assertEquals("a11y_cli: tap \"\"", A11yStatusLabels.labelFor(A11yStatusLabels.TAP, "text", null))
        // The target-free labels ignore the placeholder entirely.
        assertEquals("a11y_cli: input text", A11yStatusLabels.labelFor(A11yStatusLabels.INPUT, "text", null))
    }

    /**
     * The verbs the old table listed are not verbs this dispatcher can run. They
     * must stay out: keeping them alongside the real ones is what made the
     * capsule claim `scroll forward` for a `scroll node` call.
     */
    @Test
    fun `the invented verbs the old tables listed are gone`() {
        for (v in listOf("by-text", "by-id", "by-node", "at")) {
            assertNull("tap must not name '$v'", A11yStatusLabels.labelFor(A11yStatusLabels.TAP, v, "x"))
        }
        assertNull("input must not name 'set'", A11yStatusLabels.labelFor(A11yStatusLabels.INPUT, "set", "x"))
        for (v in listOf("forward", "backward", "up", "down")) {
            assertNull("scroll must not name '$v'", A11yStatusLabels.labelFor(A11yStatusLabels.SCROLL, v, "x"))
        }
        assertNull("no verb at all keeps the caller's fallback", A11yStatusLabels.labelFor(A11yStatusLabels.TAP, null, "x"))
    }

    // ── shape: the tables and the dispatcher are one list ────────────────

    @Test
    fun `every dispatched verb has a label and no label is undispatched`() {
        assertTablesMatchDispatch(readRepoFile(handlerRel))
    }

    @Test
    fun `the compiled tables are the ones the source declares`() {
        val src = readRepoFile(handlerRel)
        assertEquals(mapKeys(src, "TAP"), A11yStatusLabels.TAP.keys)
        assertEquals(mapKeys(src, "INPUT"), A11yStatusLabels.INPUT.keys)
        assertEquals(mapKeys(src, "SCROLL"), A11yStatusLabels.SCROLL.keys)
    }

    @Test
    fun `statusForSubAction renders through the shared tables`() {
        val body = memberBody(readRepoFile(handlerRel), "private fun statusForSubAction(")
        assertTrue(
            "statusForSubAction must render through labelFor() — a second hand-written table is what drifted",
            body.contains("A11yStatusLabels.labelFor("),
        )
        for (table in listOf("TAP", "INPUT", "SCROLL")) {
            assertTrue(
                "statusForSubAction must render the $table table, not its own copy of the verbs",
                body.contains("A11yStatusLabels.$table"),
            )
        }
    }

    // ── negative controls: the shape probe must reject drift both ways ────

    @Test
    fun `probe rejects a dispatched verb the table cannot name`() {
        val src = readRepoFile(handlerRel)
        val mutated = src.replace("        \"xy\"   to \"a11y_cli: tap at (%s)\",\n", "")
        assertTrue("mutation did not apply — the fixture is stale", mutated != src)
        assertRejects(src, mutated)
    }

    @Test
    fun `probe rejects a status verb the dispatcher cannot run`() {
        val src = readRepoFile(handlerRel)
        val mutated = src.replace(
            "        \"to-text\" to \"a11y_cli: scrolling to \\\"%s\\\"\",\n",
            "        \"to-text\" to \"a11y_cli: scrolling to \\\"%s\\\"\",\n" +
                "        \"forward\" to \"a11y_cli: scroll forward\",\n",
        )
        assertTrue("mutation did not apply — the fixture is stale", mutated != src)
        assertRejects(src, mutated)
    }

    private fun assertRejects(pristine: String, mutated: String) {
        // A negative control only means something if the probe accepts the real
        // source first — otherwise it is "rejects everything", which would pass
        // against any tree, including the pre-fix one.
        assertTablesMatchDispatch(pristine)
        var rejected = false
        try {
            assertTablesMatchDispatch(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts mutated source", rejected)
    }

    // ── source readers ───────────────────────────────────────────────────

    /**
     * Slice a member (or the class tail) starting at [signature] up to the next
     * member declaration or the class's closing brace.
     */
    private fun memberBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found in $handlerRel (probe anchor lost)", start >= 0)
        val rest = src.substring(start + signature.length)
        val stops = listOf("\n    private fun ", "\n    internal fun ", "\n    fun ", "\n}")
            .map { rest.indexOf(it) }
            .filter { it >= 0 }
        assertTrue("no member/class boundary after $signature (probe anchor lost)", stops.isNotEmpty())
        return src.substring(start, start + signature.length + stops.min())
    }

    /**
     * The `"<verb>" ->` literals of a dispatch block, e.g.
     * `private fun tapSub(` → {node, xy, text, id}. The `else ->` arm's message
     * string does not match: its quote is followed by `)`, not `->`.
     */
    private fun dispatchedVerbs(src: String, subFn: String): Set<String> =
        Regex("\"([a-z][a-z0-9-]*)\"\\s*->")
            .findAll(memberBody(src, "private fun $subFn("))
            .map { it.groupValues[1] }
            .toSet()

    /** The keys of one `val <table>: Map<String, String> = mapOf(...)` literal. */
    private fun mapKeys(src: String, table: String): Set<String> {
        val anchor = src.indexOf("val $table: Map<String, String> = mapOf(")
        assertTrue("A11yStatusLabels.$table declaration not found (probe anchor lost)", anchor >= 0)
        val open = src.indexOf('(', anchor)
        var depth = 0
        var close = -1
        for (i in open until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) { close = i; break }
                }
            }
        }
        assertTrue("unbalanced mapOf( for $table", close > open)
        val body = src.substring(open, close)
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val keys = Regex("\"([^\"]+)\"\\s+to\\s+\"").findAll(body).map { it.groupValues[1] }.toSet()
        assertTrue("no entries parsed out of $table (probe anchor lost)", keys.isNotEmpty())
        return keys
    }

    private fun assertTablesMatchDispatch(src: String) {
        assertEquals(
            "tap: the dispatched verbs and the TAP table keys must be the same set",
            dispatchedVerbs(src, "tapSub"),
            mapKeys(src, "TAP"),
        )
        assertEquals(
            "input: the dispatched verbs and the INPUT table keys must be the same set",
            dispatchedVerbs(src, "inputSub"),
            mapKeys(src, "INPUT"),
        )
        assertEquals(
            "scroll: the dispatched verbs and the SCROLL table keys must be the same set",
            dispatchedVerbs(src, "scrollSub"),
            mapKeys(src, "SCROLL"),
        )
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
