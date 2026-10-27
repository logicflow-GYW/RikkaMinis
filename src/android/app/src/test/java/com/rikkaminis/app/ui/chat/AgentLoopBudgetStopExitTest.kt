package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit3 F-02] Source-shape pin for the budget-stop EXIT shape of
 * `AgentLoopEngine.runAgentLoop` (the loop itself cannot be driven on the JVM —
 * it needs a host fake; the same reason [AgentLoopTier1GuardTest] pins
 * invariants instead of transitions).
 *
 * The regression (P0, fixed 2026-10-15): the two execution-budget gates live
 * inside the turn body's retry `while (!collectDone)` — the deadline gate and
 * the provider-attempt gate. Both set `t7BudgetStopReason` and then `break`ed
 * *bare*, which left only that retry loop. Control flow then fell through to
 * the "the turn's stream completed without error" tail, where it
 *   * recorded `RouteOutcome.Success` for the member whose attempt budget had
 *     just refused it (clearing its cooldown / circuit state),
 *   * appended an empty ASSISTANT entry to `agentHistory`, and
 *   * burned the remaining turns against a reducer already in FINALIZING
 *     (field log 2026-09-07: "3 empty turns burned in 4ms against a refused
 *     provider-attempt gate").
 *
 * Invariant pinned here: inside `AgentLoopEngine.kt`, every `t7BudgetStopReason
 * = "<reason>"` branch whose first `break` sits in a NESTED loop must break to
 * the outermost enclosing loop's label (`break@turnLoop`). Two of the four
 * assignment sites (the turn-entry deadline / turn_limit gates) sit at
 * for-body depth, where a bare `break` already leaves the run — those must stay
 * bare, so the check is "nested ⇒ labeled", not "always labeled".
 *
 * The same invariant is enforced tree-wide by
 * `scripts/scan/budget_stop_break_guard.py`; this test additionally runs the
 * negative controls IN the Gradle suite, so a silently-disabled scanner still
 * fails a build.
 */
class AgentLoopBudgetStopExitTest {

    private val sourcePath =
        "app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

    /** What one budget-stop branch looked like to the probe. */
    private data class Finding(
        val assignLine: Int,
        val breakLine: Int,
        val breakText: String,
        val loopCount: Int,
    )

    @Test
    fun `budget stop branches nested in the retry loop break to the turn loop`() {
        val src = readRepoFile(sourcePath)
        val findings = assertBudgetStopBreaksTargetTurnLoop(src)

        // Self-check: the probe must actually be looking at the four budget
        // gates of this file (a probe that silently matches nothing would
        // "pass" every source, mutated or not).
        assertEquals(
            "expected the four AgentLoopEngine budget-stop assignment sites",
            4,
            findings.size,
        )
        val nested = findings.filter { it.loopCount >= 2 }
        assertEquals(
            "the deadline + provider-attempt gates are the nested pair",
            2,
            nested.size,
        )
        nested.forEach {
            assertEquals(
                "nested gate at line ${it.assignLine} must exit the run",
                "break@turnLoop",
                it.breakText,
            )
        }
        // The two turn-entry gates stay bare (for-body depth: a bare break
        // already leaves the run, and a label there would be noise).
        findings.filter { it.loopCount < 2 }.forEach {
            assertEquals("break", it.breakText)
        }
    }

    @Test
    fun `the turn loop is labeled`() {
        val src = readRepoFile(sourcePath)
        assertTrue(
            "the outer turn loop must carry the turnLoop@ label",
            src.contains("turnLoop@ for (turn in 0 until maxTurnsThisRun)"),
        )
    }

    // ── negative controls: the probe must fail on the pre-fix shapes ──────

    @Test
    fun `negative control - a bare break in the nested gate is rejected`() {
        val src = readRepoFile(sourcePath)
        val mutated = src.replace("break@turnLoop", "break")
        assertTrue("mutation must change the source", mutated != src)
        expectProbeFailure(mutated, "bare break out of the nested budget gate")
    }

    @Test
    fun `negative control - an unlabeled turn loop is rejected`() {
        val src = readRepoFile(sourcePath)
        val mutated = src.replace(
            "turnLoop@ for (turn in 0 until maxTurnsThisRun)",
            "for (turn in 0 until maxTurnsThisRun)",
        )
        assertTrue("mutation must change the source", mutated != src)
        expectProbeFailure(mutated, "budget stop with no label to break to")
    }

    @Test
    fun `negative control - a break to the wrong label is rejected`() {
        val src = readRepoFile(sourcePath)
        // Re-aim one nested break at an inner label instead of the turn loop.
        // Anchor on a whole CODE line: `break@turnLoop` also appears inside the
        // explanatory comments, and a mutation that only rewrote those would
        // leave the probe legitimately green (found while writing this test —
        // the first version used replaceFirst on the raw text and passed the
        // probe, i.e. it mutated a comment).
        val codeBreak = Regex("(?m)^(\\s*)break@turnLoop\\s*$")
        val mutated = codeBreak.replace(src) { m -> "${m.groupValues[1]}break@retryLoop" }
        assertTrue("mutation must change the source", mutated != src)
        expectProbeFailure(mutated, "break aimed at the wrong loop")
    }

    // ── the probe ─────────────────────────────────────────────────────────

    private fun expectProbeFailure(mutated: String, what: String) {
        var caught = false
        try {
            assertBudgetStopBreaksTargetTurnLoop(mutated)
        } catch (_: AssertionError) {
            caught = true
        }
        assertTrue("negative control: probe passed on mutated source ($what)", caught)
    }

    /**
     * Walk every `t7BudgetStopReason = "…"` assignment, take the first `break`
     * inside the same brace block, and require it to be labeled with the
     * outermost enclosing loop's label whenever that break is nested in two or
     * more loops. Returns what it inspected.
     */
    private fun assertBudgetStopBreaksTargetTurnLoop(src: String): List<Finding> {
        val lines = src.split("\n")
        val clean = cleanCode(lines)
        val depth = IntArray(clean.size)
        val after = IntArray(clean.size)
        var d = 0
        clean.indices.forEach { i ->
            depth[i] = d
            after[i] = d + clean[i].count { it == '{' } - clean[i].count { it == '}' }
            d = after[i]
        }

        val loopHeader = Regex("""^\s*(?:(\w+)@\s*)?(?:while|for)\s*\(.*\)\s*\{\s*$""")
        val loops = mutableListOf<Triple<Int, Int, String?>>() // header, end, label
        clean.indices.forEach { i ->
            val m = loopHeader.find(clean[i]) ?: return@forEach
            var end = clean.size
            for (k in i + 1 until clean.size) {
                if (after[k] == depth[i]) {
                    end = k + 1
                    break
                }
            }
            loops += Triple(i + 1, end, m.groupValues[1].ifEmpty { null })
        }

        val assignRe = Regex("""t7BudgetStopReason\s*=\s*"""")
        val breakRe = Regex("""^\s*(break|break@\w+)\s*$""")
        val findings = mutableListOf<Finding>()

        clean.indices.forEach { i ->
            if (!assignRe.containsMatchIn(lines[i])) return@forEach
            val blockEnd = (i + 1 until clean.size).firstOrNull { depth[it] < depth[i] } ?: clean.size
            val breakAt = (i + 1 until blockEnd).firstOrNull { breakRe.matches(clean[it]) } ?: return@forEach
            val breakText = breakRe.find(clean[breakAt])!!.groupValues[1]
            val enclosing = loops.filter { it.first <= breakAt + 1 && breakAt + 1 <= it.second }

            findings += Finding(
                assignLine = i + 1,
                breakLine = breakAt + 1,
                breakText = breakText,
                loopCount = enclosing.size,
            )

            if (enclosing.size >= 2) {
                val outermost = enclosing.first() // smallest header line = outermost
                val label = outermost.third
                assertTrue(
                    "budget stop at line ${i + 1}: the `break` at line ${breakAt + 1} is " +
                        "nested in ${enclosing.size} loops but is not labeled",
                    label != null,
                )
                assertEquals(
                    "budget stop at line ${i + 1}: `$breakText` at line ${breakAt + 1} " +
                        "leaves only the innermost loop — it must `break@$label`",
                    "break@$label",
                    breakText,
                )
            }
        }
        return findings
    }

    /** Strip block comments, line comments and literals; keep the shape. */
    private fun cleanCode(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        var inBlock = false
        for (raw in lines) {
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) {
                    out += ""
                    continue
                }
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                line = if (end < 0) {
                    inBlock = true
                    line.substring(0, start)
                } else {
                    line.substring(0, start) + line.substring(end + 2)
                }
                if (inBlock) break
            }
            val sb = StringBuilder()
            var inStr = false
            var inChar = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inStr || inChar -> {
                        if (c == '\\') {
                            i += 2
                            continue
                        }
                        if (inStr && c == '"') inStr = false
                        if (inChar && c == '\'') inChar = false
                    }
                    c == '"' -> inStr = true
                    c == '\'' -> inChar = true
                    c == '/' && i + 1 < line.length && line[i + 1] == '/' -> break
                    else -> sb.append(c)
                }
                i++
            }
            out += sb.toString()
        }
        return out
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; the sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
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
