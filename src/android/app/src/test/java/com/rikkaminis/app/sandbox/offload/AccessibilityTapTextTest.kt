package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1017 G2 + G3] `android-a11y-cli tap text` — the argument shape and
 * the outcome shape.
 *
 * ## G2: `--contains` was not declared boolean
 *
 * `OffloadArgs` treats an undeclared `--key` as a key/value pair, so
 * `tap text --contains "Dec 4"` consumed the search string as the flag's value:
 * `positional[2]` became null and `tapText` bailed out with
 * `missing <text>` — every `--contains` tap failed, 100 % of the time, and the
 * failure looked like a caller mistake. The declaration set is read out of the
 * production file here, so the probe cannot drift from it, and the whole file's
 * `hasFlag(...)` names are checked against it (the same family as the
 * `minis-sessions-cli --async` bug).
 *
 * ## G3: `tap text` reported success unconditionally
 *
 * Both attempt branches threw their result away and the call returned `ok`
 * regardless; a node that advertises `clickable` but refuses ACTION_CLICK, and
 * a coordinate tap the system cancelled, both came back as a landed tap. The
 * body must now route through [TapTextFlow] and must be able to return an error
 * after the fallback.
 */
class AccessibilityTapTextTest {

    private val handlerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/AccessibilityOffloadHandler.kt"

    // ── G2: argument shape ───────────────────────────────────────────────

    @Test
    fun `--contains is a flag and the search text stays positional`() {
        val args = OffloadArgs(
            listOf("tap", "text", "--contains", "Dec 4"),
            booleanFlags = declaredBooleanFlags(readRepoFile(handlerRel)),
        )
        assertEquals(listOf("tap", "text", "Dec 4"), args.positional)
        assertTrue(args.hasFlag("contains"))
        assertNull("--contains must not swallow the search string", args.get("contains"))
    }

    @Test
    fun `without the declaration the search text is swallowed — the bug this pins`() {
        val declared = declaredBooleanFlags(readRepoFile(handlerRel))
        val args = OffloadArgs(
            listOf("tap", "text", "--contains", "Dec 4"),
            booleanFlags = declared - "contains",
        )
        // Exactly the pre-fix parse: positional[2] is gone, so tapText would
        // answer "missing <text>" for an argument the caller did pass.
        assertEquals(listOf("tap", "text"), args.positional)
        assertNull(args.positional.getOrNull(2))
        assertEquals("Dec 4", args.get("contains"))
        assertFalse(args.hasFlag("contains"))
    }

    @Test
    fun `every flag name read in this handler is declared boolean`() {
        val src = readRepoFile(handlerRel)
        val read = flagNamesRead(src)
        assertTrue("probe anchor lost: no hasFlag(...) found", read.isNotEmpty())
        assertEquals(
            "flag read via hasFlag() but never declared in booleanFlags — " +
                "OffloadArgs will swallow the next token as its value",
            emptySet<String>(),
            read - declaredBooleanFlags(src),
        )
    }

    // ── G3: outcome shape ────────────────────────────────────────────────

    @Test
    fun `tapText reports what actually happened`() {
        assertTapTextShape(tapTextBody(readRepoFile(handlerRel)))
    }

    @Test
    fun `probe rejects the discarded performAction result`() {
        val body = tapTextBody(readRepoFile(handlerRel))
        val mutated = body.replace(
            "if (TapTextFlow.plan(n.isClickable, n.performAction(AccessibilityNodeInfo.ACTION_CLICK))",
            "if (n.isClickable) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);\n        if (true",
        )
        assertFalse(mutated.contains("TapTextFlow.plan("))
        assertRejects(body, mutated)
    }

    @Test
    fun `probe rejects an unconditional ok after the fallback`() {
        val body = tapTextBody(readRepoFile(handlerRel))
        val mutated = body.replace(
            """        return err(args, "ACTION_FAILED",
            "tap text \"${'$'}text\": node did not accept ACTION_CLICK and the coordinate tap failed")""",
            "",
        )
        assertFalse(mutated.contains("ACTION_FAILED"))
        assertRejects(body, mutated)
    }

    private fun assertTapTextShape(body: String) {
        assertTrue("tapText must decide through TapTextFlow.plan", body.contains("TapTextFlow.plan("))
        assertTrue(
            "tapText must read the coordinate tap's own outcome",
            body.contains("TapTextFlow.fallbackSucceeded("),
        )
        assertTrue(
            "a refused ACTION_CLICK plus a failed fallback must be an error (G3)",
            body.contains("""err(args, "ACTION_FAILED""""),
        )
        assertFalse(
            "the discarded performAction result is back",
            body.contains("if (n.isClickable) n.performAction("),
        )
        val lastOk = body.lastIndexOf("return ok(")
        assertTrue("tapText no longer reports a successful click", lastOk >= 0)
        val tail = body.substring(lastOk)
        assertTrue("the coordinate fallback must still be attempted", tail.contains("tapXYRaw("))
        assertTrue("the fallback outcome must be checked", tail.contains("fallbackSucceeded("))
        assertTrue("a failed fallback must return an error", tail.contains("return err(args,"))
    }

    private fun assertRejects(pristine: String, mutated: String) {
        // A negative control only means something if the probe accepts the real
        // source first — otherwise it is "rejects everything", which would pass
        // against any tree, including the pre-fix one.
        assertTapTextShape(pristine)
        var rejected = false
        try {
            assertTapTextShape(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts mutated source", rejected)
    }

    // ── source readers ───────────────────────────────────────────────────

    /** Slice `private fun tapText(...)` up to the next member declaration. */
    private fun tapTextBody(src: String): String {
        val start = src.indexOf("private fun tapText(")
        assertTrue("tapText not found in $handlerRel", start >= 0)
        val end = src.indexOf("\n    private fun findByTextOrDesc(", start)
        assertTrue("findByTextOrDesc must follow tapText (probe anchor lost)", end > start)
        return src.substring(start, end)
    }

    /**
     * Slice the `booleanFlags = setOf(...)` literal with paren balance (comment
     * lines inside the set are ignored so a quoted example cannot leak in).
     */
    private fun declaredBooleanFlags(src: String): Set<String> {
        val anchor = src.indexOf("booleanFlags = setOf(")
        assertTrue("booleanFlags declaration not found", anchor >= 0)
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
        assertTrue("unbalanced booleanFlags setOf(", close > open)
        val body = src.substring(open, close)
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val names = Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toSet()
        assertTrue("no flag names parsed out of booleanFlags", names.isNotEmpty())
        return names
    }

    /** Every literal passed to `hasFlag(...)` in [src]. */
    private fun flagNamesRead(src: String): Set<String> =
        Regex("hasFlag\\(([^)]*)\\)").findAll(src)
            .flatMap { Regex("\"([^\"]+)\"").findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }
            .toSet()

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
