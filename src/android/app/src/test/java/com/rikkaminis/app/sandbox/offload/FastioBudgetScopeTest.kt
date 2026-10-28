package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * [meta1028-B05 F-10 / F-11] The two budget contracts of `minis-fastio`.
 *
 * F-11 — `--max-entries` is ONE budget per COMMAND, as the constant's own KDoc
 *        says and as `tar` already spends it. `du`/`find`/`grep` used to hand
 *        the same number to EVERY path argument, so `du a b c d e` bought five
 *        times the traversal the caller asked for, and the flag meant two
 *        different amounts of work depending on the subcommand.
 *
 * F-10 — `--limit` is a per-command match budget, and a root that arrives with
 *        NOTHING left must not be walked. `grepTree` has always done this;
 *        `findTree` did not, so `find /a /b --limit 500` read all of `/b` just
 *        to list nothing.
 *
 * Both engines are pure (no Android types), so the behavioural arms run here
 * directly. The handler-side wiring — that the SAME variable is decremented per
 * root instead of a fresh constant per call — cannot run on the JVM (it needs
 * GuestPathMapper + a request), so it is asserted against the real source with
 * a negative control.
 */
class FastioBudgetScopeTest {

    companion object {
        private const val HANDLER_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioOffloadHandler.kt"
    }

    // ── F-10: a spent match budget walks nothing ────────────────────────────

    /** root/{a.txt, b.log, sub/{c.log, d.txt}} — 4 files, 2 dirs. */
    private fun findFixture(): Path {
        val root = Files.createTempDirectory("fastio-budget-find")
        Files.write(root.resolve("a.txt"), "alpha\n".toByteArray())
        Files.write(root.resolve("b.log"), "beta\n".toByteArray())
        val sub = Files.createDirectory(root.resolve("sub"))
        Files.write(sub.resolve("c.log"), "gamma\n".toByteArray())
        Files.write(sub.resolve("d.txt"), "delta\n".toByteArray())
        return root
    }

    @Test
    fun `find with a zero budget lists nothing and enters no directory`() {
        val root = findFixture()
        val out = FindOutcome()

        findTree(root, "/ws", null, null, limit = 0, maxEntries = 1_000, out = out)

        assertTrue("a spent budget must answer as capped", out.matchesCapped)
        assertTrue("nothing may be listed", out.matches.isEmpty())
        assertEquals("the walk must not have happened", 0L, out.dirs)
        assertEquals("the walk must not have happened", 0L, out.files)
        assertEquals("…so nothing matched either", 0L, out.matched)
        assertFalse("and it is not a truncation", out.truncated)
    }

    @Test
    fun `find with a negative budget behaves like zero`() {
        val root = findFixture()
        val out = FindOutcome()

        findTree(root, "/ws", null, null, limit = -1, maxEntries = 1_000, out = out)

        assertTrue(out.matchesCapped)
        assertEquals(0L, out.dirs)
        assertEquals(0L, out.files)
    }

    @Test
    fun `grep with a zero budget opens no file`() {
        val root = Files.createTempDirectory("fastio-budget-grep")
        Files.write(root.resolve("a.txt"), "needle\n".toByteArray())
        val out = GrepOutcome()

        grepTree(root, "/ws", Regex("needle"), limit = 0, maxEntries = 1_000, out = out)

        assertTrue(out.matchesCapped)
        assertEquals("no file may be opened", 0L, out.filesScanned)
        assertEquals("…and none charged as visited", 0L, out.visited)
    }

    @Test
    fun `the two engines agree on a spent budget`() {
        // The contract the pair is being aligned on: same input, same answer.
        val root = findFixture()
        val f = FindOutcome(); val g = GrepOutcome()
        findTree(root, "/ws", null, null, 0, 1_000, f)
        grepTree(root, "/ws", Regex("alpha"), 0, 1_000, g)
        assertEquals(f.matchesCapped, g.matchesCapped)
        assertEquals(0L, f.visited)
        assertEquals(0L, g.visited)
    }

    @Test
    fun `a positive budget still walks`() {
        val root = findFixture()
        val out = FindOutcome()
        findTree(root, "/ws", null, null, limit = 1, maxEntries = 1_000, out = out)
        assertTrue("one hit is listed", out.matches.size == 1)
        assertTrue(out.matchesCapped)
        // `find`'s totals stay authoritative over the whole tree — the walk is
        // NOT stopped at the cap (that is the documented contract).
        assertEquals(6L, out.matched)
        assertEquals(2L, out.dirs)
        assertEquals(4L, out.files)
    }

    // ── F-11: one entry budget per command ──────────────────────────────────

    /** The `du` body, sliced by declaration. */
    private fun handlerBody(fn: String): String {
        val src = handlerSource()
        val start = src.indexOf(fn)
        assertTrue("$fn must exist", start >= 0)
        var depth = 0
        var i = src.indexOf('{', start)
        val from = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced $fn")
    }

    private fun handlerSource(): String = readRepoFile(HANDLER_REL)

    /**
     * The handler, found from the REPO ROOT (the JVM probe runs with the module
     * as cwd; Gradle runs with the project root) — the same walk
     * SessionsOffloadHandlerValidationTest uses.
     */
    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * The wiring, as a predicate so the negative controls can run it against a
     * mutated copy: the walk is handed a VARIABLE that is decremented by what
     * each root visited, not the per-call constant.
     */
    private fun budgetIsSharedAcrossRoots(body: String): Boolean {
        val declared = Regex("""var\s+(\w*[Bb]udget\w*)\s*=\s*maxEntries""").find(body)
            ?: return false
        val name = declared.groupValues[1]
        // Handed to the walk as a variable (not `maxEntries`)…
        if (!body.contains(Regex("""(findTree|grepTree|walkForSize)\([^)]*\b$name\b"""))) return false
        // …and decremented by what the root actually visited.
        if (!body.contains(Regex("""$name\s*=\s*\($name\s*-\s*\w+\.visited\)"""))) return false
        // The per-call constant must not reach the walk any more.
        if (body.contains(Regex("""(findTree|grepTree|walkForSize)\([^)]*\bmaxEntries\b"""))) return false
        return true
    }

    @Test
    fun `du spends one budget across every root`() {
        assertTrue("du must share its entry budget across roots", budgetIsSharedAcrossRoots(handlerBody("private fun du(")))
    }

    @Test
    fun `find spends one budget across every root`() {
        assertTrue("find must share its entry budget across roots", budgetIsSharedAcrossRoots(handlerBody("private fun find(")))
    }

    @Test
    fun `grep spends one budget across every root`() {
        assertTrue("grep must share its entry budget across roots", budgetIsSharedAcrossRoots(handlerBody("private fun grep(")))
    }

    // ── negative controls: each wiring check must have teeth ────────────────

    @Test
    fun `the old per-root shape is caught`() {
        val body = handlerBody("private fun find(")
        val old = body
            .replace("var entryBudget = maxEntries", "")
            .replace("findTree(path, resolution.guestPath, matcher, typeFilter, remaining, entryBudget, outcome)", "findTree(path, resolution.guestPath, matcher, typeFilter, remaining, maxEntries, outcome)")
            .replace("entryBudget = (entryBudget - outcome.visited).coerceAtLeast(0)", "")
        assertFalse("a per-root maxEntries must not pass the check", budgetIsSharedAcrossRoots(old))
    }

    @Test
    fun `a budget that is never decremented is caught`() {
        val body = handlerBody("private fun grep(")
            .replace("entryBudget = (entryBudget - outcome.visited).coerceAtLeast(0)", "")
        assertFalse("declaring the variable is not enough — it must be spent", budgetIsSharedAcrossRoots(body))
    }

    @Test
    fun `the help text states the per-command scope`() {
        assertTrue(
            "the help must say the budget is per command, not per path",
            handlerSource().contains("ONE budget per COMMAND"),
        )
    }
}
