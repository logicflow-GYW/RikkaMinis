package com.rikkaminis.app.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1029 A] cold-start deep-link dispatch shape.
 *
 * `minis://views/alarm` was a silent no-op on cold start: `DeepLinkAction`
 * carries 11 subclasses, `AppNavigation`'s `when (initialDeepLink)` dispatch
 * named 7 of them and swallowed the rest in `else -> {}` — so a subclass that
 * nobody remembered to wire fell into a hole that neither the compiler nor
 * any test could see.
 *
 * Two real ingredients, deliberately combined:
 *
 *  1. the action domain is enumerated by REFLECTION over the real compiled
 *     [DeepLinkAction] — adding a subclass changes this test's input without
 *     anyone editing it;
 *  2. the branch table is read from the REAL `AppNavigation.kt` text and
 *     matched by case name.
 *
 * Neither alone is enough: reflection without the source can't see the
 * dispatch, and the source alone can't tell that a name it spells is a
 * subclass that exists. Each check has an in-test negative control so a probe
 * that has quietly stopped looking fails loudly
 * (`ScreenshotCaptureShapeTest` precedent).
 */
class ColdStartDispatchShapeTest {

    private val navRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"

    /**
     * The eleven audited subclasses, written out as literals on purpose:
     * deriving the list from the source under test would let a dropped branch
     * drag the expectation down with it.
     */
    private val expectedSubclasses = listOf(
        "CreateEnvironmentVariable", "NewCameraChat", "NewChat",
        "OpenAlarmList", "OpenHtmlPreview", "OpenPermissionSettings",
        "OpenSession", "OpenSettingsScreen", "OpenShare",
        "OpenTerminal", "Unknown",
    )

    // ── 1. the sealed family ─────────────────────────────────────────────

    @Test
    fun `sealed family is exactly the eleven audited subclasses`() {
        val names = DeepLinkAction::class.java.declaredClasses
            .filter { DeepLinkAction::class.java.isAssignableFrom(it) }
            .map { it.simpleName }
            .filter { it != "Companion" }
            .sorted()
        assertEquals(expectedSubclasses, names)
    }

    // ── 2. the cold-start dispatch ───────────────────────────────────────

    @Test
    fun `cold-start dispatch names every subclass, with no catch-all else`() {
        val src = readRepoFile(navRel)
        assertEquals(emptyList<String>(), uncoveredBy(src))
        assertTrue("a catch-all `else ->` is back", !hasCatchAll(src))
    }

    @Test
    fun `OpenAlarmList branch launches the system Clock`() {
        val block = dispatchBlock(readRepoFile(navRel))
        val alarm = Regex(
            "is\\s+DeepLinkAction\\.OpenAlarmList\\s*->\\s*\\{[^}]*AlarmClock\\.ACTION_SHOW_ALARMS",
        )
        assertTrue(
            "is DeepLinkAction.OpenAlarmList must launch AlarmClock.ACTION_SHOW_ALARMS",
            alarm.containsMatchIn(block),
        )
    }

    // ── 3. negative controls: the probe must reject a broken dispatch ────
    //
    // Written shape-agnostically: each control calibrates against the tree it
    // is reading, so running the suite against a PRE-da1029 tree reports only
    // the substantive failures instead of also failing the controls for
    // presupposing the fixed shape.

    @Test
    fun `probe rejects a dispatch that does not name a subclass`() {
        // Operate on the comment-free carrier: the dispatch block is then a
        // contiguous substring, so a mutation can be confined to it (a whole-
        // file rename would also hit the neighbouring resolver's NewChat case).
        val code = commentFreeLines(readRepoFile(navRel))
        val baseline = uncoveredBy(code).toSet()
        var exercised = 0
        for (name in expectedSubclasses) {
            // OpenTerminal is the case that IDENTIFIES the dispatch block —
            // renaming it would make the probe select nothing, which the
            // fail-closed guard already covers as its own failure mode.
            if (name == "OpenTerminal") continue
            val block = dispatchBlock(code)
            val mutated = block.replace(
                "is DeepLinkAction.$name ->",
                "is DeepLinkAction.NotASubclass ->",
            )
            if (mutated == block) continue // that branch is absent on this tree
            exercised++
            val broken = code.replace(block, mutated)
            assertTrue(
                "renaming the $name case must show up as uncovered",
                name in uncoveredBy(broken),
            )
            assertEquals(baseline + name, uncoveredBy(broken).toSet())
        }
        assertTrue("no case label was exercised — the probe looked at nothing", exercised > 0)
    }

    @Test
    fun `probe rejects a restored catch-all else`() {
        val code = commentFreeLines(readRepoFile(navRel))
        val terminal = "            is DeepLinkAction.OpenShare,\n" +
            "            is DeepLinkAction.Unknown,\n            null -> {}"
        org.junit.Assume.assumeTrue(
            "tree has no named terminal cases to collapse (pre-da1029 shape)",
            code.contains(terminal),
        )
        val broken = code.replace(terminal, "            else -> {}")
        assertTrue(hasCatchAll(broken))
        assertEquals(
            uncoveredBy(code) + listOf("OpenShare", "Unknown"),
            uncoveredBy(broken),
        )
    }

    @Test
    fun `probe ignores a neighbouring resolver that legitimately uses else`() {
        // AppNavigation also has a `when (initialDeepLink)` startDestination
        // resolver whose `else -> null` is deliberate. The probe must pick the
        // DISPATCH block (selected by its dispatch-only OpenTerminal case) and
        // stay silent about the resolver.
        val code = commentFreeLines(readRepoFile(navRel))
        val baseline = uncoveredBy(code)
        val withResolver = code +
            "\n\nprivate fun probeOnlyResolver(initialDeepLink: DeepLinkAction?): String? =\n" +
            "    when (initialDeepLink) {\n" +
            "        is DeepLinkAction.NewChat -> null\n" +
            "        else -> null\n" +
            "    }\n"
        assertEquals(baseline, uncoveredBy(withResolver))
        assertEquals(hasCatchAll(code), hasCatchAll(withResolver))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun uncoveredBy(src: String): List<String> {
        val block = dispatchBlock(src)
        return expectedSubclasses.filterNot { block.contains("DeepLinkAction.$it") }
    }

    private fun hasCatchAll(src: String): Boolean =
        Regex("\\belse\\s*->").containsMatchIn(dispatchBlock(src))

    /**
     * The cold-start dispatch block: the `when (initialDeepLink)` whose body
     * carries the dispatch-only `OpenTerminal` case. Fails closed (throws) when
     * no such block exists, so a rename can never turn the probe into a no-op.
     */
    private fun dispatchBlock(src: String): String {
        val blocks = braceBlocks(commentFreeLines(src), "when (initialDeepLink)")
        val dispatches = blocks.filter { it.contains("DeepLinkAction.OpenTerminal") }
        assertEquals("exactly one cold-start dispatch block", 1, dispatches.size)
        return dispatches[0]
    }

    private fun commentFreeLines(src: String): String =
        src.split("\n").filterNot { line ->
            val s = line.trim()
            s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
        }.joinToString("\n")

    /** Brace-bounded substrings of every `when (…)` whose header is [header]. */
    private fun braceBlocks(code: String, header: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val idx = code.indexOf(header, i)
            if (idx < 0) return out
            val brace = code.indexOf('{', idx)
            if (brace < 0) return out
            var depth = 0
            var j = brace
            while (j < code.length) {
                val c = code[j]
                if (c == '"') { j = skipString(code, j); continue }
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) { out += code.substring(idx, j + 1); break }
                }
                j++
            }
            i = brace + 1
        }
    }

    private fun skipString(code: String, start: Int): Int {
        if (code.startsWith("\"\"\"", start)) {
            val k = code.indexOf("\"\"\"", start + 3)
            return if (k < 0) code.length else k + 3
        }
        var k = start + 1
        while (k < code.length) {
            if (code[k] == '\\') { k += 2; continue }
            if (code[k] == '"' || code[k] == '\n') return k + 1
            k++
        }
        return code.length
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
