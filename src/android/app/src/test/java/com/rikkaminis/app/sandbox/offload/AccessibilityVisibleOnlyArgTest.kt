package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1038 A-02] `android-a11y-cli ui dump --visible-only <bool>`.
 *
 * Before: `"visible-only"` was declared in `booleanFlags`, and a key declared
 * boolean is added to the parser's flag set WITHOUT binding a value. So
 *
 *   `ui dump --visible-only false`  →  flag `visible-only`, `false` a stray
 *                                      positional, `values` has no key
 *
 * `getBool("visible-only")` reads only `values`, answered `null`, and the call
 * site's `?: true` silently inverted the caller's request: "hide the invisible
 * nodes" became "show everything", with no error and no difference in the
 * output envelope. A malformed value (`--visible-only maybe`) fell into the same
 * default instead of being refused.
 *
 * The option is now a VALUE-taking one (declaration removed), the read is the
 * three-way [VisibleOnlyFlag.Read], and the branch below pins both halves: the
 * Android-free classifier by literal, and the `uiDump` body by source shape —
 * the same two-sided style [AccessibilityTapTextTest] uses for `tap text`.
 */
class AccessibilityVisibleOnlyArgTest {

    private val handlerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/AccessibilityOffloadHandler.kt"

    // ── the decision: written literals, never derived from the production set ──

    @Test
    fun `every false spelling reads as false`() {
        for (word in listOf("false", "False", "FALSE", "0", "no", "No", "NO")) {
            assertEquals(
                "false spelling \"$word\" must not read as true",
                VisibleOnlyFlag.Read.Value(false),
                VisibleOnlyFlag.resolve(word),
            )
        }
    }

    @Test
    fun `every true spelling reads as true`() {
        for (word in listOf("true", "True", "TRUE", "1", "yes", "Yes", "YES")) {
            assertEquals(
                "true spelling \"$word\" must not read as false",
                VisibleOnlyFlag.Read.Value(true),
                VisibleOnlyFlag.resolve(word),
            )
        }
    }

    @Test
    fun `nothing spelled is absent — the documented default applies`() {
        assertEquals(VisibleOnlyFlag.Read.Absent, VisibleOnlyFlag.resolve(null))
    }

    @Test
    fun `a malformed value is refused and keeps the caller's spelling`() {
        for (word in listOf("maybe", "", "2", "-1", "tru", "true ", " on", "yes!")) {
            assertEquals(
                "\"$word\" is not a boolean — it must be refused, not read as a value",
                VisibleOnlyFlag.Read.Invalid(word),
                VisibleOnlyFlag.resolve(word),
            )
        }
    }

    @Test
    fun `a legal spelling means exactly what OffloadArgs_getBool means`() {
        // One contract seen from two sides: a drift in either direction is what
        // puts a caller's `--visible-only=0` at odds with its spaced twin.
        for ((word, expected) in listOf(
            "true" to true, "TRUE" to true, "1" to true, "yes" to true, "Yes" to true,
            "false" to false, "FALSE" to false, "0" to false, "no" to false, "NO" to false,
        )) {
            assertEquals("OffloadArgs.getBool(\"$word\")", expected, boundGetBool(word))
            assertEquals(
                "VisibleOnlyFlag.resolve(\"$word\")",
                VisibleOnlyFlag.Read.Value(expected),
                VisibleOnlyFlag.resolve(word),
            )
        }
        for (word in listOf("maybe", "2", "tru")) {
            assertNull("OffloadArgs.getBool(\"$word\") must stay null", boundGetBool(word))
            assertEquals(VisibleOnlyFlag.Read.Invalid(word), VisibleOnlyFlag.resolve(word))
        }
    }

    // ── the parse: the real parser, the real declaration set ─────────────

    @Test
    fun `--visible-only false binds the value instead of dropping it`() {
        val declared = declaredBooleanFlags(readRepoFile(handlerRel))
        assertFalse(
            "--visible-only is read as a value; declaring it boolean drops the caller's spelling",
            "visible-only" in declared,
        )
        val args = OffloadArgs(listOf("ui", "dump", "--visible-only", "false"), booleanFlags = declared)
        assertEquals(listOf("ui", "dump"), args.positional)
        assertEquals("false", args.get("visible-only"))
        assertEquals(VisibleOnlyFlag.Read.Value(false), VisibleOnlyFlag.resolve(args.get("visible-only")))
        assertFalse(args.hasFlag("visible-only"))
    }

    @Test
    fun `the equals spelling carries the same value`() {
        val declared = declaredBooleanFlags(readRepoFile(handlerRel))
        for ((argv, expected) in listOf(
            listOf("ui", "dump", "--visible-only=false") to VisibleOnlyFlag.Read.Value(false),
            listOf("ui", "dump", "--visible-only=no") to VisibleOnlyFlag.Read.Value(false),
            listOf("ui", "dump", "--visible-only=1") to VisibleOnlyFlag.Read.Value(true),
            listOf("ui", "dump", "--visible-only=maybe") to VisibleOnlyFlag.Read.Invalid("maybe"),
        )) {
            val args = OffloadArgs(argv, booleanFlags = declared)
            val label = argv.joinToString(" ")
            assertEquals(label, listOf("ui", "dump"), args.positional)
            assertEquals(label, expected, VisibleOnlyFlag.resolve(args.get("visible-only")))
        }
    }

    @Test
    fun `a bare --visible-only binds nothing and keeps the default path`() {
        val declared = declaredBooleanFlags(readRepoFile(handlerRel))
        val args = OffloadArgs(listOf("ui", "dump", "--visible-only"), booleanFlags = declared)
        assertEquals(listOf("ui", "dump"), args.positional)
        assertNull("a bare option must not invent a value", args.get("visible-only"))
        assertEquals(VisibleOnlyFlag.Read.Absent, VisibleOnlyFlag.resolve(args.get("visible-only")))
    }

    @Test
    fun `declaring it boolean is what inverted the caller's request — the bug this pins`() {
        val src = readRepoFile(handlerRel)
        // Exactly the pre-fix parse, rebuilt from the real declaration set so the
        // probe shows the consequence rather than restating the shape.
        val preFix = declaredBooleanFlags(src) + "visible-only"
        val args = OffloadArgs(listOf("ui", "dump", "--visible-only", "false"), booleanFlags = preFix)
        assertEquals(listOf("ui", "dump", "false"), args.positional)
        assertNull("the spelled value never reached `values`", args.get("visible-only"))
        assertEquals(
            "so the reader saw the same thing as 'not given'",
            VisibleOnlyFlag.Read.Absent,
            VisibleOnlyFlag.resolve(args.get("visible-only")),
        )
        // …and 'not given' answers true, the opposite of the `false` that was
        // spelled. The default itself is pinned in the source, by literal.
        assertTrue(
            "the absent/bare branch must still answer the documented default (true)",
            uiDumpBody(src).contains("VisibleOnlyFlag.Read.Absent -> true"),
        )
    }

    // ── the wiring: uiDump must read through the classifier and refuse ───

    @Test
    fun `uiDump reads the value and refuses what is not a boolean`() {
        assertUiDumpShape(uiDumpBody(readRepoFile(handlerRel)))
    }

    @Test
    fun `probe rejects the pre-fix read`() {
        assertRejects(readRepoFile(handlerRel)) { src ->
            src.replace(
                "VisibleOnlyFlag.resolve(args.get(\"visible-only\"))",
                "args.getBool(\"visible-only\")",
            )
        }
    }

    @Test
    fun `probe rejects a malformed value folded into the default`() {
        assertRejects(readRepoFile(handlerRel)) { src ->
            src.replace("is VisibleOnlyFlag.Read.Invalid ->", "is VisibleOnlyFlag.Read.Unknown ->")
        }
    }

    @Test
    fun `probe rejects a refusal that is not a usage error`() {
        assertRejects(readRepoFile(handlerRel)) { src ->
            src.replace("                exit = 2,\n", "                exit = 1,\n")
        }
    }

    // ── source readers ───────────────────────────────────────────────────

    private fun assertUiDumpShape(body: String) {
        assertTrue(
            "uiDump must read --visible-only as a VALUE",
            body.contains("VisibleOnlyFlag.resolve(args.get(\"visible-only\"))"),
        )
        assertTrue(
            "a bare/absent --visible-only must keep the documented default",
            body.contains("VisibleOnlyFlag.Read.Absent -> true"),
        )
        assertTrue(
            "a spelled boolean must be honoured",
            body.contains("is VisibleOnlyFlag.Read.Value -> read.value"),
        )
        val marker = "is VisibleOnlyFlag.Read.Invalid -> "
        val refusal = body.indexOf(marker)
        assertTrue("uiDump must refuse a malformed value", refusal >= 0)
        val tail = body.substring(refusal + marker.length)
        assertTrue("the refusal must be a returned error, not a stand-in value", tail.startsWith("return err("))
        assertTrue("the refusal must carry the caller's spelling back", tail.contains("${'$'}{read.raw}"))
        assertTrue("the refusal must be a usage error (exit 2)", tail.contains("exit = 2"))
        assertFalse(
            "getBool() answers null for BOTH 'absent' and 'garbage' — the read that inverted the request",
            body.contains("getBool(\"visible-only\")"),
        )
    }

    private fun assertRejects(src: String, mutate: (String) -> String) {
        // A negative control only means something if the probe accepts the real
        // source first — otherwise it is "rejects everything".
        assertUiDumpShape(uiDumpBody(src))
        val mutated = mutate(src)
        assertFalse("the negative control did not change the source", mutated == src)
        var rejected = false
        try {
            assertUiDumpShape(uiDumpBody(mutated))
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts the mutated source", rejected)
    }

    /** Slice `private fun uiDump(...)` up to the next member declaration. */
    private fun uiDumpBody(src: String): String {
        val start = src.indexOf("private fun uiDump(")
        assertTrue("uiDump not found in $handlerRel", start >= 0)
        val end = src.indexOf("\n    private fun walkNode(", start)
        assertTrue("walkNode must follow uiDump (probe anchor lost)", end > start)
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

    /** A one-option parse through the real parser, for the vocabulary agreement. */
    private fun boundGetBool(word: String): Boolean? =
        OffloadArgs(listOf("--k", word)).getBool("k")

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
